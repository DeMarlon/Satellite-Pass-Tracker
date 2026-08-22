package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobePolygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_land.json"

/**
 * Loads Natural Earth's 110m land layer, which is provided as proper (already closed)
 * Polygon geometry - unlike the coastline layer, which traces landmasses as several open
 * LineString arcs and requires fragile heuristic stitching to reconstruct closed shapes.
 */
object LandRepository {

    @Volatile
    private var cache: List<GlobePolygon>? = null

    suspend fun loadLandPolygons(context: Context): List<GlobePolygon> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val polygons = parsePolygonFeatureCollection(context, ASSET_FILE_NAME)
            cache = polygons
            polygons
        }
    }
}
