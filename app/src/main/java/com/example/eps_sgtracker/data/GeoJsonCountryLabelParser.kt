package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.CountryLabel
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import com.example.eps_sgtracker.model.normalize
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Parses ONLY the name + label-placement data out of a GeoJSON FeatureCollection of country
 * polygons (e.g. Natural Earth's 110m admin-0 countries layer) - deliberately never retains the
 * underlying ring/point geometry, since country borders are rendered from a separate dedicated
 * boundary-line layer (see GeoJsonLineParser/BorderRepository), not from this polygon data. Only
 * three small fields per country survive parsing; the source file's coordinate arrays are
 * discarded once each feature's anchor/extent are computed.
 */
fun parseCountryLabels(context: Context, assetFileName: String): List<CountryLabel> {
    val text = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
    val features = JSONObject(text).getJSONArray("features")
    val labels = ArrayList<CountryLabel>(features.length())
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val properties = feature.getJSONObject("properties")
        val name = properties.optString("NAME", "")
        if (name.isBlank()) continue
        val geometry = feature.getJSONObject("geometry")

        // Natural Earth's own curated cartographic label point - preferred over an averaged
        // centroid, which can land off-landmass for concave/crescent-shaped countries.
        val labelX = properties.optDouble("LABEL_X", Double.NaN)
        val labelY = properties.optDouble("LABEL_Y", Double.NaN)
        val anchor = if (!labelX.isNaN() && !labelY.isNaN()) {
            latLonDegToUnitSphere(latDeg = labelY, lonDeg = labelX)
        } else {
            normalize(averageOuterRingPoints(geometry))
        }

        labels.add(CountryLabel(name = name, anchor = anchor, extentDeg = largestPartExtentDeg(geometry)))
    }
    return labels
}

// The largest disjoint part's own lat/lon span (Polygon has exactly one part; MultiPolygon may
// have several, e.g. mainland France vs. its overseas territories) - only this span drives the
// on-screen-size LOD check, so a country's other, smaller parts never inflate its label's
// apparent size.
private fun largestPartExtentDeg(geometry: JSONObject): Float {
    val coordinates = geometry.getJSONArray("coordinates")
    return when (geometry.getString("type")) {
        "MultiPolygon" -> {
            var maxExtent = 0f
            for (p in 0 until coordinates.length()) {
                val partRings = coordinates.getJSONArray(p)
                if (partRings.length() == 0) continue
                maxExtent = max(maxExtent, ringExtentDeg(partRings.getJSONArray(0)))
            }
            maxExtent
        }
        else -> { // "Polygon"
            if (coordinates.length() == 0) 0f else ringExtentDeg(coordinates.getJSONArray(0))
        }
    }
}

private fun ringExtentDeg(ring: JSONArray): Float {
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    for (j in 0 until ring.length()) {
        val pair = ring.getJSONArray(j)
        val lon = pair.getDouble(0)
        val lat = pair.getDouble(1)
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }
    val latSpan = maxLat - minLat
    val rawLonSpan = maxLon - minLon
    // A single part that itself straddles the antimeridian (raw longitudes clustering near +-180
    // on both sides) would otherwise report a near-360deg span instead of its true narrow extent.
    val lonSpan = if (rawLonSpan > 180.0) 360.0 - rawLonSpan else rawLonSpan
    return max(latSpan, lonSpan).toFloat()
}

// Fallback anchor only, for the (unexpected, per the actual downloaded file) case LABEL_X/Y are
// missing - averages the first part's outer ring rather than the largest, since this is a rare
// defensive path where "good enough" beats extra bookkeeping.
private fun averageOuterRingPoints(geometry: JSONObject): Vec3 {
    val coordinates = geometry.getJSONArray("coordinates")
    val outerRing = when (geometry.getString("type")) {
        "MultiPolygon" -> coordinates.getJSONArray(0).getJSONArray(0)
        else -> coordinates.getJSONArray(0)
    }
    var sumX = 0.0
    var sumY = 0.0
    var sumZ = 0.0
    val n = outerRing.length()
    for (j in 0 until n) {
        val pair = outerRing.getJSONArray(j)
        val v = latLonDegToUnitSphere(latDeg = pair.getDouble(1), lonDeg = pair.getDouble(0))
        sumX += v.x
        sumY += v.y
        sumZ += v.z
    }
    return Vec3((sumX / n).toFloat(), (sumY / n).toFloat(), (sumZ / n).toFloat())
}
