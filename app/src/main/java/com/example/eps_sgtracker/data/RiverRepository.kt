package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobeRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_rivers_lake_centerlines.geojson"

/** Loads Natural Earth's 110m rivers + lake-centerlines layer (major rivers only at this scale). */
object RiverRepository {

    @Volatile
    private var cache: List<GlobeRing>? = null

    suspend fun loadRivers(context: Context): List<GlobeRing> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val rings = parseLineFeatureCollection(context, ASSET_FILE_NAME)
            cache = rings
            rings
        }
    }
}
