package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobePolygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_glaciated_areas.json"

/** Loads Natural Earth's 110m glaciated areas layer (ice sheets/major glaciers), rendered white. */
object GlacierRepository {

    @Volatile
    private var cache: List<GlobePolygon>? = null

    suspend fun loadGlaciers(context: Context): List<GlobePolygon> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val polygons = parsePolygonFeatureCollection(context, ASSET_FILE_NAME)
            cache = polygons
            polygons
        }
    }
}
