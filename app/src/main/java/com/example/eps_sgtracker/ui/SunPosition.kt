package com.example.eps_sgtracker.ui

import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos

/**
 * Approximate subsolar point (the point on Earth where the sun is directly overhead) for the
 * given UTC time, as a unit-sphere direction in the same Earth-fixed frame used everywhere else
 * on the globe. Accurate to within about a degree (ignores the equation of time) - fine for a
 * visual day/night terminator, not for precision ephemeris use.
 */
fun sunDirectionAt(utcMillis: Long): Vec3 {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
    val hourUtc = calendar.get(Calendar.HOUR_OF_DAY) +
        calendar.get(Calendar.MINUTE) / 60.0 +
        calendar.get(Calendar.SECOND) / 3600.0

    val declinationDeg = -23.44 * cos(Math.toRadians(360.0 / 365.0 * (dayOfYear + 10)))
    var subsolarLonDeg = (12.0 - hourUtc) * 15.0
    while (subsolarLonDeg > 180.0) subsolarLonDeg -= 360.0
    while (subsolarLonDeg < -180.0) subsolarLonDeg += 360.0

    return latLonDegToUnitSphere(declinationDeg, subsolarLonDeg)
}
