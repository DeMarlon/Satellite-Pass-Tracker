package com.example.eps_sgtracker.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object CelestrakApi {
    // callTimeout bounds the *entire* call (connect+write+read combined), including a connection
    // that's technically still making progress but only trickling bytes - the default per-phase
    // timeouts (10s each) don't catch that case, since each individual read can still land inside
    // its own 10s window. Without this, a degraded connection can hold execute() blocked far
    // longer than any per-phase timeout implies, with no way for a coroutine cancellation to
    // preempt it since execute() is a synchronous, non-suspending call.
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // Fetches orbital data for a given NORAD catalog number and returns it as a classic 3-line
    // TLE text block (name, line1, line2), synthesized locally from CelesTrak's OMM/JSON format
    // via OmmToTleConverter. CelesTrak's own legacy FORMAT=TLE endpoint stopped serving *any* data
    // for catalog numbers >= 100000 once the real-world catalog crossed that threshold - its
    // fixed-width text format only has a 5-character field for the catalog number and physically
    // cannot represent 6 digits. FORMAT=JSON has no such limit and returns identical orbital data
    // for every catalog number, so it's used unconditionally here rather than only as a fallback
    // for large IDs - this fixes every satellite uniformly and matches CelesTrak's own
    // documented, forward-looking recommendation.
    // Runs on a background thread (Dispatchers.IO) since network calls
    // must never block the main/UI thread in Android.
    suspend fun fetchTle(noradId: Int): String = withContext(Dispatchers.IO) {
        OmmToTleConverter.toTleText(fetchOmmRecord(noradId))
    }

    private suspend fun fetchOmmRecord(noradId: Int): OmmRecord = withContext(Dispatchers.IO) {
        val url = "https://celestrak.org/NORAD/elements/gp.php?CATNR=$noradId&FORMAT=JSON"
        val request = Request.Builder().url(url).build()

        // The try covers the transport only - connecting, and reading the body, both of which can
        // fail with no usable answer ever arriving. It deliberately does NOT extend over the JSON
        // parse below; see classifyFetchFailure for why that boundary matters.
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Typed, carrying the status, because the caller's response to a non-200 is
                    // categorically different from its response to a dropped socket: CelesTrak's
                    // usage policy is that a non-200 means stop querying entirely, while a
                    // transport blip is worth retrying. A flat IOException made those
                    // indistinguishable.
                    throw CelestrakHttpException(response.code)
                }
                response.body.string()
            }
        } catch (e: IOException) {
            throw classifyFetchFailure(e)
        }

        // CelesTrak's GP/JSON endpoint always returns an array, even for a single-satellite
        // query - an empty array is how it reports an invalid/unrecognized catalog number (the
        // JSON-format equivalent of the old "No GP data found" plain-text response).
        val array = JSONArray(body)
        if (array.length() == 0) {
            // Distinct from CelestrakHttpException: this arrives as an HTTP *200* carrying an empty
            // array, so it costs nothing against CelesTrak's error budget and must NOT halt the run
            // - it just means this one catalog number does not exist. Retrying cannot fix it either.
            throw CelestrakNoDataException(noradId)
        }
        parseOmmRecord(array.getJSONObject(0))
    }

    // Throwing accessors (not opt*) for every orbitally-significant field: a malformed/partial
    // OMM record should fail loudly into TleRepository's existing retry path, not silently cache
    // a numerically-corrupt TLE (e.g. a silently-defaulted zero eccentricity would parse fine and
    // produce wildly wrong, undetected pass predictions).
    private fun parseOmmRecord(json: JSONObject): OmmRecord = OmmRecord(
        objectName = json.getString("OBJECT_NAME"),
        objectId = json.optString("OBJECT_ID", "").ifBlank { null },
        epoch = json.getString("EPOCH"),
        meanMotionDot = json.getDouble("MEAN_MOTION_DOT"),
        meanMotionDdot = json.getDouble("MEAN_MOTION_DDOT"),
        bstar = json.getDouble("BSTAR"),
        ephemerisType = json.getInt("EPHEMERIS_TYPE"),
        classificationType = json.getString("CLASSIFICATION_TYPE"),
        noradCatId = json.getInt("NORAD_CAT_ID"),
        elementSetNo = json.getInt("ELEMENT_SET_NO"),
        inclination = json.getDouble("INCLINATION"),
        raOfAscNode = json.getDouble("RA_OF_ASC_NODE"),
        eccentricity = json.getDouble("ECCENTRICITY"),
        argOfPericenter = json.getDouble("ARG_OF_PERICENTER"),
        meanAnomaly = json.getDouble("MEAN_ANOMALY"),
        meanMotion = json.getDouble("MEAN_MOTION"),
        revAtEpoch = json.getInt("REV_AT_EPOCH")
    )
}

/**
 * Sorts an [IOException] raised while fetching into "CelesTrak answered" versus "CelesTrak never
 * answered".
 *
 * This exists as a named function rather than an inline `if` for two reasons. First, it is a trap:
 * [CelestrakHttpException] and [CelestrakNoDataException] are themselves IOExceptions, so a naive
 * `catch (e: IOException) { throw CelestrakUnreachableException(e) }` would swallow both. Losing the
 * first one is the expensive mistake - a 403 would stop halting the refresh run, and that halt is
 * the entire mechanism keeping this device off CelesTrak's 50-errors-in-2-hours firewall list.
 * Second, [CelestrakApi] is an object with a private client and a hardcoded URL, so the catch block
 * itself cannot be unit-tested; this function can, and it is where the whole risk lives.
 *
 * Both sibling exceptions mean a real response ARRIVED and are passed through untouched. Only a
 * failure with no response at all - DNS, refused connection, dropped socket, callTimeout - is
 * genuinely "unreachable".
 */
internal fun classifyFetchFailure(e: IOException): IOException =
    if (e is CelestrakHttpException || e is CelestrakNoDataException) e
    else CelestrakUnreachableException(e)

/**
 * CelesTrak could not be reached at all: no HTTP response of any kind came back.
 *
 * Deliberately NOT a [CelestrakHttpException] and deliberately does not halt querying. Nothing was
 * received, so nothing counted against CelesTrak's error budget, and the user must stay free to
 * retry the moment their connection returns. It exists so the UI can say what actually happened
 * instead of blaming the satellite's catalog number, which is what a bare fetch failure looks like.
 */
class CelestrakUnreachableException(cause: IOException) :
    IOException("Could not reach celestrak.org", cause)

/**
 * CelesTrak answered with a non-2xx status.
 *
 * Per CelesTrak's usage policy, M2M clients "should immediately stop querying when it receives any
 * non-HTTP 200 responses and report the results to a human", and repeating a 403 or 404 "is not
 * going to change" the answer while counting toward the 50-errors-in-2-hours threshold that gets an
 * IP firewalled. So this is never retried, and it halts the whole refresh run.
 */
class CelestrakHttpException(val code: Int) : IOException("CelesTrak returned HTTP $code")

/**
 * An HTTP 200 whose GP array was empty - how this endpoint reports an unrecognised catalog number.
 *
 * Deliberately NOT a [CelestrakHttpException]: it is a successful response, so it neither counts
 * against the error budget nor justifies halting the other satellites in the run. It is terminal
 * for this NORAD ID only, and retrying is pointless.
 */
class CelestrakNoDataException(val noradId: Int) :
    IOException("No GP data found for NORAD ID $noradId")
