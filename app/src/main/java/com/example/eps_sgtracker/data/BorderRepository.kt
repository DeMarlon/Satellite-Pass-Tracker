package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobeRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_admin_0_boundary_lines_land.geojson"

/**
 * Loads Natural Earth's 110m country boundary-lines layer - a dedicated line layer, not derived
 * from the countries polygon layer, so each shared border between two neighboring countries is
 * encoded once rather than twice (once per country's own polygon edge).
 */
object BorderRepository {

    @Volatile
    private var cache: List<GlobeRing>? = null

    suspend fun loadBorders(context: Context): List<GlobeRing> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val rings = parseLineFeatureCollection(context, ASSET_FILE_NAME)
            cache = rings
            rings
        }
    }
}
