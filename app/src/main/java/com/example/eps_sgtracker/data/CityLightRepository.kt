package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.CityLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_populated_places.geojson"

/** Loads Natural Earth's 110m populated-places layer, drawn as lights on the globe's night side. */
object CityLightRepository {

    @Volatile
    private var cache: List<CityLight>? = null

    suspend fun loadCityLights(context: Context): List<CityLight> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val lights = parseCityLights(context, ASSET_FILE_NAME)
            cache = lights
            lights
        }
    }
}
