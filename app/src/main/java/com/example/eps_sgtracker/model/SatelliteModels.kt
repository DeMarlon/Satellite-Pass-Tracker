package com.example.eps_sgtracker.model

import java.util.UUID

// Now purely tracking IDs
data class TrackedSatellite(
    val noradId: Int
)

data class GroundStation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
    val isCustom: Boolean = false
)

data class SatellitePass(
    val satelliteName: String,
    val noradId: Int,
    val groundStationCode: String,
    val aosMillis: Long,
    val losMillis: Long,
    val orbitNumber: Int,
    val isNext: Boolean = false,
    val isGeo: Boolean = false,

    // Pass-quality figures lifted straight off predict4java's SatPassTime, which the pass
    // computations already hold and previously discarded - the SGP4 work behind them is paid for
    // whether or not anything reads them. [maxElevationDeg] is what separates a 9-degree grazing
    // pass from an 85-degree overhead one; without it every entry in the list looks equally worth
    // catching.
    //
    // All angles are DEGREES: SatPassTime.getMaxEl() converts from radians internally and
    // getAos/LosAzimuth() return whole degrees - unlike SatPos, whose azimuth/elevation are
    // radians. Defaults exist so the geostationary construction site, which has no SatPassTime at
    // all, stays valid unchanged: a GEO satellite has no horizon-to-horizon arc for these to
    // describe.
    val maxElevationDeg: Double = 0.0,
    // Time of closest approach - the instant of maximum elevation.
    val tcaMillis: Long = 0L,
    val aosAzimuthDeg: Int = 0,
    val losAzimuthDeg: Int = 0
)

/**
 * Whether [this] pass has dropped out of the display window at [nowMillis], given the user's
 * post-LOS grace period.
 *
 * Shared because every screen has to agree on the answer. The same threshold was previously written
 * out at five separate sites in three different algebraic forms - and one of them used `>=` where the
 * others used the strict complement, so at the exact boundary instant one kept a pass the rest had
 * dropped. Two screens disagreeing about which passes exist is not hypothetical here: it is what
 * left hours-old passes sitting on the Plan screen.
 */
fun SatellitePass.hasExpired(nowMillis: Long, graceMillis: Long): Boolean =
    losMillis + graceMillis <= nowMillis

/**
 * Identifies one pass as it currently stands, for use as a list key.
 *
 * Guarantees UNIQUENESS within a single computed list, which is what a LazyColumn needs - it hard
 * fails on duplicate keys. It is deliberately not stable across recomputes: fresh orbital elements
 * shift AOS by a second or two and mint a new key.
 *
 * That is the opposite guarantee from [PassReminder]'s key, which trades uniqueness for stability so
 * a reminder survives a refresh. The two are not interchangeable, which is exactly why both are named
 * rather than being assembled inline wherever they happen to be needed.
 */
fun passInstanceKey(pass: SatellitePass): String =
    "${pass.noradId}|${pass.groundStationCode}|${pass.aosMillis}"