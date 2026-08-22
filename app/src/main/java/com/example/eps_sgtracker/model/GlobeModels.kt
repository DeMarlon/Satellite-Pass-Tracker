package com.example.eps_sgtracker.model

data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * A patch of sphere containing every point of some ring: everything in it lies within
 * [angularRadius] radians of [center].
 *
 * The renderer's cheap "is this worth drawing at all" test. Deciding that from the geometry itself
 * means rotating and clipping the whole ring, which is the work being avoided - so each ring carries
 * one of these, computed once, and answers from that instead.
 *
 * Not the minimal enclosing cap, which is a much harder problem for no benefit here: the centroid
 * direction with the largest angle to it is within a few degrees of optimal on real coastline data,
 * and being slightly too generous only ever means drawing something that turns out to be off screen.
 */
data class SphericalCap(val center: Vec3, val angularRadius: Float)

fun boundingCap(points: List<Vec3>): SphericalCap {
    if (points.isEmpty()) return SphericalCap(Vec3(0f, 0f, 1f), Math.PI.toFloat())
    var sx = 0f
    var sy = 0f
    var sz = 0f
    for (p in points) {
        sx += p.x; sy += p.y; sz += p.z
    }
    val length = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
    // Points spread evenly enough that their mean cancels out have no meaningful centre; a cap over
    // the whole sphere is the honest answer and simply never culls.
    if (length < 1e-4f) return SphericalCap(Vec3(0f, 0f, 1f), Math.PI.toFloat())
    val center = Vec3(sx / length, sy / length, sz / length)
    var minDot = 1f
    for (p in points) {
        val d = center.x * p.x + center.y * p.y + center.z * p.z
        if (d < minDot) minDot = d
    }
    return SphericalCap(center, kotlin.math.acos(minDot.coerceIn(-1f, 1f)))
}

data class GlobeRing(
    val points: List<Vec3>,
    val isClosed: Boolean
) {
    /** Lazy so generated rings (the graticule) pay for it only if something actually culls them. */
    val cap: SphericalCap by lazy { boundingCap(points) }
}

/** A land polygon: rings[0] is the outer boundary, any further rings are holes (e.g. the Caspian Sea). */
data class GlobePolygon(
    val rings: List<List<Vec3>>
) {
    /**
     * One cap per ring rather than one for the whole polygon: a MultiPolygon feature's disjoint
     * parts are flattened into this rings list, so a single cap over all of them can span half the
     * globe and never cull anything.
     */
    val ringCaps: List<SphericalCap> by lazy { rings.map { boundingCap(it) } }
}

/** A traced cloud-mass boundary (marching squares over the density grid) with its fill alpha. */
data class CloudContour(
    val points: List<Vec3>,
    val alpha: Float
) {
    val cap: SphericalCap by lazy { boundingCap(points) }
}

// A country's name-label placement: [anchor] comes directly from Natural Earth's own curated
// LABEL_X/LABEL_Y point (more reliable than an averaged polygon centroid, which can land
// off-landmass for concave/crescent-shaped countries). [extentDeg] is the country's largest
// disjoint part's lat/lon span, used only to decide whether the label is big enough on screen to
// be worth drawing - see Satellite3DView's label LOD check. Deliberately does not carry the
// underlying polygon geometry at all (borders are rendered from a separate boundary-line layer).
data class CountryLabel(
    val name: String,
    val anchor: Vec3,
    val extentDeg: Float
)

// A populated place rendered as a light on the globe's night side. [magnitude] is the city's
// population mapped onto a 0..1 log scale rather than kept as a raw count: populations across this
// layer span roughly 800 to 30 million, so anything linear leaves all but a handful of megacities
// invisibly dim. Drives both dot size and brightness, and doubles as the low-zoom declutter key.
data class CityLight(
    val position: Vec3,
    val magnitude: Float
)
