package com.example.eps_sgtracker.model

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Matches TrackerViewModel.getSatellite3DPosition's unit-sphere convention exactly:
// x = cos(lat)*sin(lon), y = sin(lat), z = cos(lat)*cos(lon), radius = 1.0
fun latLonDegToUnitSphere(latDeg: Double, lonDeg: Double): Vec3 {
    val lat = Math.toRadians(latDeg)
    val lon = Math.toRadians(lonDeg)
    val cosLat = cos(lat)
    return Vec3(
        x = (cosLat * sin(lon)).toFloat(),
        y = sin(lat).toFloat(),
        z = (cosLat * cos(lon)).toFloat()
    )
}

// Rescales a point back onto the unit sphere - needed for the rare fallback path where a
// country's label anchor is derived by averaging ring points (which pulls slightly inside the
// sphere) rather than from Natural Earth's own LABEL_X/LABEL_Y, since every consumer of a Vec3
// "position" (isObscuredByGlobe, projectRotated, orientation.rotate) assumes radius 1.
fun normalize(v: Vec3): Vec3 {
    val n = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    if (n < 1e-6f) return Vec3(0f, 0f, 1f)
    return Vec3(v.x / n, v.y / n, v.z / n)
}
