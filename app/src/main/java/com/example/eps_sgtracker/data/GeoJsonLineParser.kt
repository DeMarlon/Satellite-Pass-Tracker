package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobeRing
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a GeoJSON FeatureCollection of LineString/MultiLineString geometries (e.g. Natural
 * Earth's 110m rivers or country boundary-lines layers) into open-chain [GlobeRing]s, all with
 * isClosed = false - rendered via the exact same open-chain path buildVisibleSubpaths already
 * uses for graticule meridians. A MultiLineString feature's parts become independent GlobeRings
 * with no merging or shared identity (rivers/borders don't need per-feature names).
 */
fun parseLineFeatureCollection(context: Context, assetFileName: String): List<GlobeRing> {
    val text = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
    val features = JSONObject(text).getJSONArray("features")
    val rings = ArrayList<GlobeRing>()
    for (i in 0 until features.length()) {
        val geometry = features.getJSONObject(i).getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")
        when (geometry.getString("type")) {
            "MultiLineString" -> {
                for (p in 0 until coordinates.length()) {
                    rings.add(GlobeRing(points = parseLine(coordinates.getJSONArray(p)), isClosed = false))
                }
            }
            else -> { // "LineString"
                rings.add(GlobeRing(points = parseLine(coordinates), isClosed = false))
            }
        }
    }
    return rings
}

private fun parseLine(coordinates: JSONArray): List<Vec3> {
    val points = ArrayList<Vec3>(coordinates.length())
    for (j in 0 until coordinates.length()) {
        val pair = coordinates.getJSONArray(j)
        points.add(latLonDegToUnitSphere(latDeg = pair.getDouble(1), lonDeg = pair.getDouble(0)))
    }
    return points
}
