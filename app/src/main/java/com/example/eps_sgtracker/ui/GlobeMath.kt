package com.example.eps_sgtracker.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import com.example.eps_sgtracker.model.GlobePolygon
import com.example.eps_sgtracker.model.GlobeRing
import com.example.eps_sgtracker.model.SphericalCap
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TWO_PI = (Math.PI * 2).toFloat()
private const val PI_F = Math.PI.toFloat()

data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quaternion {
            val half = angleRad / 2f
            val s = sin(half)
            return Quaternion(cos(half), axis.x * s, axis.y * s, axis.z * s)
        }
    }

    operator fun times(o: Quaternion) = Quaternion(
        w = w * o.w - x * o.x - y * o.y - z * o.z,
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w
    )

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-9f) IDENTITY else Quaternion(w / n, x / n, y / n, z / n)
    }

    fun rotate(v: Vec3): Vec3 {
        val qv = Vec3(x, y, z)
        val uv = cross(qv, v)
        val uuv = cross(qv, uv)
        return Vec3(
            x = v.x + 2f * (w * uv.x + uuv.x),
            y = v.y + 2f * (w * uv.y + uuv.y),
            z = v.z + 2f * (w * uv.z + uuv.z)
        )
    }

    /** Inverse rotation. Conjugate equals inverse for a normalized (unit) quaternion. */
    fun conjugate() = Quaternion(w, -x, -y, -z)
}

private fun cross(a: Vec3, b: Vec3) = Vec3(
    x = a.y * b.z - a.z * b.y,
    y = a.z * b.x - a.x * b.z,
    z = a.x * b.y - a.y * b.x
)

private fun normalize(v: Vec3): Vec3 {
    val n = sqrt(v.x * v.x + v.y * v.y + v.z * v.z).coerceAtLeast(1e-6f)
    return Vec3(v.x / n, v.y / n, v.z / n)
}

fun dot(a: Vec3, b: Vec3): Float = a.x * b.x + a.y * b.y + a.z * b.z

/** Spherical linear interpolation between two orientations, for a smooth animated reset. */
fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
    var bw = b.w
    var bx = b.x
    var by = b.y
    var bz = b.z
    var cosHalfTheta = a.w * bw + a.x * bx + a.y * by + a.z * bz
    if (cosHalfTheta < 0f) {
        bw = -bw; bx = -bx; by = -by; bz = -bz
        cosHalfTheta = -cosHalfTheta
    }
    if (cosHalfTheta > 0.9995f) {
        return Quaternion(
            a.w + t * (bw - a.w),
            a.x + t * (bx - a.x),
            a.y + t * (by - a.y),
            a.z + t * (bz - a.z)
        ).normalized()
    }
    val halfTheta = acos(cosHalfTheta.coerceIn(-1f, 1f))
    val sinHalfTheta = sqrt(1f - cosHalfTheta * cosHalfTheta)
    val ratioA = sin((1f - t) * halfTheta) / sinHalfTheta
    val ratioB = sin(t * halfTheta) / sinHalfTheta
    return Quaternion(
        a.w * ratioA + bw * ratioB,
        a.x * ratioA + bx * ratioB,
        a.y * ratioA + by * ratioB,
        a.z * ratioA + bz * ratioB
    )
}

fun applyDragRotation(
    current: Quaternion,
    dxPx: Float,
    dyPx: Float,
    // Reduced from the original 0.008f - the 1:1 pixel mapping felt too twitchy relative to
    // actual finger movement.
    sensitivity: Float = 0.006f
): Quaternion {
    // Positive drag (finger moving right/down) should carry the surface under the finger along
    // with it, same "grab and drag" convention as a map - not spin the globe the other way.
    val yaw = Quaternion.fromAxisAngle(Vec3(0f, 1f, 0f), dxPx * sensitivity)
    val pitch = Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f), dyPx * sensitivity)
    return (pitch * yaw * current).normalized()
}

/**
 * Applies a two-finger twist delta as a pure roll around the fixed view-space z axis (screen-
 * normal) - the exact same axis and outer-multiplied composition as [northUpOrientation]'s
 * correction, so the twist gesture and the North-up button drive one and the same degree of
 * freedom (twist to any roll angle, tap North-up to snap it back). [screenAngleDeltaRad] is the
 * finger-pair angle change measured in raw screen coordinates (y pointing down); screen y is
 * inverted relative to world y (see [projectRotated]), so the delta is negated here to make the
 * globe follow the fingers' visual rotation direction rather than mirroring it.
 */
fun applyTwistRotation(current: Quaternion, screenAngleDeltaRad: Float): Quaternion =
    (Quaternion.fromAxisAngle(Vec3(0f, 0f, 1f), -screenAngleDeltaRad) * current).normalized()

/**
 * The orientation that's a pure roll away from [current] - same globe facing, same zoom/pan
 * unaffected (those aren't part of orientation at all), just corrected so the world's north pole
 * projects straight up on screen. Rolls around the fixed view-space z axis (screen-perpendicular,
 * i.e. the axis pointing at the camera), which composes as the *outer* transform here - same
 * pattern as [applyDragRotation]'s fixed-axis deltas - so it only ever adjusts on-screen roll,
 * never which part of the globe is centered.
 */
fun northUpOrientation(current: Quaternion): Quaternion {
    val north = current.rotate(Vec3(0f, 1f, 0f))
    val screenDist = sqrt(north.x * north.x + north.y * north.y)
    // Looking straight down the pole axis - "up" is undefined, so leave the roll alone rather
    // than snapping to an arbitrary angle.
    if (screenDist < 1e-4f) return current
    val currentAngle = atan2(north.y, north.x)
    val correction = Quaternion.fromAxisAngle(Vec3(0f, 0f, 1f), PI_F / 2f - currentAngle)
    return (correction * current).normalized()
}

data class ProjectedPoint(val screen: Offset, val viewZ: Float)

/** Orthographic screen-space projection of a point already rotated into view space. */
fun projectRotated(r: Vec3, zoom: Float, panOffset: Offset, center: Offset, baseRadiusPx: Float): Offset =
    Offset(
        x = center.x + panOffset.x + r.x * baseRadiusPx * zoom,
        y = center.y + panOffset.y - r.y * baseRadiusPx * zoom
    )

fun project(
    v: Vec3,
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    center: Offset,
    baseRadiusPx: Float
): ProjectedPoint {
    val r = orientation.rotate(v)
    return ProjectedPoint(
        screen = projectRotated(r, zoom, panOffset, center, baseRadiusPx),
        viewZ = r.z
    )
}

/**
 * True if a rotated (view-space) point is hidden behind the opaque globe from the camera. Used
 * uniformly for satellite/station markers and pass-line clipping so they always agree with each
 * other about what's visible.
 *
 * This is a flat z<0 half-space test *restricted to the globe's silhouette* (x^2+y^2<1), not a
 * true curved-sphere occlusion test - a point can dip slightly below z=0 right at the limb and
 * still read as "visible" here. That's an accepted simplification for small marker dots; see
 * [visibleSegmentRuns] for why it matters more for line clipping.
 */
fun isObscuredByGlobe(rotated: Vec3): Boolean =
    rotated.z < 0f && (rotated.x * rotated.x + rotated.y * rotated.y) < 1f

/**
 * Approximates the visible portion(s) of a straight line segment between two points already
 * rotated into view space (e.g. a ground-station-to-satellite connector) by densely sampling it
 * and testing each sample with [isObscuredByGlobe] - the same test used for the endpoint
 * markers, so a line and the markers it connects never disagree about what's visible. Returns
 * each visible run as a list of consecutive view-space points, ready to project and draw as a
 * polyline; a segment can in principle have more than one visible run.
 *
 * A simple flat z=0 half-space clip (as used for rings lying exactly on the unit sphere) is
 * *not* correct here: one endpoint is elevated above the surface (radius > 1 - a satellite,
 * especially a GEO one at radius ~6.6), and for such a point z alone doesn't determine occlusion.
 * A point can have z < 0 yet sit far outside the globe's silhouette entirely (not obscured at
 * all) if it's elevated and off to the side - clipping purely on z<0 would incorrectly cut the
 * line, or drop it completely, exactly when a satellite is clearly visible but happens to be
 * behind the z=0 plane.
 *
 * Each run's cut end is then bisection-refined onto the exact visibility boundary rather than
 * left at the nearest sample. Uniform sampling alone leaves a gap up to 1/[samples] of the whole
 * 3D segment before the true crossing - for a GEO line (station at r=1 out to a satellite at
 * r~6.6) that segment is long, so most samples land far out in space and very few fall near the
 * globe surface where the crossing actually happens, making the untreated gap large and visible
 * (the line stops well short of Earth's rendered edge). Bisecting between the two samples that
 * straddle the flip converges on the true boundary regardless of how coarse the initial sampling
 * interval was.
 */
fun visibleSegmentRuns(aRotated: Vec3, bRotated: Vec3, samples: Int = 24): List<List<Vec3>> {
    fun pointAt(t: Float) = Vec3(
        aRotated.x + (bRotated.x - aRotated.x) * t,
        aRotated.y + (bRotated.y - aRotated.y) * t,
        aRotated.z + (bRotated.z - aRotated.z) * t
    )

    fun crossingPoint(tVisible: Float, tObscured: Float): Vec3 {
        var lo = tVisible
        var hi = tObscured
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (isObscuredByGlobe(pointAt(mid))) hi = mid else lo = mid
        }
        return pointAt(lo)
    }

    val runs = ArrayList<ArrayList<Vec3>>()
    var current: ArrayList<Vec3>? = null
    var prevT = 0f
    var prevObscured = isObscuredByGlobe(pointAt(0f))
    if (!prevObscured) {
        current = ArrayList<Vec3>().also { it.add(pointAt(0f)); runs.add(it) }
    }

    for (i in 1..samples) {
        val t = i / samples.toFloat()
        val obscured = isObscuredByGlobe(pointAt(t))
        if (obscured != prevObscured) {
            val boundary = if (prevObscured) crossingPoint(t, prevT) else crossingPoint(prevT, t)
            if (prevObscured) {
                current = ArrayList<Vec3>().also { it.add(boundary); runs.add(it) }
            } else {
                current?.add(boundary)
                current = null
            }
        }
        if (!obscured) {
            val run = current ?: ArrayList<Vec3>().also { current = it; runs.add(it) }
            run.add(pointAt(t))
        }
        prevT = t
        prevObscured = obscured
    }
    return runs.filter { it.size >= 2 }
}

/**
 * A 30deg-by-default lat/lon grid, generated once and reused across frames.
 * Parallels (fixed latitude) are closed rings; meridians (fixed longitude) are open half-circles.
 */
fun buildGraticuleRings(
    latStepDeg: Int = 30,
    lonStepDeg: Int = 30,
    segments: Int = 48
): List<GlobeRing> {
    val rings = ArrayList<GlobeRing>()

    var lat = -60
    while (lat <= 60) {
        val points = (0..segments).map { i ->
            val lonDeg = -180.0 + 360.0 * i / segments
            latLonDegToUnitSphere(lat.toDouble(), lonDeg)
        }
        rings.add(GlobeRing(points = points, isClosed = true))
        lat += latStepDeg
    }

    var lon = -180
    while (lon < 180) {
        val points = (0..segments).map { i ->
            val latDeg = -90.0 + 180.0 * i / segments
            latLonDegToUnitSphere(latDeg, lon.toDouble())
        }
        rings.add(GlobeRing(points = points, isClosed = false))
        lon += lonStepDeg
    }

    return rings
}

/**
 * Reusable working memory for one frame of globe drawing.
 *
 * Every vector layer - land, glaciers, lakes, rivers, borders, graticule, clouds, the terminator -
 * goes through [clipVisibleSubpaths], and it used to allocate an `Array<Vec3>` per ring on top of
 * the four `Vec3`s [Quaternion.rotate] allocates per call. Across the vector world's ~11,000 points
 * that is around 45,000 objects per frame at 30fps, which on a real device is enough garbage to keep
 * the young generation collecting more or less continuously. The buffer below is what that turned
 * into: the rotation is expanded to a 3x3 once per ring and written as plain floats.
 *
 * The [Path] pool is the same story for the native side. A fresh path per subpath meant several
 * hundred native objects created and thrown away every frame; reusing them also gives Skia a stable
 * identity to cache tessellation against for as long as the geometry does not change.
 *
 * Owned by the caller and remembered across frames, like [EarthMeshBuffers], rather than being
 * file-level state. NOT reentrant, and it does not need to be: the builders fill it and consume it
 * before returning, and every caller walks its rings strictly one at a time.
 */
class GlobeProjectionScratch {
    // Sized to the largest ring seen so far. Natural Earth's biggest coastline ring is a few
    // thousand points, so this settles within the first frame and never grows again.
    private var rotated = FloatArray(3 * 1024)
    private val paths = ArrayList<Path>()
    private var nextPath = 0

    /** View-space x/y/z of the ring being clipped, interleaved. Valid until the next ring. */
    fun rotatedBuffer(pointCount: Int): FloatArray {
        val needed = pointCount * 3
        if (rotated.size < needed) rotated = FloatArray(needed)
        return rotated
    }

    /**
     * A cleared path from the pool, valid until the next [beginFrame].
     *
     * Handing the same objects out in the same order every frame is deliberate - see above. Drawing
     * one does not copy it, but Skia's paths are copy-on-write, so clearing it here after it has
     * been recorded into a display list leaves that recording intact (the same guarantee the
     * terminator's reused band paths already rely on).
     *
     * The fill type is reset along with the contents: it is not part of what [Path.rewind] clears,
     * so a path that took the even-odd branch once would otherwise keep it forever.
     */
    fun obtainPath(): Path {
        if (nextPath == paths.size) paths.add(Path())
        return paths[nextPath++].apply {
            rewind()
            fillType = PathFillType.NonZero
        }
    }

    /** Releases every path handed out so far. Call once at the top of each frame's draw. */
    fun beginFrame() {
        nextPath = 0
    }
}

/**
 * Whether anything inside this cap could reach the viewport, from one rotation and a rectangle test.
 *
 * Two rejections in one, and the first matters as much as the second: roughly half the world faces
 * away from the camera at any moment, and every one of those rings was still being rotated point by
 * point and clipped before anything noticed. The viewport test is what pays at high zoom, where most
 * of the visible hemisphere is scrolled off screen as well.
 *
 * Conservative in the only direction that is safe - a cap that is kept but turns out to be off
 * screen merely costs what it used to.
 */
fun SphericalCap.mayBeVisible(
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    screenCenter: Offset,
    baseRadiusPx: Float,
    viewport: Size
): Boolean {
    val rotated = orientation.rotate(center)
    val sinRadius = sin(angularRadius)
    // Entirely on the far hemisphere: even the near edge of the cap has turned away. A cap of more
    // than a quarter turn always reaches the near side, so it skips the test rather than tripping
    // over sin() coming back down again.
    if (angularRadius < PI_F / 2f && rotated.z < -sinRadius) return false

    // Everything below bounds where the ring's own POINTS can land, and that is only the whole story
    // while the ring stays clear of the horizon. One that crosses it is closed by an arc traced along
    // the silhouette, which can run well past any of its points, so a cap that reaches the horizon at
    // all keeps whatever the hemisphere test left it.
    if (rotated.z <= sinRadius) return true

    val scale = baseRadiusPx * zoom
    val x = screenCenter.x + panOffset.x + rotated.x * scale
    val y = screenCenter.y + panOffset.y - rotated.y * scale
    // Orthographic projection drops a coordinate, so it can only shrink distances - which makes the
    // cap's chord an exact bound on how far from its centre its points can land on screen.
    val reach = 2f * sin(angularRadius / 2f) * scale
    return x + reach >= 0f && x - reach <= viewport.width &&
        y + reach >= 0f && y - reach <= viewport.height
}

/**
 * Splits a ring into one or more screen-space [Path]s containing only the front-facing
 * (camera-visible) portion, cutting each crossing of the visibility horizon (rotated z = 0)
 * at an interpolated boundary point instead of drawing the far-side points directly.
 *
 * A whole-ring average-z cull isn't enough here: large landmasses (e.g. Eurasia) commonly
 * straddle the horizon, so naively drawing every point in one Path mixes front- and mirrored
 * back-side segments into a single self-overlapping shape, whose opposite winding directions
 * cancel out under the nonzero fill rule.
 *
 * Each visible run is closed by tracing along the horizon *circle* (the shorter way) between
 * its two crossing points, not a straight chord: jagged, repeatedly-crossing coastlines (the
 * Arctic archipelago is the worst offender) can have a single visible run whose two horizon
 * crossings are far apart on the rim, and a straight chord between them cuts across the
 * globe's interior instead of hugging the edge, producing large wedge-shaped artifacts.
 */
fun buildVisibleSubpaths(
    points: List<Vec3>,
    treatAsClosed: Boolean,
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    center: Offset,
    baseRadiusPx: Float,
    scratch: GlobeProjectionScratch
): List<Path> {
    val paths = ArrayList<Path>()
    var current: Path? = null
    clipVisibleSubpaths(
        points, treatAsClosed, orientation, zoom, panOffset, center, baseRadiusPx, scratch,
        onMoveTo = { x, y -> current = scratch.obtainPath().apply { moveTo(x, y) } },
        onLineTo = { x, y -> current?.lineTo(x, y) },
        onEndSubpath = {
            current?.let {
                if (treatAsClosed) it.close()
                paths.add(it)
            }
            current = null
        }
    )
    return paths
}

/**
 * The horizon clip itself, streaming each clipped subpath's screen-space vertices out through
 * [onMoveTo]/[onLineTo]/[onEndSubpath] rather than returning them in a collection.
 *
 * The streaming shape is a deliberate allocation choice, not a style preference. This runs for
 * every map layer (land, glaciers, lakes, rivers, borders, graticule, terminator) on every one of
 * the view's ~30 frames per second, and a materialized `List<Offset>` per ring boxes one object per
 * point per frame - [Offset] is a value class, so it only stays unboxed while it's a local. That
 * churn alone was enough to drive near-continuous young-gen GCs and visible stutter. Being
 * `inline`, the three callbacks cost nothing and callers can consume the vertices however they
 * like: [buildVisibleSubpaths] writes them straight into a [Path], while [buildShadowPath]
 * simultaneously accumulates a winding number from the same stream without storing anything.
 */
private inline fun clipVisibleSubpaths(
    points: List<Vec3>,
    treatAsClosed: Boolean,
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    center: Offset,
    baseRadiusPx: Float,
    scratch: GlobeProjectionScratch,
    onMoveTo: (Float, Float) -> Unit,
    onLineTo: (Float, Float) -> Unit,
    onEndSubpath: () -> Unit
) {
    val n = points.size
    if (n < 2) return

    // The orientation expanded to a 3x3 once per ring, so rotating a point is nine multiply-adds
    // into plain floats. Quaternion.rotate is the same arithmetic but allocates four Vec3s doing it,
    // and this runs over every point of every layer on every frame - see GlobeProjectionScratch.
    val qw = orientation.w
    val qx = orientation.x
    val qy = orientation.y
    val qz = orientation.z
    val m00 = 1f - 2f * (qy * qy + qz * qz)
    val m01 = 2f * (qx * qy - qw * qz)
    val m02 = 2f * (qx * qz + qw * qy)
    val m10 = 2f * (qx * qy + qw * qz)
    val m11 = 1f - 2f * (qx * qx + qz * qz)
    val m12 = 2f * (qy * qz - qw * qx)
    val m20 = 2f * (qx * qz - qw * qy)
    val m21 = 2f * (qy * qz + qw * qx)
    val m22 = 1f - 2f * (qx * qx + qy * qy)

    // Visibility is accumulated in the same pass rather than in a BooleanArray and two more
    // traversals over it.
    val rotated = scratch.rotatedBuffer(n)
    var anyVisible = false
    var allVisible = true
    for (i in 0 until n) {
        val p = points[i]
        val px = p.x
        val py = p.y
        val pz = p.z
        val slot = i * 3
        rotated[slot] = m00 * px + m01 * py + m02 * pz
        rotated[slot + 1] = m10 * px + m11 * py + m12 * pz
        val z = m20 * px + m21 * py + m22 * pz
        rotated[slot + 2] = z
        if (z > 0f) anyVisible = true else allVisible = false
    }

    // Projection folded into two constants up front - the per-point math below is then just a
    // multiply-add. (Kotlin forbids local functions inside an inline function, so these can't be
    // an sx()/sy() pair anyway; horizonCrossing is a top-level helper for the same reason.)
    val scale = baseRadiusPx * zoom
    val originX = center.x + panOffset.x
    val originY = center.y + panOffset.y

    if (allVisible) {
        onMoveTo(originX + rotated[0] * scale, originY - rotated[1] * scale)
        for (i in 1 until n) {
            onLineTo(originX + rotated[i * 3] * scale, originY - rotated[i * 3 + 1] * scale)
        }
        onEndSubpath()
        return
    }
    if (!anyVisible) return

    if (!treatAsClosed) {
        // Open chain (graticule meridian): never filled, so a straight chord at each crossing
        // is fine - meridians are simple monotonic-latitude lines with no complex crossing runs.
        var open = false
        var hasPrev = false
        var prevX = 0f
        var prevY = 0f
        var prevZ = 0f
        var crossX = 0f
        var crossY = 0f
        for (i in 0 until n) {
            val slot = i * 3
            val x = rotated[slot]
            val y = rotated[slot + 1]
            val z = rotated[slot + 2]
            val nowVisible = z > 0f
            if (!hasPrev) {
                if (nowVisible) {
                    onMoveTo(originX + x * scale, originY - y * scale)
                    open = true
                }
            } else {
                val prevVisible = prevZ > 0f
                when {
                    prevVisible && nowVisible ->
                        if (open) onLineTo(originX + x * scale, originY - y * scale)
                    prevVisible && !nowVisible -> {
                        if (open) {
                            horizonCrossing(prevX, prevY, prevZ, x, y, z) { cx, cy ->
                                crossX = cx
                                crossY = cy
                            }
                            onLineTo(originX + crossX * scale, originY - crossY * scale)
                            onEndSubpath()
                            open = false
                        }
                    }
                    !prevVisible && nowVisible -> {
                        horizonCrossing(prevX, prevY, prevZ, x, y, z) { cx, cy ->
                            crossX = cx
                            crossY = cy
                        }
                        onMoveTo(originX + crossX * scale, originY - crossY * scale)
                        onLineTo(originX + x * scale, originY - y * scale)
                        open = true
                    }
                    else -> Unit
                }
            }
            prevX = x
            prevY = y
            prevZ = z
            hasPrev = true
        }
        if (open) onEndSubpath()
        return
    }

    // Closed ring with at least one crossing: start the scan at a known-invisible point so
    // every visible run is cleanly bounded by two real horizon crossings (an entry and an
    // exit), then close each run by rim-tracing from its exit back to its own entry.
    var startIdx = 0
    for (i in 0 until n) {
        if (rotated[i * 3 + 2] <= 0f) {
            startIdx = i
            break
        }
    }
    var open = false
    var hasEntry = false
    var entryX = 0f
    var entryY = 0f
    var exitX = 0f
    var exitY = 0f
    var prevX = rotated[startIdx * 3]
    var prevY = rotated[startIdx * 3 + 1]
    var prevZ = rotated[startIdx * 3 + 2]
    for (offset in 1..n) {
        val slot = ((startIdx + offset) % n) * 3
        val x = rotated[slot]
        val y = rotated[slot + 1]
        val z = rotated[slot + 2]
        val prevVisible = prevZ > 0f
        val nowVisible = z > 0f
        when {
            prevVisible && nowVisible ->
                if (open) onLineTo(originX + x * scale, originY - y * scale)
            prevVisible && !nowVisible -> {
                horizonCrossing(prevX, prevY, prevZ, x, y, z) { cx, cy ->
                    exitX = cx
                    exitY = cy
                }
                if (open) onLineTo(originX + exitX * scale, originY - exitY * scale)
                if (open && hasEntry) {
                    val angleFrom = atan2(exitY, exitX)
                    var delta = atan2(entryY, entryX) - angleFrom
                    while (delta > PI_F) delta -= TWO_PI
                    while (delta < -PI_F) delta += TWO_PI
                    val steps = (abs(delta) / (PI_F / 36f)).toInt().coerceAtLeast(1) // ~5 degree steps
                    for (i in 1..steps) {
                        val a = angleFrom + delta * i / steps
                        onLineTo(originX + cos(a) * scale, originY - sin(a) * scale)
                    }
                    onEndSubpath()
                }
                open = false
                hasEntry = false
            }
            !prevVisible && nowVisible -> {
                horizonCrossing(prevX, prevY, prevZ, x, y, z) { cx, cy ->
                    entryX = cx
                    entryY = cy
                }
                hasEntry = true
                onMoveTo(originX + entryX * scale, originY - entryY * scale)
                onLineTo(originX + x * scale, originY - y * scale)
                open = true
            }
            else -> Unit
        }
        prevX = x
        prevY = y
        prevZ = z
    }
}

/**
 * Linear interpolation of a segment's horizon (rotated z = 0) crossing, re-normalized onto the
 * unit horizon circle so rim-tracing - which walks that exact circle - starts from a point
 * actually on it.
 *
 * Reports its result through a callback rather than returning a [Vec3]: it is called from the
 * per-point clip loop, and being inline this hands back two floats with nothing allocated. z is not
 * passed on because it is zero by construction.
 */
private inline fun horizonCrossing(
    ax: Float, ay: Float, az: Float,
    bx: Float, by: Float, bz: Float,
    onCrossing: (Float, Float) -> Unit
) {
    val t = az / (az - bz)
    val x = ax + t * (bx - ax)
    val y = ay + t * (by - ay)
    val len = sqrt(x * x + y * y).coerceAtLeast(1e-6f)
    onCrossing(x / len, y / len)
}

/**
 * Builds a single horizon-clipped, screen-space [Path] for a land polygon, combining its outer
 * boundary and any hole rings (e.g. the Caspian Sea) with [PathFillType.EvenOdd] so holes are
 * correctly subtracted regardless of how the horizon clip splits either ring into fragments.
 * Returns null if the whole polygon is on the far side of the globe.
 */
fun buildVisiblePolygonPath(
    polygon: GlobePolygon,
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    center: Offset,
    baseRadiusPx: Float,
    viewport: Size,
    scratch: GlobeProjectionScratch
): Path? {
    // Each ring's clipped contours are written STRAIGHT into the combined path. Building them as
    // separate paths first and adding them afterwards produced the identical shape by way of one
    // throwaway native path per ring, on top of the one being kept.
    var combined: Path? = null
    for (index in polygon.rings.indices) {
        // Per ring, not per polygon: one feature's islands can be scattered across a hemisphere.
        if (!polygon.ringCaps[index].mayBeVisible(
                orientation, zoom, panOffset, center, baseRadiusPx, viewport
            )
        ) continue
        val ring = polygon.rings[index]
        clipVisibleSubpaths(
            ring, true, orientation, zoom, panOffset, center, baseRadiusPx, scratch,
            // Taken from the pool only once something is actually visible, so a polygon on the far
            // side of the globe - roughly half of them at any moment - costs nothing at all.
            onMoveTo = { x, y ->
                val path = combined ?: scratch.obtainPath().also { combined = it }
                path.moveTo(x, y)
            },
            onLineTo = { x, y -> combined?.lineTo(x, y) },
            onEndSubpath = { combined?.close() }
        )
    }
    return combined?.apply { fillType = PathFillType.EvenOdd }
}

/**
 * A circle of constant solar elevation on the globe, plus the threshold needed to test which side
 * of it a point falls on. [cosPolar] is the cosine of the ring's angular radius measured from the
 * sun direction: a surface point is on the sunlit side of this ring exactly when
 * `dot(point, sunDir) > cosPolar`.
 */
data class ShadowRing(val points: List<Vec3>, val cosPolar: Float)

/**
 * The circle of points that see the sun at [elevationDeg] above their horizon, in Earth-fixed
 * space, ready for the same [buildVisibleSubpaths] horizon clip as any coastline or graticule ring.
 *
 * Since a surface point's normal *is* its position on the unit sphere, seeing the sun at elevation
 * `e` means sitting at polar angle `90 - e` from the sun direction. Elevation 0 therefore gives the
 * terminator itself as the great circle perpendicular to [sunDir], and negative elevations give the
 * progressively smaller circles bounding civil (-6), nautical (-12) and astronomical (-18)
 * twilight. One generator covers all of them rather than the terminator being a separate case.
 *
 * Depends only on [sunDir] and [elevationDeg], and the sun moves slowly enough to be recomputed
 * about once a minute, so callers should remember these rather than rebuild them per frame.
 *
 * Built in Earth-fixed space on purpose. An earlier version built the ring directly in view space
 * (post-rotation) and projected it to a 2D ellipse; that collapses to a near-zero-area sliver
 * whenever the terminator is viewed edge-on (very common), and intersecting/subtracting a
 * degenerate sliver against the globe disc darkens almost everything instead of splitting it in
 * half. Routing through the real 3D-to-clipped-2D pipeline avoids that collapse entirely.
 */
fun sunElevationRing(sunDir: Vec3, elevationDeg: Float, segments: Int = 64): ShadowRing {
    val polar = Math.toRadians(90.0 - elevationDeg).toFloat()
    val cosPolar = cos(polar)
    val sinPolar = sin(polar)

    val reference = if (abs(sunDir.z) < 0.99f) Vec3(0f, 0f, 1f) else Vec3(1f, 0f, 0f)
    val u = normalize(cross(sunDir, reference))
    val v = cross(sunDir, u)

    val points = (0..segments).map { i ->
        val angle = TWO_PI * i / segments
        val c = cos(angle)
        val s = sin(angle)
        // Tilt the great circle towards the anti-sun point by the polar angle. At elevation 0 the
        // cosPolar term vanishes and this is exactly the perpendicular great circle.
        Vec3(
            cosPolar * sunDir.x + sinPolar * (c * u.x + s * v.x),
            cosPolar * sunDir.y + sinPolar * (c * u.y + s * v.y),
            cosPolar * sunDir.z + sinPolar * (c * u.z + s * v.z)
        )
    }
    return ShadowRing(points = points, cosPolar = cosPolar)
}

/** How a shadow band should be painted - see [buildShadowBandFill]. */
enum class ShadowFill {
    /** Nothing falls in this band; draw nothing. */
    NONE,
    /** The band covers the whole globe disc; fill it directly. */
    WHOLE_DISC,
    /** Fill the written path as-is; its fill type is already set correctly. */
    PATH
}

/**
 * How far past the globe's edge the shading is carried, in pixels.
 *
 * The bands are drawn WITHOUT antialiasing (see [buildShadowBandFill]), so wherever one ends at the
 * silhouette it lands on whole pixels, while the ocean disc underneath it was drawn antialiased.
 * Without this the outermost pixel of the night limb - half ocean, half space - is the one pixel the
 * shading rounds away from, and it reads as a bright ragged fringe along the dark edge of the globe.
 *
 * Applied to the whole shadow geometry rather than only the disc contour, so a band bounded by the
 * rim gets the same treatment as one bounded by the disc. That shifts the terminator itself half a
 * pixel outwards too, which is invisible and, more to the point, identical for every band - so they
 * still tile exactly. Callers painting the [ShadowFill.WHOLE_DISC] case directly must use the same
 * margin.
 */
const val SHADOW_BLEED_PX = 0.5f

/**
 * One ring's shaded region, expressed as what was written into the band path.
 *
 * A ring's shaded side is either the lobe it encloses or everything on the disc except that lobe,
 * and both cases have to compose with the next ring's - hence carrying the two facts separately
 * rather than four opaque outcomes.
 */
private enum class RingRegion(val addsDisc: Boolean, val hasContours: Boolean) {
    /** Entirely sunlit at this elevation. */
    EMPTY(false, false),
    /** Entirely below this elevation. */
    WHOLE_DISC(true, false),
    LOBE(false, true),
    DISC_MINUS_LOBE(true, true)
}

/**
 * Writes one twilight band - the region between [outer] and the next ring in, [inner] - into [into]
 * and reports how the caller should paint it. [inner] is null for the innermost band, which has
 * nothing below it.
 *
 * ## Why bands and not nested lobes
 *
 * Each ring encloses a smaller region than the one before, so stacking their fills from the horizon
 * inward produces the graded twilight directly - and that is how this worked. The cost is that deep
 * night is painted by every one of the [ShadowRing]s, all 22 of them, since they very nearly
 * coincide away from the terminator: the whole night side is blended over 22 times per frame. On a
 * globe zoomed in far enough to fill the screen that is 22 screenfuls of blending, which is the
 * single most expensive thing the vector renderer does.
 *
 * The bands tile instead of overlapping, so every pixel is painted exactly once, with the colour the
 * stack would have composited to there (the caller precomputes those). Same image, one pass.
 *
 * ## The even-odd algebra
 *
 * Each ring's shaded region is `lobe XOR (disc if it is the complement)`, and because the regions
 * are nested, the band between two of them is just their symmetric difference. So: append both
 * rings' contours, add the disc contour once if exactly one of the two wants it - two would cancel -
 * and let [PathFillType.EvenOdd] resolve it. No path operations, no clips, no extra allocation, and
 * the four lobe/complement combinations all fall out of the same three lines.
 *
 * ## Antialiasing
 *
 * Bands that share an edge must be drawn with it OFF. Two abutting antialiased fills both take
 * partial coverage of the shared pixel and composite over each other, which lands well short of full
 * coverage - a visibly lighter hairline along every one of the 21 joins. With it off the tiling is
 * exact: each pixel centre falls in exactly one band. The step between neighbours is about one
 * percent of alpha, far too small to read as an edge, and this is the same reasoning that turns
 * antialiasing off for the unwrap mesh's shared triangle edges.
 *
 * The paths are caller-owned and reused across frames, hence the `rewind()` - and note the fill type
 * has to be reset with it, or a path that took the even-odd branch once would keep it forever.
 */
fun buildShadowBandFill(
    outer: ShadowRing,
    inner: ShadowRing?,
    sunDir: Vec3,
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    center: Offset,
    baseRadiusPx: Float,
    globeCenter: Offset,
    globeRadius: Float,
    scratch: GlobeProjectionScratch,
    into: Path
): ShadowFill {
    into.rewind()
    into.fillType = PathFillType.NonZero

    // scale is baseRadiusPx * zoom, so this is exactly SHADOW_BLEED_PX of extra screen radius.
    val bleedRadius = baseRadiusPx + SHADOW_BLEED_PX / zoom

    val outerRegion = appendShadowRing(
        outer, sunDir, orientation, zoom, panOffset, center, bleedRadius, globeCenter, scratch, into
    )
    // Nothing is below the outer elevation, so nothing is between it and the next one in.
    if (outerRegion == RingRegion.EMPTY) return ShadowFill.NONE

    val innerRegion = if (inner == null) RingRegion.EMPTY else appendShadowRing(
        inner, sunDir, orientation, zoom, panOffset, center, bleedRadius, globeCenter, scratch, into
    )
    // Everything visible is already past the inner elevation, so the band between them is empty.
    if (innerRegion == RingRegion.WHOLE_DISC) return ShadowFill.NONE

    if (outerRegion == RingRegion.WHOLE_DISC && innerRegion == RingRegion.EMPTY) {
        return ShadowFill.WHOLE_DISC
    }

    val discDiffers = outerRegion.addsDisc != innerRegion.addsDisc
    if (discDiffers) {
        into.addOval(Rect(center = globeCenter, radius = globeRadius + SHADOW_BLEED_PX))
    }
    if (discDiffers || (outerRegion.hasContours && innerRegion.hasContours)) {
        into.fillType = PathFillType.EvenOdd
    }
    return ShadowFill.PATH
}

/** Appends one ring's clipped contours to [into] and classifies the region they describe. */
private fun appendShadowRing(
    ring: ShadowRing,
    sunDir: Vec3,
    orientation: Quaternion,
    zoom: Float,
    panOffset: Offset,
    center: Offset,
    baseRadiusPx: Float,
    globeCenter: Offset,
    scratch: GlobeProjectionScratch,
    into: Path
): RingRegion {
    val centerEarthFixed = orientation.conjugate().rotate(Vec3(0f, 0f, 1f))
    val centerIsDay = dot(centerEarthFixed, sunDir) > ring.cosPolar

    // Whether globeCenter falls inside the clipped terminator shape is accumulated as a winding
    // number straight off the vertex stream, instead of testing the finished Path through
    // android.graphics.Region: Region truncates to integer pixels, which could flip this decision
    // on a sub-pixel change whenever the terminator passed near globeCenter - a one-frame swap
    // between "thin sliver" and "almost the whole globe" that read as a flicker mid-drag. The
    // nonzero winding rule here matches the fill rule the path is actually drawn with.
    var subpathCount = 0
    var winding = 0
    var firstX = 0f
    var firstY = 0f
    var prevX = 0f
    var prevY = 0f
    clipVisibleSubpaths(
        ring.points, true, orientation, zoom, panOffset, center, baseRadiusPx, scratch,
        onMoveTo = { x, y ->
            into.moveTo(x, y)
            firstX = x; firstY = y; prevX = x; prevY = y
            subpathCount++
        },
        onLineTo = { x, y ->
            into.lineTo(x, y)
            winding += windingStep(prevX, prevY, x, y, globeCenter.x, globeCenter.y)
            prevX = x; prevY = y
        },
        onEndSubpath = {
            into.close()
            winding += windingStep(prevX, prevY, firstX, firstY, globeCenter.x, globeCenter.y)
        }
    )

    if (subpathCount == 0) {
        // Ring doesn't cross the visible disc at all - it's uniformly above or below this elevation.
        return if (centerIsDay) RingRegion.EMPTY else RingRegion.WHOLE_DISC
    }

    // When the lobe is the *lit* side, what this ring shades is everything else on the disc.
    val combinedIsDay = (winding != 0) == centerIsDay
    return if (combinedIsDay) RingRegion.DISC_MINUS_LOBE else RingRegion.LOBE
}

/** One edge's contribution to the nonzero winding number of (px, py) - see [appendShadowRing]. */
private fun windingStep(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Int {
    val cross = (bx - ax) * (py - ay) - (px - ax) * (by - ay)
    return when {
        ay <= py && by > py && cross > 0f -> 1
        ay > py && by <= py && cross < 0f -> -1
        else -> 0
    }
}
