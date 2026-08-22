package com.example.eps_sgtracker.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.example.eps_sgtracker.network.CelestrakApi

class TleRepository(
    private val tleDao: TleDao
) {
    private val twoDaysMillis = 2 * 24 * 60 * 60 * 1000L

    suspend fun getTle(noradId: Int): String? = withContext(Dispatchers.IO) {
        val cached = tleDao.getTleForSatellite(noradId)
        if (cached != null) {
            return@withContext cached.rawTleText
        }
        return@withContext forceRefreshSatelliteTle(noradId)
    }

    // A single failed fetch here used to leave a satellite permanently stuck for the rest of
    // the session (nothing else automatically retries a missing TLE - see checkAndRefreshIfExpired),
    // so transient network hiccups (timeouts, momentary rate-limiting from a burst of concurrent
    // requests at startup) get a couple of quick retries before giving up.
    suspend fun forceRefreshSatelliteTle(noradId: Int): String? = withContext(Dispatchers.IO) {
        repeat(3) { attempt ->
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (attempt < 2) delay(800L * (attempt + 1))
        }
        return@withContext null
    }

    suspend fun checkAndRefreshIfExpired(noradId: Int) = withContext(Dispatchers.IO) {
        val cached = tleDao.getTleForSatellite(noradId)
        val currentTime = System.currentTimeMillis()

        if (cached == null || (currentTime - cached.lastUpdatedMillis) > twoDaysMillis) {
            forceRefreshSatelliteTle(noradId)
        }
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