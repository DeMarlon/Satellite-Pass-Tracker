package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.GlobePolygon
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a GeoJSON FeatureCollection of Polygon/MultiPolygon geometries (e.g. Natural Earth's
 * 110m land, glaciated-areas, or lakes layers) into unit-sphere [GlobePolygon]s. A MultiPolygon
 * feature's disjoint parts are flattened into one [GlobePolygon] with every ring from every part
 * in the same flat rings list - safe because every consumer (buildVisiblePolygonPath) already
 * clips and culls each ring independently before merging results, so a far-hemisphere part still
 * culls correctly on its own regardless of which sibling part it's grouped with.
 */
fun parsePolygonFeatureCollection(context: Context, assetFileName: String): List<GlobePolygon> {
    val text = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
    val features = JSONObject(text).getJSONArray("features")
    val polygons = ArrayList<GlobePolygon>(features.length())
    for (i in 0 until features.length()) {
        val geometry = features.getJSONObject(i).getJSONObject("geometry")
        polygons.add(GlobePolygon(rings = parseRingsFromGeometry(geometry)))
    }
    return polygons
}

// Polygon: coordinates = array of rings (rings[0] = outer boundary, rest = holes).
// MultiPolygon: coordinates = array of Polygon-shaped arrays (one per disjoint part) - flattened
// here into a single ring list, per this file's doc comment above.
private fun parseRingsFromGeometry(geometry: JSONObject): List<List<Vec3>> {
    val coordinates = geometry.getJSONArray("coordinates")
    return when (geometry.getString("type")) {
        "MultiPolygon" -> {
            val rings = ArrayList<List<Vec3>>()
            for (p in 0 until coordinates.length()) {
                val partRingsJson = coordinates.getJSONArray(p)
                for (r in 0 until partRingsJson.length()) {
                    rings.add(parseRing(partRingsJson.getJSONArray(r)))
                }
            }
            rings
        }
        else -> { // "Polygon"
            val rings = ArrayList<List<Vec3>>(coordinates.length())
            for (r in 0 until coordinates.length()) {
                rings.add(parseRing(coordinates.getJSONArray(r)))
            }
            rings
        }
    }
}

private fun parseRing(coordinates: JSONArray): List<Vec3> {
    val points = ArrayList<Vec3>(coordinates.length())
    for (j in 0 until coordinates.length()) {
        val pair = coordinates.getJSONArray(j)
        points.add(latLonDegToUnitSphere(latDeg = pair.getDouble(1), lonDeg = pair.getDouble(0)))
    }
    return points
}
