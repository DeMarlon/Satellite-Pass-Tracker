package com.example.eps_sgtracker.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import com.example.eps_sgtracker.model.Vec3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Which way the view is currently drawing the world. */
enum class GlobeViewMode { GLOBE, MAP }

/**
 * The equirectangular counterpart to the globe's orthographic projection.
 *
 * Everything the view draws is a list of [Vec3] on the unit sphere, and the only thing that differs
 * between the two view modes is how one of those becomes a screen point - which is why both modes
 * share every layer, setting and gesture.
 *
 * ## Wrap-around, and why there is no seam split
 *
 * A first attempt cut every line where it crossed the antimeridian. That is the obvious reading of
 * the problem and it is wrong for filled shapes: a landmass split into two open runs cannot be
 * filled without inventing an edge down the middle, so Eurasia or the Americas would simply vanish
 * whenever the seam happened to cross them - which changes as the map is panned.
 *
 * Instead, longitudes are unwrapped **continuously along each ring**, so a shape is never cut, and
 * the whole world is then drawn several times at 360-degree intervals. Whichever copy the viewport
 * happens to overlap is the one you see. Nothing is ever split, fills always work, and horizontal
 * wrap-around falls out for free rather than needing its own handling.
 *
 * The one exception is a ring being carried through the globe-to-map unwrap, which is drawn in a
 * single copy and therefore does have to be cut - see the folding in [buildMapRingPaths]. It can
 * afford to be: only outlines are drawn while the surface is morphing, so nothing needs filling.
 */

/** Longitude difference wrapped into -180..180. */
fun wrapLonDelta(deltaDeg: Float): Float {
    var d = deltaDeg
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return d
}

private fun latOf(v: Vec3): Float {
    val len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    val y = if (len < 1e-6f) v.y else v.y / len
    return Math.toDegrees(asin(y.coerceIn(-1f, 1f)).toDouble()).toFloat()
}

private fun lonOf(v: Vec3): Float =
    Math.toDegrees(atan2(v.x.toDouble(), v.z.toDouble())).toFloat()

/**
 * How close to a pole a point has to be before its longitude stops meaning anything.
 *
 * At the pole itself x and z are both zero and `atan2(0, 0)` returns 0, so every pole vertex claims
 * to be at longitude 0. Natural Earth closes Antarctica by running a line of vertices along
 * latitude -90, and taken literally that collapses the entire southern edge onto one meridian - the
 * ring then doubles back through 180 degrees of longitude and fills as a torn, inverted mess.
 *
 * Treating such a vertex as continuing the previous longitude instead is what makes the path run
 * straight down to the pole and back up, which is the shape actually intended.
 */
private const val POLE_EPSILON = 1e-4f

// How much accumulated longitude marks a ring as encircling a pole rather than enclosing an area.
// A normal ring returns to where it started and nets out near zero; a polar one nets a full turn, so
// anything past three quarters of a turn is unambiguous.
private const val POLAR_RING_TURN_DEG = 270f

/**
 * The lat/lon currently facing the camera, which is what the map centres on.
 *
 * [Quaternion.rotate] takes a world point into view space, so its conjugate takes the view's forward
 * axis back out to the world - and that point is exactly what the user is looking at. Using it as the
 * map centre is what makes switching modes keep your place rather than jumping home.
 */
fun viewCenterLatLon(orientation: Quaternion): Pair<Float, Float> {
    val facing = orientation.conjugate().rotate(Vec3(0f, 0f, 1f))
    return latOf(facing) to lonOf(facing)
}

/**
 * The inverse of [viewCenterLatLon]: the orientation that brings [latDeg]/[lonDeg] to face the
 * camera, north up. Panning writes back through this so `orientation` stays the single source of
 * truth for where the view points, in both modes.
 *
 * Derivation, since the axis order is easy to get backwards: rotating about +Y by `-lon` brings the
 * point into the YZ plane, after which rotating about +X by `lat` lands it on +Z. Quaternion
 * multiplication applies its right operand first, hence `qLat * qLon`.
 */
fun orientationFacing(latDeg: Float, lonDeg: Float): Quaternion {
    val qLon = Quaternion.fromAxisAngle(Vec3(0f, 1f, 0f), -Math.toRadians(lonDeg.toDouble()).toFloat())
    val qLat = Quaternion.fromAxisAngle(Vec3(1f, 0f, 0f), Math.toRadians(latDeg.toDouble()).toFloat())
    return (qLat * qLon).normalized()
}

/**
 * Where the full-world rectangle sits on screen. [width] spans 360 degrees of longitude and [height]
 * 180 of latitude, so the aspect is always 2:1 and lat/lon maps into it linearly.
 *
 * That linearity is load-bearing: it means the equirectangular source imagery can be drawn into this
 * rectangle directly, with no resampling and no shader.
 */
data class MapLayout(
    val centerLat: Float,
    val centerLon: Float,
    val screenCenter: Offset,
    val width: Float,
    val height: Float,
    val viewportWidth: Float
) {
    /**
     * Top-left of the world rectangle itself - where longitude -180 and latitude +90 actually land.
     *
     * NOT the same as the centre of the viewport minus half the size, which is only correct when the
     * map happens to be centred on 0N 0E. Anything drawn as a rectangle rather than point-by-point -
     * the satellite imagery above all - has to be positioned from here, or it stays pinned to the
     * screen while every projected layer scrolls past it.
     */
    val worldLeft: Float get() = screenCenter.x - (centerLon + 180f) / 360f * width
    val worldTop: Float get() = screenCenter.y - (90f - centerLat) / 180f * height

    /**
     * Horizontal offsets at which the world is repeated so it wraps seamlessly.
     *
     * Enough copies to cover the viewport whatever the zoom - at MIN_ZOOM the whole world is narrower
     * than the screen and several are visible at once, while zoomed in it is nearly always just the
     * one plus a neighbour at the edge.
     */
    fun copyOffsets(): List<Float> {
        if (width <= 0f) return listOf(0f)
        // Symmetric around zero, and deliberately generous. An attempt to derive this from where the
        // world rectangle sits was wrong: most content here is anchored to the map CENTRE, not to
        // that rectangle's left edge, and can legitimately extend from -0.5 to +1.5 widths away - a
        // ring that wraps the globe, or a terminator arc whose solved longitude range runs past 360.
        // Deriving the range from the rectangle dropped exactly the copy that carried such content
        // into view, which is why Antarctica and parts of the night side vanished at some centre
        // longitudes and not others.
        //
        // A caller that knows its content's real extent is free to narrow this itself, rather than
        // tightening it here for everyone.
        val reach = ceil(viewportWidth / width).toInt() + 1
        return (-reach..reach).map { it * width }
    }
}

fun mapLayoutFor(
    orientation: Quaternion,
    zoom: Float,
    center: Offset,
    viewportWidth: Float,
    viewportHeight: Float
): MapLayout {
    val (rawLat, lon) = viewCenterLatLon(orientation)
    val width = viewportWidth * zoom
    val height = width / 2f

    // Latitude clamps so the poles cannot be dragged inside the viewport leaving empty bands - but
    // only once the map is tall enough for that to be possible. Shorter than the viewport, it simply
    // centres, because there is nowhere to scroll to. Longitude deliberately does not clamp; it wraps.
    val halfVisibleLat = if (height <= 0f) 0f else viewportHeight / height * 180f / 2f
    val latLimit = (90f - halfVisibleLat).coerceAtLeast(0f)

    return MapLayout(rawLat.coerceIn(-latLimit, latLimit), lon, center, width, height, viewportWidth)
}

/** Screen position for a single point. Altitude is normalised away, which is exactly why a
 *  satellite's orbital samples render as its GROUND track here with no separate sampling. */
fun MapLayout.project(v: Vec3): Offset = Offset(
    x = screenCenter.x + wrapLonDelta(lonOf(v) - centerLon) / 360f * width,
    y = screenCenter.y - (latOf(v) - centerLat) / 180f * height
)

/** X for a longitude expressed as an unwrapped offset from the map centre, in degrees. */
fun MapLayout.xForLonOffset(lonOffsetDeg: Float): Float =
    screenCenter.x + lonOffsetDeg / 360f * width

fun MapLayout.yForLat(latDeg: Float): Float =
    screenCenter.y - (latDeg - centerLat) / 180f * height

/**
 * Turns a ring of sphere points into screen paths, one per visible world copy.
 *
 * Longitudes are accumulated continuously along the ring rather than each being wrapped
 * independently, so a shape spanning the antimeridian stays one coherent path instead of being torn
 * in half. Returning the repeated copies means callers just draw everything they are given and get
 * correct wrap-around without knowing this happens.
 */
/**
 * A ring's fill and its outline, which are NOT the same shape for a polygon that encircles a pole.
 *
 * Antarctica is closed by an edge run along latitude -90 that exists only to make the shape fillable;
 * it is not a coastline. Stroking the fill path therefore drew that synthetic edge - a vertical line
 * from the pole up to the ring's first vertex, plus a horizontal run along the bottom - straight
 * across the map. The outline omits it.
 */
class MapRingPaths(val fill: List<Path>, val outline: List<Path>)

/**
 * Optionally displaces each projected point, given the world point it came from and where the map
 * would have put it.
 *
 * This is how the unwrap animation moves the vector layers: the caller supplies a blend between the
 * globe's projection and the map's, and every ring is built through it. Without it the overlays can
 * only sit in one projection or the other, so they have to jump between the two part-way through the
 * transition while the surface underneath is deforming smoothly.
 */
typealias MapPointTransform = (world: Vec3, mapX: Float, mapY: Float) -> Offset

/**
 * Drops points from a ring, breaking it into separate runs where they are dropped.
 *
 * The unwrap uses this to withhold the far hemisphere while the near one is still in front of it -
 * see FAR_SIDE_EMERGES. A predicate rather than a pre-filtered list because the longitude
 * accumulation above has to keep walking the points it is not drawing, or the run after a gap comes
 * out a whole turn away from the one before it.
 */
typealias MapPointFilter = (world: Vec3) -> Boolean

fun buildMapSubpaths(
    points: List<Vec3>,
    treatAsClosed: Boolean,
    layout: MapLayout,
    transform: MapPointTransform? = null,
    visible: MapPointFilter? = null
): List<Path> = buildMapRingPaths(points, treatAsClosed, layout, transform, visible).outline

fun buildMapRingPaths(
    points: List<Vec3>,
    treatAsClosed: Boolean,
    layout: MapLayout,
    transform: MapPointTransform? = null,
    visible: MapPointFilter? = null
): MapRingPaths {
    if (points.size < 2) return MapRingPaths(emptyList(), emptyList())

    val base = Path()
    var previousLon = lonOf(points[0])
    val startAccumulated = wrapLonDelta(previousLon - layout.centerLon)
    var accumulated = startAccumulated
    var minLat = latOf(points[0])
    var maxLat = minLat

    // A transformed ring is mid-unwrap, and the two cases differ in what "where this point goes"
    // even means. On the flat map a point's position is only defined modulo a world width, which is
    // why the whole world is repeated below and rings are free to accumulate past 360 degrees. Part
    // way through the unwrap it is NOT: each point is being blended towards a globe position that
    // has no such freedom, so it has exactly one place to be - inside the single world copy the
    // morphing surface itself spans. Longitudes are folded back into that copy here, and the path is
    // broken where a step folds, rather than the ring being drawn several times over.
    var previousCopy = Int.MIN_VALUE
    var startRun = true
    var broken = false

    for (i in points.indices) {
        val p = points[i]
        val lat: Float
        if (i == 0) {
            lat = minLat
        } else {
            // A vertex sitting on a pole has no meaningful longitude - x and z are both zero there,
            // so atan2 returns an arbitrary 0 - and inheriting the previous one keeps it from
            // injecting a spurious sideways jump. The genuine sideways run along the pole is
            // reconstructed below.
            val lon = if (hypot(p.x, p.z) < POLE_EPSILON) previousLon else lonOf(p)
            accumulated += wrapLonDelta(lon - previousLon)
            previousLon = lon
            lat = latOf(p)
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
        }

        if (visible != null && !visible(p)) {
            startRun = true
            broken = true
            continue
        }

        val lonOffset = if (transform == null) accumulated else {
            val copy = floor((accumulated + 180f) / 360f).toInt()
            if (copy != previousCopy) {
                if (previousCopy != Int.MIN_VALUE) {
                    startRun = true
                    broken = true
                }
                previousCopy = copy
            }
            accumulated - copy * 360f
        }

        val x = layout.xForLonOffset(lonOffset)
        val y = layout.yForLat(lat)
        val at = transform?.invoke(p, x, y) ?: Offset(x, y)
        if (startRun) {
            base.moveTo(at.x, at.y)
            startRun = false
        } else {
            base.lineTo(at.x, at.y)
        }
    }

    // Neither closure nor the polar reconstruction below means anything once the ring has been cut
    // into runs: close() would join the last run's end to its own start rather than to the ring's.
    val closable = treatAsClosed && !broken

    // A ring whose longitude accumulates a full turn does not enclose an area on its own - it
    // encircles a pole. Antarctica is the case that matters: its coastline runs the entire way
    // round, and the polygon is only closed by the edge along latitude -90. Joining the two ends
    // directly, as an ordinary close() does, cuts a diagonal across the map and fills the complement
    // of the continent, which is what showed up as an inverted Antarctica.
    val polar = closable &&
        kotlin.math.abs(accumulated - startAccumulated) > POLAR_RING_TURN_DEG

    // The outline is the data as given. For a polar ring it stays OPEN: both the run out to the pole
    // and the join back to the start are inventions of the fill, and stroking either drew a line
    // across the map that no coastline follows.
    val outline = Path().apply {
        addPath(base)
        if (closable && !polar) close()
    }

    val fill = Path().apply {
        addPath(base)
        if (polar) {
            val poleLat = if (-minLat > maxLat) -90f else 90f
            val yPole = layout.yForLat(poleLat)
            lineTo(layout.xForLonOffset(accumulated), yPole)
            lineTo(layout.xForLonOffset(startAccumulated), yPole)
        }
        if (closable) close()
    }

    // One copy only while a transform is in play, per the folding above: the copies are a world
    // apart in MAP space, and translating an already-blended path by that spacing scatters
    // half-unwrapped ghosts of the world across the screen instead of tiling anything.
    val offsets = if (transform == null) layout.copyOffsets() else PRIMARY_COPY_ONLY
    fun copiesOf(path: Path) = offsets.map { dx ->
        if (dx == 0f) path else Path().apply { addPath(path, Offset(dx, 0f)) }
    }
    return MapRingPaths(fill = copiesOf(fill), outline = copiesOf(outline))
}

private val PRIMARY_COPY_ONLY = listOf(0f)

/**
 * Polygon fill on the map. Every ring of the polygon, and every world copy of it, goes into one
 * [Path] - the copies are 360 degrees apart so they never overlap, and a single path keeps the
 * even-odd hole behaviour of multi-ring polygons (the Caspian, for instance) intact.
 */
fun buildMapPolygonPath(rings: List<List<Vec3>>, layout: MapLayout): MapRingPaths {
    val fill = Path()
    val outline = Path()
    rings.forEach { ring ->
        val paths = buildMapRingPaths(ring, treatAsClosed = true, layout = layout)
        paths.fill.forEach { fill.addPath(it) }
        paths.outline.forEach { outline.addPath(it) }
    }
    return MapRingPaths(listOf(fill), listOf(outline))
}

/**
 * Pans the map by a screen-space drag, returning the updated orientation.
 *
 * Deliberately 1:1 with the finger rather than reusing the globe's `applyDragRotation`: on a sphere a
 * drag rotates by an amount that depends on where the surface was grabbed, which is right for a ball
 * and wrong for a map, where the world should follow the fingertip.
 */
fun panMap(
    orientation: Quaternion,
    dragX: Float,
    dragY: Float,
    zoom: Float,
    viewportSize: Size
): Quaternion {
    val width = viewportSize.width * zoom
    val height = width / 2f
    if (width <= 0f || height <= 0f) return orientation

    val (lat, lon) = viewCenterLatLon(orientation)
    val newLon = lon - dragX / width * 360f
    val halfVisibleLat = viewportSize.height / height * 180f / 2f
    val latLimit = (90f - halfVisibleLat).coerceAtLeast(0f)
    val newLat = (lat + dragY / height * 180f).coerceIn(-latLimit, latLimit)

    return orientationFacing(newLat, newLon)
}

