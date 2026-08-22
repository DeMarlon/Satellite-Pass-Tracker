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

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }
            response.body?.string() ?: throw IOException("Empty response body")
        }

        // CelesTrak's GP/JSON endpoint always returns an array, even for a single-satellite
        // query - an empty array is how it reports an invalid/unrecognized catalog number (the
        // JSON-format equivalent of the old "No GP data found" plain-text response).
        val array = JSONArray(body)
        if (array.length() == 0) {
            throw IOException("No GP data found for NORAD ID $noradId")
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
