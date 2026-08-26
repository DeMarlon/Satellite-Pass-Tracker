package com.example.eps_sgtracker.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.example.eps_sgtracker.network.CelestrakApi
import com.example.eps_sgtracker.network.CelestrakHttpException
import com.example.eps_sgtracker.network.CelestrakNoDataException

class TleRepository(
    private val tleDao: TleDao
) {
    private val twoDaysMillis = 2 * 24 * 60 * 60 * 1000L

    private val _refreshHalt = MutableStateFlow<RefreshHalt?>(null)

    /** Non-null while querying is suspended after a non-200 response. Observed by the UI. */
    val refreshHalt: StateFlow<RefreshHalt?> = _refreshHalt.asStateFlow()

    /**
     * Reads a satellite's element set, refreshing first if the cached copy is past the freshness
     * window, and reports which of those actually happened.
     *
     * This replaces a `checkAndRefreshIfExpired(id)` + `getTle(id)` pair that could not express
     * failure. The refresh signalled failure only by returning null, and the wrapper around it
     * returned Unit, so the null had nowhere to go; the read that followed then handed back the
     * stale cached row and the caller marked the satellite successfully resolved. A refresh that
     * failed was therefore indistinguishable from one that succeeded - the app went on propagating
     * from months-old elements with nothing anywhere saying so. The old pair also double-fetched
     * when nothing was cached, since the read would start its own second retry cycle after the
     * refresh had already failed - three more HTTP errors against CelesTrak's budget for nothing.
     */
    suspend fun getTleRefreshingIfExpired(noradId: Int): TleFetchResult = withContext(Dispatchers.IO) {
        val cached = tleDao.getTleForSatellite(noradId)

        // Phrased so the compiler can smart-cast, rather than computing an `expired` flag and then
        // asserting non-nullity with !!. Same logic, but the null case is proved instead of claimed.
        if (cached != null &&
            (System.currentTimeMillis() - cached.lastUpdatedMillis) <= twoDaysMillis
        ) {
            return@withContext TleFetchResult(cached.rawTleText, refreshFailed = false)
        }

        val refreshed = forceRefreshSatelliteTle(noradId)
        return@withContext when {
            refreshed != null -> TleFetchResult(refreshed, refreshFailed = false)
            // A refresh was due and did not succeed. Still serve whatever is cached so the
            // satellite keeps tracking rather than vanishing - but say so, so the UI can show the
            // same failure state a manual Force Update would produce.
            else -> TleFetchResult(cached?.rawTleText, refreshFailed = true)
        }
    }

    // A single failed fetch here used to leave a satellite permanently stuck for the rest of
    // the session (nothing else automatically retries a missing TLE - see getTleRefreshingIfExpired),
    // so transient network hiccups (timeouts, momentary rate-limiting from a burst of concurrent
    // requests at startup) get a couple of quick retries before giving up.
    suspend fun forceRefreshSatelliteTle(noradId: Int): String? = withContext(Dispatchers.IO) {
        // "Stopping additional queries when these are detected" means exactly this: once CelesTrak
        // has answered with a non-200, every remaining satellite in the run - and any Force Update
        // tapped inside the window - returns without touching the network at all.
        if (isHalted()) return@withContext null

        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            try {
                val rawLines = CelestrakApi.fetchTle(noradId)
                // A non-blank response isn't necessarily a valid TLE - Celestrak can return a
                // non-blank error/rate-limit page with a 200 status. Caching that as if it were a
                // real result used to strand the satellite permanently: it counted as a
                // "successful" fetch (clearing the fetch-failed flag) but never parsed into a
                // usable TLE, so it was neither retried nor ever surfaced as failed - stuck on
                // "Fetching name..." until the 48h staleness window or a manual force-refresh.
                // Treating it the same as a network error here means it gets the same retries and
                // ultimately reports failure like any other bad fetch, instead of poisoning the
                // cache with unparseable text.
                val lineCount = rawLines.lines().count { it.isNotBlank() }
                if (lineCount >= 3) {
                    tleDao.insertOrUpdateTle(
                        TleEntity(
                            noradId = noradId,
                            rawTleText = rawLines,
                            lastUpdatedMillis = System.currentTimeMillis()
                        )
                    )
                    return@withContext rawLines
                }
            } catch (e: CancellationException) {
                // Must propagate - a caller timing out or cancelling this fetch (see
                // TrackerViewModel's per-satellite resolution) must not be reinterpreted as an
                // ordinary fetch failure by the catch below, which would misreport a cancelled
                // fetch as _satelliteFetchFailed instead of simply abandoning it.
                throw e
            } catch (e: CelestrakHttpException) {
                // Non-200. Never retried and it stops the entire run: CelesTrak's usage policy is
                // that repeating a 403 or 404 "is not going to change" the answer while counting
                // toward the 50-errors-in-2-hours threshold that firewalls an IP. The old code
                // retried this three times per satellite, so N failing satellites produced 3N
                // errors - a handful of Force Update taps was enough to cross that line.
                haltQuerying(e.code)
                return@withContext null
            } catch (e: CelestrakNoDataException) {
                // HTTP 200 with an empty array: this catalog number does not exist. Terminal for
                // this satellite, but deliberately NOT a halt - it cost nothing against the error
                // budget, and one mistyped NORAD ID must not stop every other satellite refreshing.
                return@withContext null
            } catch (e: Exception) {
                // Transport-level: DNS, dropped socket, timeout. The only failure a retry can fix.
                e.printStackTrace()
            }
            if (attempt < MAX_FETCH_ATTEMPTS - 1) delay(RETRY_BACKOFF_BASE_MS * (attempt + 1))
        }
        return@withContext null
    }

    /** True while a non-200 halt is in force; clears itself once the window has passed. */
    private fun isHalted(): Boolean {
        val halt = _refreshHalt.value ?: return false
        if (System.currentTimeMillis() >= halt.retryAtMillis) {
            _refreshHalt.value = null
            return false
        }
        return true
    }

    private fun haltQuerying(code: Int) {
        val now = System.currentTimeMillis()
        _refreshHalt.value = RefreshHalt(
            httpCode = code,
            haltedAtMillis = now,
            retryAtMillis = now + HALT_WINDOW_MS
        )
    }

    suspend fun deleteTle(noradId: Int) = withContext(Dispatchers.IO) {
        tleDao.deleteTle(noradId)
    }

    // Per-satellite last-fetch timestamps, reactive - so Setup can show each tracked satellite's
    // own "last OMM update" independent of the aggregate MAX() used by getLatestTleTimestamp,
    // which only tells you the freshest entry in the whole cache, not whether THIS satellite's
    // own data ever actually refreshed.
    val allTleTimestampsFlow: Flow<Map<Int, Long>> = tleDao.getAllStoredTlesFlow().map { list ->
        list.associate { it.noradId to it.lastUpdatedMillis }
    }

    // FIXED: Uses the exact Flow method from your TleDao
    suspend fun getLatestTleTimestamp(): Long = withContext(Dispatchers.IO) {
        try {
            val allStored = tleDao.getAllStoredTlesFlow().first()
            return@withContext allStored.maxOfOrNull { it.lastUpdatedMillis } ?: 0L
        } catch (e: Exception) {
            return@withContext 0L
        }
    }
}

/**
 * Outcome of [TleRepository.getTleRefreshingIfExpired].
 *
 * [rawTleText] is whatever the app should use: a freshly fetched element set, the still-valid
 * cached one, or - when a refresh was due but failed - the previously cached one. [refreshFailed]
 * distinguishes that last case, which is otherwise invisible.
 */
data class TleFetchResult(val rawTleText: String?, val refreshFailed: Boolean)

// Retries exist only for transport failures - DNS, dropped sockets, timeouts - which are the sole
// category a repeat can fix. A CelesTrak non-200 is terminal on the first response.
private const val MAX_FETCH_ATTEMPTS = 3
private const val RETRY_BACKOFF_BASE_MS = 800L

// How long querying stays suspended after a non-200. Comfortably inside CelesTrak's 2-hour error
// window so a burst cannot accumulate toward the 50-error firewall threshold; short enough that a
// transient block clears without the app looking broken; and free in practice, since CelesTrak only
// refreshes GP data every 2 hours, so there is nothing new to fetch inside the window anyway.
private const val HALT_WINDOW_MS = 30 * 60 * 1000L

/**
 * Recorded when CelesTrak answers with a non-200 and querying is suspended as a result.
 *
 * Exists so the halt can be *reported* rather than silently swallowed - the app previously turned a
 * failed refresh into an invisible fallback onto stale elements, and a bare "Update failed" invites
 * the user to tap Force Update again, which is precisely the behaviour that gets an IP firewalled.
 */
data class RefreshHalt(
    val httpCode: Int,
    val haltedAtMillis: Long,
    val retryAtMillis: Long
)
