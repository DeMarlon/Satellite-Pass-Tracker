package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.CityLight
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import org.json.JSONObject
import kotlin.math.ln

// Population bounds the log scale is normalized against. The lower bound is not the true minimum -
// Natural Earth's populated-places layer includes entries like Vatican City at ~800 people, which
// are present for being capitals rather than for being large. Clamping at 10k stops those from
// pinning the whole scale's bottom end and flattening the range everything else has to share.
private const val MIN_POPULATION = 10_000.0
private const val MAX_POPULATION = 30_000_000.0

/**
 * Parses a GeoJSON FeatureCollection of Point features into [CityLight]s - specifically Natural
 * Earth's populated-places layer.
 *
 * Coordinates come from the feature's own LATITUDE/LONGITUDE properties, which this layer carries
 * on every feature, with the Point geometry as a fallback. Same shortcut GeoJsonCountryLabelParser
 * takes with LABEL_X/LABEL_Y, and it keeps this parser from needing any geometry handling at all.
 */
fun parseCityLights(context: Context, assetFileName: String): List<CityLight> {
    val text = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
    val features = JSONObject(text).getJSONArray("features")
    val lights = ArrayList<CityLight>(features.length())

    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val properties = feature.optJSONObject("properties") ?: continue

        var lat = properties.optDouble("LATITUDE", Double.NaN)
        var lon = properties.optDouble("LONGITUDE", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            lon = coordinates.getDouble(0)
            lat = coordinates.getDouble(1)
        }

        val population = properties.optDouble("POP_MAX", 0.0).coerceIn(MIN_POPULATION, MAX_POPULATION)
        val magnitude = (ln(population / MIN_POPULATION) / ln(MAX_POPULATION / MIN_POPULATION))
            .toFloat()
            .coerceIn(0f, 1f)

        lights.add(
            CityLight(
                position = latLonDegToUnitSphere(latDeg = lat, lonDeg = lon),
                magnitude = magnitude
            )
        )
    }
    return lights
}
