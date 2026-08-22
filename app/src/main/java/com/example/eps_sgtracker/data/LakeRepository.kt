package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobePolygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_lakes.geojson"

/** Loads Natural Earth's 110m lakes layer, rendered as filled water bodies over land. */
object LakeRepository {

    @Volatile
    private var cache: List<GlobePolygon>? = null

    suspend fun loadLakes(context: Context): List<GlobePolygon> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val polygons = parsePolygonFeatureCollection(context, ASSET_FILE_NAME)
            cache = polygons
            polygons
        }
    }
}
