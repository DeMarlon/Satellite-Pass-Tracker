package com.example.eps_sgtracker.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Paint as ComposePaint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.eps_sgtracker.data.EarthTextures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eps_sgtracker.data.BorderRepository
import com.example.eps_sgtracker.data.CloudRepository
import com.example.eps_sgtracker.data.CountryLabelRepository
import com.example.eps_sgtracker.data.EarthTextureRepository
import com.example.eps_sgtracker.data.GlacierRepository
import com.example.eps_sgtracker.data.LakeRepository
import com.example.eps_sgtracker.data.LandRepository
import com.example.eps_sgtracker.data.RiverRepository
import com.example.eps_sgtracker.data.TrajectoryConfig
import com.example.eps_sgtracker.data.TrajectoryDurationUnit
import com.example.eps_sgtracker.data.CityLightRepository
import com.example.eps_sgtracker.model.CityLight
import com.example.eps_sgtracker.model.CloudContour
import com.example.eps_sgtracker.model.CountryLabel
import com.example.eps_sgtracker.model.GlobePolygon
import com.example.eps_sgtracker.model.GlobeRing
import com.example.eps_sgtracker.model.SphericalCap
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TWO_PI_F = (2.0 * Math.PI).toFloat()

private val OCEAN_COLOR_NEAR = Color(0xFF3E7CB1)
private val OCEAN_COLOR_FAR = Color(0xFF0E2A44)
private val CONTINENT_FILL = Color(0xFF4B7A4B)
private val CONTINENT_STROKE = Color(0xFF2E4F2E)
private val GLACIER_FILL = Color(0xFFF2F5F7)
private val GLACIER_STROKE = Color(0xFFB9C4CC)
// Same family as the ocean sphere so lakes read as "the same water" rather than a distinct color.
private val LAKE_FILL = OCEAN_COLOR_NEAR
private val LAKE_STROKE = OCEAN_COLOR_FAR
private val RIVER_COLOR = OCEAN_COLOR_NEAR.copy(alpha = 0.8f)
private val BORDER_COLOR = Color.Black
private val GRATICULE_COLOR = Color.White.copy(alpha = 0.12f)
private val CLOUD_COLOR = Color(0xFFEAEAEA)
private val ATMOSPHERE_COLOR = Color(0xFF5D9CEC)
// Graded night shading: a stack of filled regions, one per solar elevation, each darkening
// everything below it. Strokes were the wrong tool for this - three of them read as three countable
// lines rather than a gradient, and being sized in screen dp they stayed equally thick at every
// zoom. World-space filled regions are smooth by construction and widen correctly as you zoom in,
// because twilight has a fixed width on the ground, not on screen.
//
// Two numbers control the look, and they are independent:
//
// SPAN is how far past the horizon the shading keeps deepening, in degrees of solar elevation - so
// it sets the band's width on the ground (~110 km per degree) and nothing else. The first attempt
// ran to -18 (astronomical twilight), which is faithful but reads as a very wide smear from orbit;
// 6 degrees is roughly civil twilight and gives a much tighter edge.
//
// COUNT is purely how finely that span is subdivided, and therefore how smooth the ramp looks. Four
// bands still left visible steps, since the eye picks out ~15% jumps in brightness readily, and ten
// still did on a large globe - each band's alpha is the same wherever it lands, so the wider the
// terminator appears on screen, the wider each step is.
//
// This is where the vector globe differs from every other surface in the app: the flat map samples
// its lighting per texel and the photorealistic globe evaluates it per pixel, but a vector globe has
// only geometry to work with, so smoothness has to be bought with band count. The cost is per-band
// ring clipping, which is why this is not simply set very high.
private const val TWILIGHT_SPAN_DEG = 6f
private const val NIGHT_BAND_COUNT = 22

// Total darkening once every band overlaps, in deep night. Per-band alpha is derived from it rather
// than hand-tuned, since the bands compose multiplicatively (each passes 1-a of what reaches it) -
// so changing COUNT alone keeps the final night exactly as dark, just more finely graded.
private const val NIGHT_TOTAL_DARKENING = 0.58f
private val NIGHT_BAND_ALPHA = 1f - (1f - NIGHT_TOTAL_DARKENING).pow(1f / NIGHT_BAND_COUNT)

// Shading tone runs warm at the horizon (sunset reddening) to cold blue in deep night. The blue
// does real work beyond mood: flat black over the green land fill just turns it khaki, whereas a
// blue-tinted shade desaturates it into something that reads as unlit.
//
// Bands are confined to the night side on purpose. An attempt to extend them across the lit
// hemisphere as well - to get view-independent Lambertian shading, which a screen-space gradient
// genuinely cannot provide - sampled 90 degrees of daylight with only three rings, and three steps
// spread over that range read as broad diagonal bands across the day side. Revisiting that needs
// *more* lit-side samples, not fewer, which in turn needs the bands drawn as non-overlapping annuli
// rather than stacked fills, or the fill rate becomes the problem instead.
private val NIGHT_WARM = Color(0xFF3A2010)
private val NIGHT_COOL = Color(0xFF04060F)

// The span above expressed the way every renderer actually tests it: as the sun's height, which is
// what cos(normal to sun) already is.
private val NIGHT_TWILIGHT_SPAN_SIN =
    sin(Math.toRadians(TWILIGHT_SPAN_DEG.toDouble())).toFloat().coerceAtLeast(1e-4f)

// What the stack of bands composites to at each one of them.
//
// The bands used to be drawn as nested lobes, each translucent layer darkening everything inside it,
// so deep night was painted by all 22 of them - see buildShadowBandFill for why that got expensive.
// They are now drawn as the regions BETWEEN consecutive rings, which tile instead of overlapping, so
// each one has to arrive already carrying the colour the stack would have reached there. That is
// just src-over accumulation, and it depends on nothing but the constants above, so it is run once
// here rather than by the GPU on every pixel of every frame.
private val NIGHT_BAND_COLORS: List<Color> = run {
    // Premultiplied, which is the form src-over accumulates in.
    var red = 0f
    var green = 0f
    var blue = 0f
    var alpha = 0f
    List(NIGHT_BAND_COUNT) { index ->
        val band = lerp(NIGHT_WARM, NIGHT_COOL, index / (NIGHT_BAND_COUNT - 1f))
        red = band.red * NIGHT_BAND_ALPHA + (1f - NIGHT_BAND_ALPHA) * red
        green = band.green * NIGHT_BAND_ALPHA + (1f - NIGHT_BAND_ALPHA) * green
        blue = band.blue * NIGHT_BAND_ALPHA + (1f - NIGHT_BAND_ALPHA) * blue
        alpha = NIGHT_BAND_ALPHA + (1f - NIGHT_BAND_ALPHA) * alpha
        Color(red / alpha, green / alpha, blue / alpha, alpha)
    }
}

// How far the sun has to swing behind the globe before the ocean's shading gradient has flattened
// out completely, measured as the sun's z-component in view space. Full strength is kept for all of
// z >= 0 - the entire range where the lit side faces the camera - so this only affects the view of
// the night side, where the gradient's highlight would otherwise sit on unlit ocean.
private const val OCEAN_GRADIENT_FADE_DEPTH = 0.6f

// Warm sodium-lamp tone for the night-side city lights.
private val CITY_LIGHT_COLOR = Color(0xFFFFC46B)

// Specular highlight where the sun reflects off the ocean, drawn under the land layers so it only
// ever shows on water. The two alphas are peak values, reached only with the subsolar point dead
// centre; everything else in view fades them off towards the limb.
private val SUN_GLINT_COLOR = Color(0xFFFFF6DC)
private const val SUN_GLINT_CORE_ALPHA = 0.14f
private const val SUN_GLINT_GLOW_ALPHA = 0.06f

// How far into daylight the limb has to be before the atmosphere reaches full strength, as a dot
// product against the sun direction. Below this it fades out towards the terminator, and past the
// terminator it is gone entirely.
//
// Sized against the segment count, not chosen for looks alone. Illumination around the limb varies
// as the cosine of the angle to the sun, so a threshold of t spreads the fade over roughly
// (90 - acos(t)) degrees of arc. At 0.35 that was ~20 degrees - under three segments at the
// original count of 48 - so the "fade" was really a three-step staircase whose steps landed
// differently as the globe turned, which read as the rim flickering on and off in patches.
private const val LIMB_FADE_DEPTH = 0.5f

// Low enough that a GEO satellite (orbit radius ~6.6 Earth radii) stays fully on-screen even
// viewed edge-on/perpendicular to its pass line, the worst case for how far it sits from the
// globe in screen space (screen distance from globe center = orbitRadius * baseRadiusPx * zoom).
private const val MIN_ZOOM = 0.2f
// High enough to inspect ground-station markers and satellite detail up close.
private const val MAX_ZOOM = 10f
// Only the near hemisphere (roughly half) is ever drawn at once - see the star-field draw loop -
// so this is sized for that visible half to read at the same density the old always-both-
// hemispheres version had.
private const val STAR_COUNT = 320
private const val RESET_ANIMATION_MS = 450

// Fixed points on a celestial sphere in world space (like coastline points, just for the sky
// instead of the globe) rather than screen-space fractions - see the rotation loop below for why.
private data class Star(
    val position: Vec3,
    val radiusDp: Float,
    val baseAlpha: Float,
    val phase: Float
)

private val LABEL_TEXT_STYLE = TextStyle(
    color = Color.White,
    fontSize = 10.sp,
    shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)
)

// Slightly smaller/dimmer than LABEL_TEXT_STYLE so country names read as background map detail,
// not competing with the operational station/satellite labels sharing the same draw step.
private val COUNTRY_LABEL_TEXT_STYLE = TextStyle(
    color = Color(0xFFDDDDDD),
    fontSize = 9.sp,
    shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 3f)
)

// A country's label is only drawn once its largest part's on-screen projected size clears this
// floor - doubles as both low-zoom declutter and a bound on how many distinct strings
// TextMeasurer's LRU cache has to shape per frame (see the larger cacheSize passed to
// rememberTextMeasurer below). MAX_COUNTRY_LABELS is a hard safety cap on top of that, biting the
// *smallest* surviving candidates first (see the sort-then-take in the draw loop).
private const val MIN_COUNTRY_LABEL_SIZE_DP = 24f
private const val MAX_COUNTRY_LABELS = 40

private data class CountryLabelCandidate(val name: String, val screen: Offset, val sizePx: Float)

// How far past the terminator a city has to be before its light reaches full brightness, as a dot
// product against the sun direction. Ramping over a band rather than switching on at exactly zero
// keeps the lights from drawing a hard line of their own straight through the twilight gradient.
private const val CITY_LIGHT_FADE_DEPTH = 0.15f

// Below this the globe is too small for individual cities to read as anything but noise, so only
// the largest survive; by full zoom every city is shown. Compared against CityLight.magnitude.
private const val CITY_LIGHT_MIN_MAGNITUDE_AT_MIN_ZOOM = 0.62f

// Brightness of the largest city at full night, dead centre of the globe - every other light is
// this scaled down by population, how far past the terminator it sits, and how far it has turned
// towards the limb. The single dial for how loud the night side reads.
private const val CITY_LIGHT_PEAK_ALPHA = 0.20f

// Segment count for the sun-modulated atmosphere rim. Needs to be high enough that the day-to-night
// fade spans many segments rather than a handful - see LIMB_FADE_DEPTH. Roughly half of these are
// on the night side and skipped outright, and an arc is a rounding error against the vector layers
// below it, so the count is cheap to raise.
private const val LIMB_SEGMENTS = 160

// Fullscreen control auto-hide, video-player style: the controls retire after a few idle seconds
// and come back on any touch. The fade back in is deliberately much faster than the fade out - it
// answers a touch the user just made, so it needs to feel immediate, whereas the fade out is
// unprompted and should be gentle enough not to pull attention away from the globe.
// How finely the station-to-satellite connector is sampled on the map. The globe gets this for free
// from visibleSegmentRuns' occlusion sampling; the map has nothing to occlude, so it samples only
// enough to curve smoothly and to give the seam split something to cut between.
private const val CONNECTOR_MAP_SAMPLES = 24


// Long enough to read as the globe unwrapping rather than snapping, short enough not to be in the
// way. The mesh renderer only runs for this window.
private const val MORPH_ANIMATION_MS = 700

// How far into the unwrap the far hemisphere stops being hidden behind the near one.
//
// The surface itself needs no such number - the mesh draws its far quads first and lets the near
// ones cover them - but the overlays are one flat pass with no depth to sort by, so drawing the far
// side from the first frame slid the back of the world across the front of it for as long as the
// near side was still in the way. That is longer than it sounds: the deepest far point (dead centre
// of the back) starts at the middle of the disc and has to travel out past the near side's own edge,
// which on a portrait viewport it only manages around the halfway mark. Erring late is the safe
// direction - content withheld a little too long is a duplicate of what is drawn anyway, whereas
// content released too early lands on top of the front of the globe.
private const val FAR_SIDE_EMERGES = 0.5f

/**
 * Vertex tints for the unwrap mesh - one set per renderer it can be standing in for.
 *
 * Each is that renderer's own night model, evaluated per vertex instead of per pixel or per texel.
 * The mesh used to carry a third model of its own, with an ambient term and a night level that
 * belonged to neither, which is why the night side visibly changed brightness the moment the
 * transition started and again when it ended.
 */
private fun imageryDayTint(cosSun: Float): Int = greyTint(earthDayLevelAt(cosSun))

private fun imageryNightTint(cosSun: Float): Int = greyTint(earthNightLevelAt(cosSun))

/**
 * The vector world's night: exactly the curve [buildMapNightTint] bakes for the flat map, and the
 * continuous limit of the band stack the vector globe draws.
 *
 * Both of those composite a tint OVER what is underneath, while a vertex colour can only multiply
 * into it - so the tint is folded into the multiplier rather than added. The two differ by
 * `tint * darkness * (1 - texel)`, and since the tint is all but black to begin with that is at most
 * a few percent on the darkest texels and nothing at all on the brightest.
 */
private fun vectorSurfaceTint(cosSun: Float): Int {
    val depth = (-cosSun / NIGHT_TWILIGHT_SPAN_SIN).coerceIn(0f, 1f)
    val darkness = 1f - (1f - NIGHT_TOTAL_DARKENING).pow(depth)
    val tint = lerp(NIGHT_WARM, NIGHT_COOL, depth)
    val lit = 1f - darkness
    return channelTint(
        lit + tint.red * darkness,
        lit + tint.green * darkness,
        lit + tint.blue * darkness
    )
}

private fun greyTint(level: Float): Int = channelTint(level, level, level)

private fun channelTint(red: Float, green: Float, blue: Float): Int =
    (0xFF shl 24) or
        ((red.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
        ((green.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
        (blue.coerceIn(0f, 1f) * 255f).toInt()



private const val CONTROLS_AUTO_HIDE_MS = 5000L
private const val CONTROLS_FADE_IN_MS = 120
private const val CONTROLS_FADE_OUT_MS = 600

// Savers for the camera state below, so leaving the screen no longer discards the view the user set
// up. Both types need one: Quaternion is a plain data class and Offset is a value class over a
// packed Long, so neither is Parcelable or Serializable and neither can go into a Bundle as-is.
// Kept here rather than in GlobeMath.kt - that file is geometry, this is a UI-state concern, and
// this is the only place either is saved.
private val QuaternionSaver = listSaver<Quaternion, Float>(
    save = { listOf(it.w, it.x, it.y, it.z) },
    restore = { Quaternion(it[0], it[1], it[2], it[3]) }
)

private val OffsetSaver = listSaver<Offset, Float>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) }
)

@Composable
fun Satellite3DView(
    viewModel: TrackerViewModel,
    modifier: Modifier = Modifier
) {
    // visibleTrackedSatellites (not trackedSatellites directly) so a satellite hidden via the
    // Setup screen's eye toggle drops out of the 3D view too, while staying in the tracked list.
    val trackedSats by viewModel.visibleTrackedSatellites.collectAsStateWithLifecycle()
    val availableStations by viewModel.availableStations.collectAsStateWithLifecycle()
    val activeStationCodes by viewModel.activeStationCodes.collectAsStateWithLifecycle()
    val calculatedPasses by viewModel.calculatedPasses.collectAsStateWithLifecycle()
    val cloudLayerEnabled by viewModel.cloudLayerEnabled.collectAsStateWithLifecycle()
    val stationColorOverrides by viewModel.stationColorOverrides.collectAsStateWithLifecycle()
    val satelliteColorOverrides by viewModel.satelliteColorOverrides.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Default LRU cache size is 8, fine for the handful of station/satellite labels this measurer
    // used to serve exclusively - comfortably covers those plus up to MAX_COUNTRY_LABELS country
    // names sharing the same measurer now.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val coroutineScope = rememberCoroutineScope()
    // Shared by both the reset-view and north-up buttons (and cancelled on any new touch) so
    // only one orientation animation is ever driving the `orientation`/`zoom`/`panOffset` state
    // at a time.
    var viewAnimationJob by remember { mutableStateOf<Job?>(null) }

    // Fullscreen lives in the ViewModel because MainAppShell needs it to drop the bottom nav bar;
    // whether the *in-view* controls are currently revealed is purely local to this screen.
    val is3DFullscreen by viewModel.is3DFullscreen.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var touchActive by remember { mutableStateOf(false) }
    // Re-armed by every change to either input: entering fullscreen, and each touch down and up.
    // Each restart shows the controls and begins the idle countdown afresh - and because leaving
    // fullscreen also restarts it, controls can never stay hidden outside fullscreen.
    LaunchedEffect(is3DFullscreen, touchActive) {
        controlsVisible = true
        // No countdown while a finger is still down, otherwise the controls would fade out from
        // under a slow drag.
        if (!is3DFullscreen || touchActive) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }
    val controlsShown = !is3DFullscreen || controlsVisible

    // Movie/video-player behavior: watching a pass play out involves no touch input for minutes at
    // a time, which would otherwise let the display time out. Scoped to fullscreen only, and
    // cleared in onDispose so backgrounding the app or leaving the tab can't leave the flag set.
    val activity = LocalActivity.current
    DisposableEffect(is3DFullscreen, activity) {
        val window = activity?.window
        if (is3DFullscreen) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Photorealistic Earth: NASA imagery lit by a GPU shader, replacing the vector surface layers
    // entirely. `earthShader` is the single source of truth for which renderer is live - it stays
    // null if the setting is off, if the platform predates RuntimeShader (API 33), or if either
    // texture failed to decode, and every branch below tests it rather than the setting. That way a
    // device that cannot deliver the upgrade silently keeps the vector globe instead of showing
    // nothing.
    val photorealisticEarth by viewModel.photorealisticEarth.collectAsStateWithLifecycle()
    var earthShader by remember { mutableStateOf<EarthShader?>(null) }
    // Held alongside the shader because the map draws the imagery directly. Equirectangular maps
    // lat/lon linearly onto a rectangle, so on a flat map the day texture is simply the map - it can
    // be blitted into place with no shader and no resampling, which is why 2D needs no GPU path of
    // its own.
    var earthTextures by remember { mutableStateOf<EarthTextures?>(null) }
    // The map blits through Compose's ImageBitmap while the morph mesh needs the platform Bitmap for
    // its BitmapShader, so the wrapper is derived once rather than per frame.
    val dayImage = remember(earthTextures) { earthTextures?.day?.asImageBitmap() }
    val nightImage = remember(earthTextures) { earthTextures?.night?.asImageBitmap() }
    LaunchedEffect(photorealisticEarth) {
        val textures = if (photorealisticEarth) EarthTextureRepository.loadTextures(context) else null
        earthShader = textures?.let { createEarthShader(it) }
        earthTextures = textures
    }
    // The vector map layers are only redundant where imagery actually replaces them, which now means
    // either renderer - the shader on the globe, or the blitted texture on the map.
    val useShader = earthShader != null || earthTextures != null

    // The vector surface layers. All four are subsumed by the imagery, so they are neither parsed
    // nor drawn while the shader is live - which also hands back the several MB their geometry
    // occupies. Same load-on-demand pattern the borders/labels/cloud layers already use.
    var landPolygons by remember { mutableStateOf<List<GlobePolygon>>(emptyList()) }
    LaunchedEffect(useShader) {
        landPolygons = if (useShader) emptyList() else LandRepository.loadLandPolygons(context)
    }
    var glaciers by remember { mutableStateOf<List<GlobePolygon>>(emptyList()) }
    LaunchedEffect(useShader) {
        glaciers = if (useShader) emptyList() else GlacierRepository.loadGlaciers(context)
    }
    var lakes by remember { mutableStateOf<List<GlobePolygon>>(emptyList()) }
    LaunchedEffect(useShader) {
        lakes = if (useShader) emptyList() else LakeRepository.loadLakes(context)
    }
    var rivers by remember { mutableStateOf<List<GlobeRing>>(emptyList()) }
    LaunchedEffect(useShader) {
        rivers = if (useShader) emptyList() else RiverRepository.loadRivers(context)
    }
    // Always on when the vector renderer is live, like lakes/rivers: 243 points is small enough that
    // neither the parse nor the per-frame cull is worth gating behind a setting. The shader reads
    // city lights from its own night texture instead.
    var cityLights by remember { mutableStateOf<List<CityLight>>(emptyList()) }
    LaunchedEffect(useShader) {
        cityLights = if (useShader) emptyList() else CityLightRepository.loadCityLights(context)
    }
    // Country borders/labels are the priciest new layer (see MIN_COUNTRY_LABEL_SIZE_DP's doc
    // comment), so - unlike lakes/rivers - they're both off by default AND only parsed at all
    // once the user actually enables them, matching the cloud layer's load-on-demand pattern.
    // Borders and labels are independent toggles, each with its own load/unload effect.
    val showCountryBorders by viewModel.showCountryBorders.collectAsStateWithLifecycle()
    var borders by remember { mutableStateOf<List<GlobeRing>>(emptyList()) }
    LaunchedEffect(showCountryBorders) {
        borders = if (showCountryBorders) BorderRepository.loadBorders(context) else emptyList()
    }
    // Globe or flat map. Saveable, matching the camera state right below it: the choice survives
    // leaving the tab and being backgrounded, but a genuine cold start comes back to the globe.
    var viewMode by rememberSaveable { mutableStateOf(GlobeViewMode.GLOBE) }

    // 0 = globe, 1 = flat map, and everything between is the unwrap. Animated rather than switched
    // so every layer can interpolate its own projection off a single parameter.
    val morph by animateFloatAsState(
        targetValue = if (viewMode == GlobeViewMode.MAP) 1f else 0f,
        animationSpec = tween(MORPH_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "globeMapMorph"
    )
    // The unwrap warps a TEXTURE across a mesh, so it needs one to exist. Without the photorealistic
    // imagery there is nothing to deform - and animating anyway just faded the surface out to black
    // and back, since the vector layers cannot morph and the mesh had nothing to draw. In that case
    // the switch is instant, which is honest rather than a broken animation.
    val meshBuffers = remember { EarthMeshBuffers() }

    // The vector world, baked into a texture so the unwrap works without the photorealistic imagery
    // - the mesh warps a texture, and with none there was nothing to deform. Built lazily on the
    // first transition and only rebuilt when the layers it draws actually change, since it costs a
    // full-world rasterisation.
    val vectorWorldKey = VectorWorldKey(
        landPolygons.size, glaciers.size, lakes.size, rivers.size, borders.size, showCountryBorders
    )
    var vectorWorld by remember { mutableStateOf<Bitmap?>(null) }
    var vectorWorldBuiltFor by remember { mutableStateOf<VectorWorldKey?>(null) }
    LaunchedEffect(vectorWorldKey, photorealisticEarth) {
        if (!photorealisticEarth && landPolygons.isNotEmpty() && vectorWorldBuiltFor != vectorWorldKey) {
            val baked = withContext(Dispatchers.Default) {
                renderVectorWorldTexture(
                    land = landPolygons, glaciers = glaciers, lakes = lakes,
                    rivers = rivers, borders = borders, showBorders = showCountryBorders,
                    oceanColor = lerp(OCEAN_COLOR_NEAR, OCEAN_COLOR_FAR, 0.5f),
                    landColor = CONTINENT_FILL, landStroke = CONTINENT_STROKE,
                    glacierColor = GLACIER_FILL, lakeColor = LAKE_FILL,
                    riverColor = RIVER_COLOR, borderColor = BORDER_COLOR
                )
            }
            vectorWorld = baked
            vectorWorldBuiltFor = vectorWorldKey
        }
    }

    // Whichever texture this mode has to offer. Without one there is nothing to warp, so the switch
    // stays instant rather than animating a blank surface.
    val morphTextureBitmap = if (photorealisticEarth) earthTextures?.day else vectorWorld

    // Whether the unwrap is actually in flight - NOT simply whether `morph` sits between its two
    // ends. `viewMode` flips the instant the toggle is tapped, while `morph` is still parked on the
    // value it had, so a test on the value alone left one frame in which the destination had already
    // been selected and the animation had not yet started: tapping 2D drew the complete flat map for
    // a frame, which was then replaced by the globe and unwrapped into the map again. Comparing
    // against the target instead keeps that first frame on the mesh, at exactly 0 (or 1), where it
    // reproduces the mode being left.
    val morphTarget = if (viewMode == GlobeViewMode.MAP) 1f else 0f
    val morphing = morph != morphTarget && morphTextureBitmap != null

    // What the mesh shades with while it owns the surface. It has no lighting model of its own on
    // purpose: for those 700ms it stands in for whichever renderer the mode uses, and anything else
    // shows as the night side jumping in brightness at both ends of the transition.
    val meshDayTint: (Float) -> Int =
        if (photorealisticEarth) ::imageryDayTint else ::vectorSurfaceTint
    // Only the imagery has a night photograph to reveal. The vector world's night is a tint, which
    // vectorSurfaceTint already folds into the one multiplier it returns.
    val meshNightTint: ((Float) -> Int)? = if (photorealisticEarth) ::imageryNightTint else null
    // Map panning is 1:1 with the finger, so it needs the viewport in the gesture handler - which
    // sits outside the Canvas and so cannot read DrawScope.size.
    var viewportSize by remember { mutableStateOf(Size.Zero) }

    // Unlike borders/labels there is nothing to load on demand - buildGraticuleRings is generated
    // geometry, not a parsed asset - so this only gates the draw.
    val showGraticule by viewModel.showGraticule.collectAsStateWithLifecycle()
    val showCountryLabels by viewModel.showCountryLabels.collectAsStateWithLifecycle()
    var countryLabels by remember { mutableStateOf<List<CountryLabel>>(emptyList()) }
    LaunchedEffect(showCountryLabels) {
        countryLabels = if (showCountryLabels) CountryLabelRepository.loadCountryLabels(context) else emptyList()
    }
    var cloudContours by remember { mutableStateOf<List<CloudContour>>(emptyList()) }
    LaunchedEffect(cloudLayerEnabled) {
        cloudContours = if (cloudLayerEnabled) CloudRepository.loadCloudCells(context) else emptyList()
    }
    val graticule = remember { buildGraticuleRings() }
    val stars = remember {
        val rng = Random(20240713)
        List(STAR_COUNT) {
            // Uniform sampling on a unit sphere (not lat/lon, which would bunch points at the
            // poles): pick z uniformly in [-1,1] and an independent uniform azimuth.
            val z = rng.nextFloat() * 2f - 1f
            val azimuth = rng.nextFloat() * (2f * Math.PI.toFloat())
            val ringRadius = sqrt(1f - z * z)
            Star(
                position = Vec3(ringRadius * cos(azimuth), ringRadius * sin(azimuth), z),
                radiusDp = rng.nextFloat() * 1.3f + 0.4f,
                baseAlpha = rng.nextFloat() * 0.5f + 0.35f,
                phase = rng.nextFloat() * 6.2832f
            )
        }
    }

    // Saveable, not plain remember: navigating away disposes this composition, so the view the user
    // had rotated and zoomed to was discarded and the globe snapped back to its default every time
    // they came back. Saved state survives that, survives a config change, and survives being
    // backgrounded even if the OS reclaims the process - while still resetting on a genuine cold
    // start. A ViewModel would cover the first two but not the third, since it dies with the
    // Activity; the reset-view button remains the deliberate way back to the default.
    var orientation by rememberSaveable(stateSaver = QuaternionSaver) { mutableStateOf(Quaternion.IDENTITY) }
    // Always written through a MIN_ZOOM..MAX_ZOOM clamp, so a restored value is in range by
    // construction.
    var zoom by rememberSaveable { mutableStateOf(1f) }
    // Stored in pixels, so restoring into a different window size (rotation, split screen) reinstates
    // the same pixel offset rather than the same relative position. Not a new failure mode - panning
    // is unbounded to begin with, so the same state is reachable by dragging, and reset recovers it.
    var panOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    var currentFrameTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var lastTick = 0L
        while (true) {
            withInfiniteAnimationFrameMillis {
                val now = System.currentTimeMillis()
                if (now - lastTick >= 33) { // 30 FPS Lock Optimization
                    currentFrameTime = now
                    lastTick = now
                }
            }
        }
    }
    // The sun barely moves minute to minute, so only recompute its direction once a minute
    // rather than on every animation frame.
    val sunDir = remember(currentFrameTime / 60_000L) { sunDirectionAt(currentFrameTime) }

    // The map's lighting, sampled per texel into small bitmaps. Rebuilt only when the sun moves,
    // which sunDir already quantises to once a minute - never per frame. The vector variant has no
    // night mask, because there is no night photograph to reveal through one.
    val mapNight = remember(sunDir, photorealisticEarth) {
        if (photorealisticEarth) {
            buildMapLighting(sunDir).let {
                MapLightingImages(it.darkening.asImageBitmap(), it.nightMask.asImageBitmap())
            }
        } else {
            MapLightingImages(
                buildMapNightTint(
                    sunDir, NIGHT_WARM, NIGHT_COOL, NIGHT_TOTAL_DARKENING, TWILIGHT_SPAN_DEG
                ).asImageBitmap(),
                null
            )
        }
    }
    // One ring per shading band, evenly spaced from the horizon down to -TWILIGHT_SPAN_DEG, with
    // the tone interpolated warm-to-cool across the same range. Keyed on sunDir, so the rings
    // survive every frame in between the once-a-minute sun update and only clipping is per-frame.
    // Per-band alpha is derived from the target curve rather than hand-tuned, because the bands
    // stack multiplicatively - each one passes (1 - alpha) of whatever reaches it. Tracking the
    // running transmittance and solving for the next step is what lets the elevation list be
    // resampled freely without the final night getting lighter or darker as a side effect.
    val nightRings = remember(sunDir) {
        List(NIGHT_BAND_COUNT) { index ->
            sunElevationRing(sunDir, -TWILIGHT_SPAN_DEG * index / (NIGHT_BAND_COUNT - 1f))
        }
    }
    // One reusable Path per band, rewritten in place each frame. Allocating these per frame instead
    // is what made a ten-band gradient unusable - see buildShadowBandFill's doc comment.
    val nightBandPaths = remember { List(NIGHT_BAND_COUNT) { Path() } }
    // The bands tile rather than overlap, so they have to be drawn with antialiasing OFF or every
    // shared edge shows as a lighter hairline (again, see buildShadowBandFill). DrawScope's own
    // draws are always antialiased, so this needs a paint of its own.
    val nightBandPaint = remember {
        ComposePaint().apply { isAntiAlias = false; style = PaintingStyle.Fill }
    }
    // Reused projection buffers and path pool for every vector layer on the globe. Remembered rather
    // than rebuilt per frame for the same reason the mesh's buffers are.
    val globeScratch = remember { GlobeProjectionScratch() }

    // Trajectory sample points barely change frame-to-frame, so recompute at most every 10s
    // rather than on every animation frame - re-running SGP4 propagation for ~100
    // samples/satellite 30 times a second would be wasted work for no visible benefit. Must be
    // computed here (composable scope), not inside the Canvas draw lambda below - that lambda is
    // a plain DrawScope callback re-run every frame, not composable context, so remember() isn't
    // available there (same reason sunDir/graticule/stars are all computed up here too).
    // trajectoryConfig is a remember key so a settings change (durations, units, on/off) takes
    // effect immediately instead of waiting out the 10s throttle bucket; disabled skips the SGP4
    // work entirely, not just the drawing.
    val trajectoryConfig by viewModel.trajectoryConfig.collectAsStateWithLifecycle()
    var showTrajectorySettings by remember { mutableStateOf(false) }
    val trajectoriesByNoradId = remember(trackedSats, currentFrameTime / 10_000L, trajectoryConfig) {
        if (!trajectoryConfig.enabled) {
            emptyMap()
        } else {
            trackedSats.associate { sat ->
                sat.noradId to (viewModel.getSatelliteTrajectory(sat.noradId, currentFrameTime) ?: emptyList())
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The Canvas below never clears - every layer is drawn over whatever this leaves - so
            // this is the one place the empty background is decided. Black rather than a near-black
            // because it is standing in for space in both modes: behind the stars on the globe, and
            // in the letterbox above and below the flat map's 2:1 world rect. MainAppShell paints
            // the same black behind the system bars while this screen is up, so the two meet
            // without a seam.
            .background(Color.Black)
            .onSizeChanged { viewportSize = Size(it.width.toFloat(), it.height.toFloat()) }
            // Pure observer for the auto-hide timer, kept separate from the globe gesture below.
            // Watches the Initial pass and consumes nothing, so it still sees touches that land on
            // the control buttons themselves (those consume on Main, which would otherwise hide
            // them from the globe handler and let the countdown expire while the user is actively
            // pressing buttons).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touchActive = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touchActive = false
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // requireUnconsumed = true so a tap that lands on the north-up/reset-view
                    // buttons (which sit in this same Box and consume their own down event) isn't
                    // also picked up here as the start of a drag - without it, every button tap
                    // registered as a tiny unwanted rotation.
                    awaitFirstDown(requireUnconsumed = true)
                    viewAnimationJob?.cancel()
                    var prevCentroid: Offset? = null
                    var prevSpread: Float? = null
                    var prevTwistAngle: Float? = null
                    var prevCount = 0
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }
                        if (pointers.isEmpty()) break

                        if (pointers.size != prevCount) {
                            val centroid = centroidOf(pointers)
                            prevCentroid = centroid
                            prevSpread = averageSpread(pointers, centroid)
                            prevTwistAngle = twistAngleOf(pointers)
                            prevCount = pointers.size
                        } else if (pointers.size == 1) {
                            val delta = pointers[0].positionChange()
                            orientation = if (viewMode == GlobeViewMode.MAP) {
                                panMap(orientation, delta.x, delta.y, zoom, viewportSize)
                            } else {
                                applyDragRotation(orientation, delta.x, delta.y)
                            }
                            pointers[0].consume()
                        } else {
                            val centroid = centroidOf(pointers)
                            val spread = averageSpread(pointers, centroid)
                            val centroidDelta = centroid - (prevCentroid ?: centroid)
                            // On a map the two gestures are the same thing: a two-finger drag pans
                            // the world exactly as a one-finger drag does, rather than sliding the
                            // whole projection around inside the viewport the way panOffset does for
                            // the globe. Leaving panOffset alone here also keeps it from surviving a
                            // switch back to 3D as an unexplained offset.
                            if (viewMode == GlobeViewMode.MAP) {
                                orientation = panMap(orientation, centroidDelta.x, centroidDelta.y, zoom, viewportSize)
                            } else {
                                panOffset += centroidDelta
                            }
                            val basisSpread = prevSpread
                            if (basisSpread != null && basisSpread > 1e-3f) {
                                zoom = (zoom * (spread / basisSpread)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            }
                            // Two-finger twist: the change in the finger-pair's angle drives a
                            // roll around the screen-normal axis - the same rotation degree of
                            // freedom the North-up button snaps back to zero. Runs alongside
                            // pan/zoom in the same gesture, matching how map apps combine all
                            // three two-finger transforms simultaneously.
                            // Twist is skipped on a map: roll has no meaning there, and applying it
                            // would tilt north away from up with no way to see that it had happened.
                            val twistAngle = twistAngleOf(pointers)
                            val basisTwist = prevTwistAngle
                            if (viewMode == GlobeViewMode.GLOBE && twistAngle != null && basisTwist != null) {
                                orientation = applyTwistRotation(orientation, normalizeAngleRad(twistAngle - basisTwist))
                            }
                            prevCentroid = centroid
                            prevSpread = spread
                            prevTwistAngle = twistAngle
                            pointers.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Hands every path taken last frame back to the pool. Everything below draws from it and
            // is finished with what it took by the time this returns.
            globeScratch.beginFrame()
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadiusPx = size.width.coerceAtMost(size.height) * 0.28f
            val globeCenter = center + panOffset
            val globeRadius = baseRadiusPx * zoom

            // The single point where the two view modes diverge. Every layer below - coastlines,
            // borders, ground tracks, markers, the terminator - is a list of unit-sphere points, and
            // the ONLY thing that differs between a globe and a flat map is how one of those becomes
            // a screen coordinate. Routing all of them through these four helpers is what lets both
            // modes share every layer, every setting and every gesture rather than needing a second
            // renderer.
            //
            // Dispatch happens here, once per layer, rather than inside the vertex loops: the
            // subpath builders each pick their implementation up front and then run monomorphically
            // over what can be tens of thousands of points a frame.
            val flatLayout = mapLayoutFor(orientation, zoom, center, size.width, size.height)

            // Steady-state map only. Null throughout the transition, because while the mesh owns the
            // surface there is no flat rectangle to blit imagery or lighting into.
            val mapLayout = if (viewMode == GlobeViewMode.MAP && !morphing) flatLayout else null

            // The layers ride the SAME interpolation as the mesh, rather than sitting in one
            // projection or the other. Previously they snapped between the two half-way through and a
            // fade was used to hide it - which meant every overlay had to vanish mid-transition,
            // including the country borders. Interpolating the projected position instead is what
            // lets them stay on screen and deform with the surface.
            val morphTransform: MapPointTransform? = if (!morphing) null else { world, mapX, mapY ->
                val g = projectRotated(orientation.rotate(world), zoom, panOffset, center, baseRadiusPx)
                Offset(g.x + (mapX - g.x) * morph, g.y + (mapY - g.y) * morph)
            }

            // Which projection the vector layers are built against. Flat whenever the map is
            // involved at all, including mid-transition, where morphTransform then blends it back
            // toward the globe.
            val layerLayout = if (viewMode == GlobeViewMode.MAP || morphing) flatLayout else null

            // Whether the far hemisphere has come out from behind the near one yet - see
            // FAR_SIDE_EMERGES. Until it has, the overlays keep the globe's own culling, so the back
            // of the world is not painted across the front of it while the mesh is still covering it
            // with the near side.
            val farSideShowing = !morphing || morph >= FAR_SIDE_EMERGES
            val farSideFilter: MapPointFilter? = if (layerLayout == null || farSideShowing) null
                else { v -> !isObscuredByGlobe(orientation.rotate(v)) }

            fun subpathsOf(points: List<Vec3>, closed: Boolean): List<Path> =
                layerLayout?.let { buildMapSubpaths(points, closed, it, morphTransform, farSideFilter) }
                    ?: buildVisibleSubpaths(
                        points, closed, orientation, zoom, panOffset, center, baseRadiusPx, globeScratch
                    )

            fun screenOf(v: Vec3): Offset = layerLayout?.let { layout ->
                val m = layout.project(v)
                morphTransform?.invoke(v, m.x, m.y) ?: m
            } ?: projectRotated(orientation.rotate(v), zoom, panOffset, center, baseRadiusPx)

            // A map hides nothing - the globe's far hemisphere is simply the other half of the
            // rectangle. Mid-transition it eventually hides nothing either, since the mesh unwraps
            // BOTH hemispheres, but not from the first frame: until the far side has actually come
            // out from behind the near one, the globe's culling is still the honest answer.
            fun hidden(v: Vec3): Boolean =
                (layerLayout == null || !farSideShowing) && isObscuredByGlobe(orientation.rotate(v))

            // Fill and outline are separate because a polar ring's fill contains a synthetic closure
            // along the pole that is not a coastline and must not be stroked. On the globe the two
            // are the same path, so the outline is simply the fill.
            fun polygonPathsOf(polygon: GlobePolygon): Pair<Path, Path>? = if (mapLayout != null) {
                val paths = buildMapPolygonPath(polygon.rings, mapLayout)
                paths.fill.first() to paths.outline.first()
            } else {
                buildVisiblePolygonPath(
                    polygon, orientation, zoom, panOffset, center, baseRadiusPx, size, globeScratch
                )?.let { it to it }
            }

            // Cheap reject for a whole ring before any of its points are rotated - see
            // SphericalCap.mayBeVisible. Only on the globe: the map repeats the world sideways, so
            // where a ring lands there is not a single place a cap could describe.
            fun onScreen(cap: SphericalCap): Boolean =
                layerLayout != null ||
                    cap.mayBeVisible(orientation, zoom, panOffset, center, baseRadiusPx, size)

            // 0. Star field backdrop with a gentle twinkle. Rotated by the same `orientation` as
            // everything else (rather than fixed in screen space) so the backdrop responds to
            // the drag/orbit gesture like a real sky the camera is turning through, not a static
            // wallpaper pinned behind the globe. Projected at a fixed radius sized to always
            // cover the screen corner-to-corner regardless of zoom/pan, so it reads as an
            // effectively infinite backdrop rather than shrinking/panning with the globe.
            //
            // Only one hemisphere of the star sphere is drawn at a time - showing both at once
            // means the far hemisphere's points are on screen simultaneously with the near
            // hemisphere's, and for a single rigid rotation their orthographic screen-space
            // velocity is the *negative* of each other at every instant (basic rotational
            // kinematics: for a point at angle theta, dx/dt is proportional to -z, so the two
            // hemispheres sweep in opposite screen directions under the same spin) - that reads
            // as half the stars spinning backwards.
            //
            // Which half, though, is the opposite choice from the globe's own near/far test: the
            // globe is a solid object *at* the origin, so its near/visible hemisphere is the one
            // facing the camera (rotated.z > 0, per isObscuredByGlobe). The star field is a
            // backdrop conceptually surrounding the whole scene at effectively infinite radius -
            // the camera sits outside the globe looking toward it (toward -z), so the sky it can
            // actually see is the far side, in the direction it's looking and extending past the
            // globe (rotated.z < 0). The near hemisphere (z > 0) is the sky behind the camera's
            // head, never in view. Stars simply fade out at the z=0 boundary and different ones
            // fade in on the other side as the view turns, same as a real sky.
            val starFieldRadiusPx = sqrt(size.width * size.width + size.height * size.height) / 2f
            val twinkleBase = (currentFrameTime % 20_000L) / 1000f
            // No sky behind a flat map - the backdrop goes plain black, fading out across the unwrap
            // rather than cutting at some point during it.
            val starAlpha = 1f - morph
            if (starAlpha > 0.01f) stars.forEach { star ->
                val rotated = orientation.rotate(star.position)
                if (rotated.z >= 0f) return@forEach
                val starScreen = Offset(center.x + rotated.x * starFieldRadiusPx, center.y - rotated.y * starFieldRadiusPx)
                // Belt-and-suspenders: the opaque ocean fill drawn afterward already covers any
                // star that lands within the globe's silhouette on a normal frame, but during a
                // fast drag a star's position can visibly cross the limb between one frame and
                // the next, reading as a brief flash "in front of" the globe. Skipping it here
                // removes that ambiguity regardless of draw order.
                if ((starScreen - globeCenter).getDistance() <= globeRadius) return@forEach
                val twinkle = (sin(twinkleBase + star.phase) * 0.15f + 0.85f).coerceIn(0f, 1f)
                drawCircle(
                    color = Color.White.copy(alpha = star.baseAlpha * twinkle * starAlpha),
                    radius = star.radiusDp.dp.toPx(),
                    center = starScreen
                )
            }

            // 0b. Photorealistic Earth, when it is available: ONE draw standing in for steps 1, 2,
            // 2b, 4-7, 10, 10c and 14 below. The rect covers the globe plus its halo, and the shader
            // decides per pixel whether it is looking at the sphere, the atmosphere around it, or
            // nothing. The vector steps it replaces are each gated on `vectorSurface` rather than
            // deleted, so the two renderers stay side by side and the fallback is always one flag
            // away.
            // The shader raymarches a sphere, so it has nothing to say about a flat map. Until the
            // map gets its own surface renderer, map mode falls back to the vector layers - which
            // already draw correctly through the shared projection above.
            val mapImagery = if (mapLayout != null) dayImage else null
            // While the mesh is running it owns the surface outright - the globe shader cannot draw
            // a half-unwrapped world, and the flat blit cannot either.
            val vectorSurface = if (morphing) false
                else if (mapLayout != null) mapImagery == null
                else earthShader == null
            // Steps that are the shape of a GLOBE rather than of the world: the atmosphere halo, the
            // ocean disc, the specular glint, the terminator lobes and the limb rim are all built
            // around globeCenter/globeRadius, so on a map they would draw a circle in the middle of
            // the rectangle. The map substitutes its own equivalents where it has one.
            val globeSurface = vectorSurface && mapLayout == null

            // Land, ice, water and borders are all baked into the morph texture, so drawing them as
            // vectors as well during the transition paints a second, un-morphed copy of the world
            // over the deforming one - which is the doubling that showed up in vector mode and not
            // under the shader, where these lists are already empty because the imagery replaces
            // them. The graticule is not baked, so it keeps drawing and simply fades with the rest
            // of the overlay.
            val surfaceLayers = !morphing

            // Borders are a special case among the surface layers. In vector mode they are baked
            // INTO the morph texture, so drawing them again during the transition would paint a
            // second, un-morphed copy over it. Under imagery nothing bakes them, so there is no
            // conflict and they can simply keep drawing - which is why they were visible through the
            // warp in one mode and not the other. Now they are visible in both.
            val bordersBakedIntoMorph = morphing && !photorealisticEarth
            val drawBorders = showCountryBorders && !bordersBakedIntoMorph
            // The shader draws only while the globe is STEADY. It raymarches a sphere, so it has
            // nothing to say about a flat map - and nothing to say about a half-unwrapped one
            // either, which this was missing: left running through the transition it kept painting a
            // full, un-morphed globe underneath the mesh. That is the whole globe that stayed on
            // screen behind the flat map, and the Earth the far side appeared to open and close in
            // front of - it was never translucent, there were simply two of it.
            // The SDK_INT check is redundant at runtime - createEarthShader already returns null
            // below API 33, so earthShader is non-null only where RuntimeShader exists. It is here
            // because lint cannot trace that invariant across the remember/LaunchedEffect that
            // assigns it, and reports brushFor as an unguarded API 33 call (breaking lintRelease).
            // Making the guard explicit keeps lint usable as a signal and states the constraint
            // where a reader of this draw loop will actually see it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                earthShader?.takeIf { mapLayout == null && !morphing }?.let { shader ->
                    val extent = globeRadius * EARTH_SHADER_RIM_EXTENT
                    drawRect(
                        brush = shader.brushFor(globeCenter, globeRadius, sunDir, orientation),
                        topLeft = Offset(globeCenter.x - extent, globeCenter.y - extent),
                        size = Size(extent * 2f, extent * 2f)
                    )
                }
            }

            // 1. Atmosphere glow, drawn behind everything, extending past the globe silhouette
            if (globeSurface) drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ATMOSPHERE_COLOR.copy(alpha = 0.35f), ATMOSPHERE_COLOR.copy(alpha = 0f)),
                    center = globeCenter,
                    radius = globeRadius * 1.18f
                ),
                radius = globeRadius * 1.18f,
                center = globeCenter
            )

            // 2. Ocean sphere. A radial gradient standing in for lighting, centred on the projected
            // subsolar point - the diffuse peak (the specular glint below belongs at the sun/viewer
            // half-vector instead, and is a different thing). Pulled in to 0.75 of the way out
            // rather than sitting exactly on the subsolar point, which with the sun near the limb
            // would leave the globe almost entirely in the gradient's dark end.
            //
            // Being screen-space, this has one failure mode: with the sun *behind* the globe its
            // projection lands near the middle of the disc, which put the brightest patch of ocean
            // squarely on the night side.
            //
            // Rather than abandon the gradient - it reads well on the lit side, which is where it
            // does its job - its contrast is faded out as the view turns away from the sun. Both
            // stops converge on their own midpoint, so the highlight dissolves into a flat tone
            // while average brightness is unchanged; there is no dimming or brightening as it goes,
            // only the gradient flattening. subsolar.z is exactly the right control: +1 with the sun
            // directly behind the camera, 0 with the terminator through the middle of the disc,
            // negative once the lit side has turned away. Full strength is held for the whole of
            // z >= 0, so the lit-side appearance is untouched.
            val subsolar = orientation.rotate(sunDir)
            val gradientStrength = ((subsolar.z + OCEAN_GRADIENT_FADE_DEPTH) / OCEAN_GRADIENT_FADE_DEPTH)
                .coerceIn(0f, 1f)
            val oceanMid = lerp(OCEAN_COLOR_NEAR, OCEAN_COLOR_FAR, 0.5f)
            // The map's ocean is the whole world rectangle rather than a disc, and flat rather than
            // gradient-lit - a screen-space radial highlight has no meaning once the world is not a
            // sphere on screen.
            // The unwrap itself. Vertices lerp between the two projections - a forward mapping, so
            // interpolation is just a lerp - and the triangles are drawn far-to-near so the far
            // hemisphere is covered by the near one until they genuinely separate. See
            // EarthSurfaceMesh for why this is drawVertices and not drawBitmapMesh.
            val morphTexture = morphTextureBitmap
            if (morphing && morphTexture != null) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawEarthMesh(
                        buffers = meshBuffers,
                        bitmap = morphTexture,
                        dayTint = meshDayTint,
                        nightBitmap = if (photorealisticEarth) earthTextures?.night else null,
                        nightTint = meshNightTint,
                        morph = morph,
                        sunDir = sunDir,
                        // The layout itself rather than a per-point projection: the mesh has to place
                        // its own seam relative to the map centre, which it cannot do if all it can
                        // ask is where an individual point goes. See its strip note.
                        layout = flatLayout,
                        globeProject = { v ->
                            projectRotated(orientation.rotate(v), zoom, panOffset, center, baseRadiusPx)
                        },
                        globeDepth = { v -> orientation.rotate(v).z }
                    )
                }
            }

            if (mapLayout != null && !morphing) {
                mapLayout.copyOffsets().forEach { dx ->
                    // Positioned from worldLeft/worldTop, not from the viewport centre: the imagery
                    // is the one layer drawn as a rectangle rather than projected point by point, so
                    // it is the one that has to be told where the world actually sits. Anchoring it
                    // to the screen instead left it stationary while every other layer scrolled.
                    if (mapImagery != null) {
                        drawImage(
                            image = mapImagery,
                            dstOffset = IntOffset(
                                (mapLayout.worldLeft + dx).roundToInt(),
                                mapLayout.worldTop.roundToInt()
                            ),
                            dstSize = IntSize(mapLayout.width.roundToInt(), mapLayout.height.roundToInt())
                        )
                    } else {
                        drawRect(
                            color = oceanMid,
                            topLeft = Offset(mapLayout.worldLeft + dx, mapLayout.worldTop),
                            size = Size(mapLayout.width, mapLayout.height)
                        )
                    }
                }
            }
            if (globeSurface) drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerp(oceanMid, OCEAN_COLOR_NEAR, gradientStrength),
                        lerp(oceanMid, OCEAN_COLOR_FAR, gradientStrength)
                    ),
                    center = Offset(
                        globeCenter.x + subsolar.x * globeRadius * 0.75f,
                        globeCenter.y - subsolar.y * globeRadius * 0.75f
                    ),
                    radius = globeRadius * 1.4f
                ),
                radius = globeRadius,
                center = globeCenter
            )

            // 2b. Sun glint: the specular highlight where sunlight reflects off the ocean back at
            // the viewer.
            //
            // Its position is NOT the subsolar point. Dragging orbits the camera around a fixed
            // sun, so the view direction and the sun direction are independent, and a reflection
            // only reaches the eye from the point whose surface normal bisects the two - the
            // half-vector between "towards the sun" and "towards the viewer". The subsolar point is
            // where *diffuse* lighting peaks (that is what the ocean gradient above uses); the two
            // coincide only with the sun directly behind the camera, and sit 45 degrees apart with
            // the sun off to one side.
            //
            // Under orthographic projection the viewer direction is simply +z in view space, so the
            // half-vector is subsolar + (0,0,1), normalised. Its z is 1 + subsolar.z, which is never
            // negative - the reflection point is always on the near hemisphere, sliding out to the
            // limb as the sun swings behind the globe.
            //
            // Drawn here, before land, precisely so the land layers paint over it - the glint then
            // appears on water only, for free, instead of needing to be clipped to coastlines.
            val halfLength = sqrt(
                subsolar.x * subsolar.x + subsolar.y * subsolar.y +
                    (subsolar.z + 1f) * (subsolar.z + 1f)
            )
            if (globeSurface && halfLength > 1e-4f) {
                val glintNormal = Vec3(
                    subsolar.x / halfLength,
                    subsolar.y / halfLength,
                    (subsolar.z + 1f) / halfLength
                )
                val glintScreen = projectRotated(glintNormal, zoom, panOffset, center, baseRadiusPx)
                // Fades as the sun swings behind the globe, where the reflection becomes grazing and
                // its point is drifting onto the terminator. Squared so it falls off decisively
                // rather than leaving a highlight hanging over the night side.
                val glintFacing = (1f + subsolar.z) / 2f
                val glintStrength = glintFacing * glintFacing
                // Two concentric layers: a broad, very faint sheen with a tight bright core inside
                // it. One wide gradient on its own was too diffuse to read as a reflection - it
                // just lightened half the ocean.
                val glowRadius = globeRadius * 0.45f
                val coreRadius = globeRadius * 0.14f
                clipPath(Path().apply { addOval(Rect(center = globeCenter, radius = globeRadius)) }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                SUN_GLINT_COLOR.copy(alpha = SUN_GLINT_GLOW_ALPHA * glintStrength),
                                SUN_GLINT_COLOR.copy(alpha = 0f)
                            ),
                            center = glintScreen,
                            radius = glowRadius
                        ),
                        radius = glowRadius,
                        center = glintScreen
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                SUN_GLINT_COLOR.copy(alpha = SUN_GLINT_CORE_ALPHA * glintStrength),
                                SUN_GLINT_COLOR.copy(alpha = 0f)
                            ),
                            center = glintScreen,
                            radius = coreRadius
                        ),
                        radius = coreRadius,
                        center = glintScreen
                    )
                }
            }

            // No fade across the transition any more. The overlays used to be dissolved through the
            // middle of it purely to hide them jumping between projections; now they interpolate
            // along with the surface (see morphTransform), so they can simply stay on screen.

            // 3. Graticule, clipped to the front-facing hemisphere per segment
            if (showGraticule) graticule.forEach { ring ->
                if (!onScreen(ring.cap)) return@forEach
                subpathsOf(ring.points, ring.isClosed)
                    .forEach { path ->
                        drawPath(path, color = GRATICULE_COLOR, style = Stroke(width = 0.5.dp.toPx()))
                    }
            }

            // 4. Continent fills, clipped to the front-facing hemisphere per segment. Each
            // polygon's rings come from Natural Earth's land layer, which are already closed
            // by construction (no stitching/guessing involved), so filling is always safe.
            if (surfaceLayers) landPolygons.forEach { polygon ->
                polygonPathsOf(polygon)?.let { (fill, outline) ->
                    drawPath(fill, color = CONTINENT_FILL)
                    drawPath(outline, color = CONTINENT_STROKE, style = Stroke(width = 1.dp.toPx()))
                }
            }

            // 5. Glaciated areas (ice sheets/major glaciers), painted over the continents
            if (surfaceLayers) glaciers.forEach { polygon ->
                polygonPathsOf(polygon)?.let { (fill, outline) ->
                    drawPath(fill, color = GLACIER_FILL)
                    drawPath(outline, color = GLACIER_STROKE, style = Stroke(width = 1.dp.toPx()))
                }
            }

            // 6. Lakes, painted over land like glaciers - always on (small file, low point count,
            // no clutter story, same treatment as glaciers).
            if (surfaceLayers) lakes.forEach { polygon ->
                polygonPathsOf(polygon)?.let { (fill, outline) ->
                    drawPath(fill, color = LAKE_FILL)
                    drawPath(outline, color = LAKE_STROKE, style = Stroke(width = 1.dp.toPx()))
                }
            }

            // 7. Rivers - open chains (not closed polygons), same clipping path as graticule
            // meridians. Always on, like lakes.
            if (surfaceLayers) rivers.forEach { ring ->
                if (!onScreen(ring.cap)) return@forEach
                subpathsOf(ring.points, ring.isClosed)
                    .forEach { path -> drawPath(path, color = RIVER_COLOR, style = Stroke(width = 1.dp.toPx())) }
            }

            // 8. Country borders - open chains from a dedicated boundary-line layer, not the
            // countries polygon's own ring edges, so a shared border between two neighbors is
            // drawn once rather than twice (once per country's own edge). Off by default - see
            // TrackerViewModel.showCountryBorders.
            if (drawBorders) {
                borders.forEach { ring ->
                    if (!onScreen(ring.cap)) return@forEach
                    subpathsOf(ring.points, ring.isClosed)
                        .forEach { path -> drawPath(path, color = BORDER_COLOR, style = Stroke(width = 1.dp.toPx())) }
                }
            }

            // 9. Cloud cover: contour polygons traced with marching squares from NASA GIBS'
            // daily true-color mosaic, refreshed at most once a day (see CloudRepository).
            // Adjacent cloudy grid cells share exact edges, so a connected cloud mass renders as
            // one seamless shape rather than a field of separate blobs. Two density bands (a
            // lighter, larger halo and a smaller, denser core) are layered for some depth. Each
            // contour is clipped to the front-facing hemisphere exactly like land/graticule rings.
            cloudContours.forEach { contour ->
                if (!onScreen(contour.cap)) return@forEach
                subpathsOf(contour.points, true)
                    .forEach { path -> drawPath(path, color = CLOUD_COLOR.copy(alpha = contour.alpha)) }
            }

            // 10. Day/night shading - dims the ocean/land/glacier/water/border/cloud layers already
            // drawn, but not the satellites, atmosphere, or star field drawn before/after it.
            //
            // Drawn shallowest-first so the bands nest: each ring encloses a smaller region than
            // the one before, and the overlaps build a graded terminator instead of a hard step.
            // Skipped under the shader, which crossfades day into night per pixel from the sun angle
            // - a continuous gradient rather than ten stacked translucent fills.

            // The map's terminator, sampled per texel into small bitmaps and blitted - see
            // MapLighting. Both modes go through the same mechanism now; the only difference is what
            // is being shaded, imagery crossfading into a night photograph versus flat vector fills
            // being dimmed and cooled.
            if (mapLayout != null && mapNight != null) {
                mapLayout.copyOffsets().forEach { dx ->
                    val dstOffset = IntOffset(
                        (mapLayout.worldLeft + dx).roundToInt(),
                        mapLayout.worldTop.roundToInt()
                    )
                    val dstSize = IntSize(
                        mapLayout.width.roundToInt(),
                        mapLayout.height.roundToInt()
                    )

                    // Black at the alpha that brings the day imagery down to its lit level, or the
                    // warm-to-cool tint for vector fills. Never a tinted colour over imagery - that
                    // is what turned the night side purple.
                    drawImage(image = mapNight.darkening, dstOffset = dstOffset, dstSize = dstSize)

                    // City lights, composited additively because the night imagery is black wherever
                    // nothing is lit, so adding it leaves the darkened day imagery intact rather than
                    // flattening it. The mask must be applied inside a layer bounded to THIS copy - a
                    // DstIn spanning the canvas would erase what neighbouring copies already drew.
                    val lightsImage = nightImage
                    val mask = mapNight.nightMask
                    if (lightsImage != null && mask != null) {
                        val bounds = Rect(
                            dstOffset.x.toFloat(),
                            dstOffset.y.toFloat(),
                            (dstOffset.x + dstSize.width).toFloat(),
                            (dstOffset.y + dstSize.height).toFloat()
                        )
                        drawContext.canvas.saveLayer(
                            bounds,
                            ComposePaint().apply { blendMode = BlendMode.Plus }
                        )
                        drawImage(image = lightsImage, dstOffset = dstOffset, dstSize = dstSize)
                        drawImage(
                            image = mask,
                            dstOffset = dstOffset,
                            dstSize = dstSize,
                            blendMode = BlendMode.DstIn
                        )
                        drawContext.canvas.restore()
                    }
                }
            }

            if (globeSurface) drawIntoCanvas { canvas ->
                nightRings.forEachIndexed { index, ring ->
                    val band = nightBandPaths[index]
                    // The next ring in is this band's inner edge; the innermost band has none.
                    val fill = buildShadowBandFill(
                        outer = ring,
                        inner = nightRings.getOrNull(index + 1),
                        sunDir = sunDir,
                        orientation = orientation,
                        zoom = zoom,
                        panOffset = panOffset,
                        center = center,
                        baseRadiusPx = baseRadiusPx,
                        globeCenter = globeCenter,
                        globeRadius = globeRadius,
                        scratch = globeScratch,
                        into = band
                    )
                    if (fill == ShadowFill.NONE) return@forEachIndexed
                    nightBandPaint.color = NIGHT_BAND_COLORS[index]
                    if (fill == ShadowFill.WHOLE_DISC) {
                        canvas.drawCircle(globeCenter, globeRadius + SHADOW_BLEED_PX, nightBandPaint)
                    } else {
                        canvas.drawPath(band, nightBandPaint)
                    }
                }
            }

            // 10c. City lights on the night side, over the darkened hemisphere.
            //
            // The day/night test needs no rotation: city positions and sunDir are both already in
            // Earth-fixed space, so their dot product is orientation-independent. Only the
            // projection below cares how the globe is currently turned.
            if (cityLights.isNotEmpty()) {
                val zoomFraction = ((zoom - MIN_ZOOM) / (2.5f - MIN_ZOOM)).coerceIn(0f, 1f)
                val minMagnitude = CITY_LIGHT_MIN_MAGNITUDE_AT_MIN_ZOOM * (1f - zoomFraction)
                cityLights.forEach { light ->
                    if (light.magnitude < minMagnitude) return@forEach
                    val illumination = dot(light.position, sunDir)
                    if (illumination >= 0f) return@forEach
                    // Rotated ONCE. The occlusion test, the limb dimming and the screen position all
                    // come off this; each used to rotate the light again for itself, three times per
                    // light per frame across a couple of hundred of them.
                    val rotated = orientation.rotate(light.position)
                    if ((layerLayout == null || !farSideShowing) && isObscuredByGlobe(rotated)) return@forEach

                    // Ramped rather than switched on at illumination == 0, so the lights don't draw
                    // a hard edge of their own straight through the twilight gradient above.
                    val nightDepth = (-illumination / CITY_LIGHT_FADE_DEPTH).coerceIn(0f, 1f)
                    // Dimmed towards the silhouette too, where the surface is turning away and a
                    // full-brightness dot would read as floating off the edge. On a map nothing
                    // turns away, so every light sits at full facing.
                    val facing = if (layerLayout != null) 1f else 0.35f + 0.65f * rotated.z
                    // Brightness tracks size, so a megacity genuinely outshines a small city rather
                    // than the whole field landing at one value and reading as scattered confetti.
                    val coreAlpha = CITY_LIGHT_PEAK_ALPHA * nightDepth * facing * (0.3f + 0.7f * light.magnitude)
                    val screen = if (layerLayout == null) {
                        projectRotated(rotated, zoom, panOffset, center, baseRadiusPx)
                    } else {
                        screenOf(light.position)
                    }
                    val coreRadius = (0.45f + 1.0f * light.magnitude).dp.toPx() *
                        (0.55f + 0.45f * zoom.coerceAtMost(2.5f))
                    // Zoomed in, most of the field is scrolled off screen; the halo below is the
                    // furthest either draw reaches from the light itself.
                    val reach = coreRadius * 2.6f
                    if (screen.x < -reach || screen.x > size.width + reach ||
                        screen.y < -reach || screen.y > size.height + reach
                    ) return@forEach

                    // Two passes: a faint wide halo under a small bright core. A single flat disc is
                    // what made these read as stickers on the map - a light needs falloff.
                    drawCircle(
                        color = CITY_LIGHT_COLOR.copy(alpha = coreAlpha * 0.28f),
                        radius = coreRadius * 2.6f,
                        center = screen
                    )
                    drawCircle(
                        color = CITY_LIGHT_COLOR.copy(alpha = coreAlpha),
                        radius = coreRadius,
                        center = screen
                    )
                }
            }

            // 11. Active pass connector lines, drawn under the station/satellite markers. Every
            // currently-active pass gets its own independent line - if two ground stations both
            // see the same satellite right now (or one station sees several satellites at
            // once), each pairing draws separately here with no deduplication by satellite or
            // station, so overlapping visibilities just naturally produce multiple lines.
            val nowMillis = currentFrameTime
            calculatedPasses.forEach { pass ->
                if (nowMillis in pass.aosMillis..pass.losMillis) {
                    val station = availableStations.find { it.code == pass.groundStationCode }
                    val satCoords = viewModel.getSatellite3DPosition(pass.noradId, nowMillis)
                    if (station != null && satCoords != null) {
                        val stationV = latLonDegToUnitSphere(station.latitude, station.longitude)
                        val (sx, sy, sz) = satCoords
                        val satV = Vec3(sx.toFloat(), sy.toFloat(), sz.toFloat())
                        // Sampled clipping rather than a flat z=0 plane cut: the satellite
                        // endpoint is elevated (radius > 1, way more so for GEO), so a point can
                        // have z < 0 yet sit entirely outside the globe's silhouette - not
                        // obscured at all. Reuses the exact same occlusion test as the markers.
                        val stationRotated = orientation.rotate(stationV)
                        val satRotated = orientation.rotate(satV)
                        // A linear gradient anchored on the full station->satellite screen
                        // positions (not per-run) so the color at any point along the line
                        // reflects its true position between the two ends, even where an
                        // occluded middle section is cut out into separate visible runs.
                        val stationScreen = screenOf(stationV)
                        val satScreen = screenOf(satV)
                        val lineBrush = Brush.linearGradient(
                            colors = listOf(
                                getStationColor(station.code, stationColorOverrides),
                                getSatelliteColor(pass.noradId, satelliteColorOverrides)
                            ),
                            start = stationScreen,
                            end = satScreen
                        )
                        if (layerLayout != null) {
                            // On a map the connector is station-to-subsatellite-point, so it is
                            // sampled in world space and seam-split like any other line. The globe's
                            // occlusion sampling below has nothing to do here - a map hides nothing.
                            val samples = ArrayList<Vec3>(CONNECTOR_MAP_SAMPLES)
                            for (i in 0 until CONNECTOR_MAP_SAMPLES) {
                                val t = i / (CONNECTOR_MAP_SAMPLES - 1f)
                                samples.add(
                                    Vec3(
                                        stationV.x + (satV.x - stationV.x) * t,
                                        stationV.y + (satV.y - stationV.y) * t,
                                        stationV.z + (satV.z - stationV.z) * t
                                    )
                                )
                            }
                            subpathsOf(samples, false).forEach { path ->
                                drawPath(path, brush = lineBrush, style = Stroke(width = 1.5.dp.toPx()))
                            }
                        } else {
                            visibleSegmentRuns(stationRotated, satRotated).forEach { run ->
                                val path = Path()
                                run.forEachIndexed { i, p ->
                                    val screen = projectRotated(p, zoom, panOffset, center, baseRadiusPx)
                                    if (i == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
                                }
                                drawPath(path, brush = lineBrush, style = Stroke(width = 1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }

            // 12. Ground station markers (triangles) with station-code labels
            availableStations.forEach { station ->
                if (station.code in activeStationCodes) {
                    val v = latLonDegToUnitSphere(station.latitude, station.longitude)
                    if (!hidden(v)) {
                        val screen = screenOf(v)
                        drawPath(triangleAt(screen, 6.dp.toPx()), color = getStationColor(station.code, stationColorOverrides))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = station.code,
                            topLeft = Offset(screen.x + 9.dp.toPx(), screen.y - 8.dp.toPx()),
                            style = LABEL_TEXT_STYLE
                        )
                    }
                }
            }

            // 12b. Country name labels - grouped with station/satellite labels (not with the
            // border-line step above) specifically so they draw AFTER the night terminator and
            // stay legible on the night side too, matching the treatment station/satellite labels
            // already get. LOD-gated per MIN_COUNTRY_LABEL_SIZE_DP/MAX_COUNTRY_LABELS above.
            if (showCountryLabels && countryLabels.isNotEmpty()) {
                val candidates = ArrayList<CountryLabelCandidate>()
                countryLabels.forEach { label ->
                    if (hidden(label.anchor)) return@forEach
                    val screen = screenOf(label.anchor)
                    // isObscuredByGlobe only tests front-vs-back hemisphere, not whether a
                    // front-hemisphere point is actually within the current viewport - at high
                    // zoom, most of the globe is still "front-facing" even though it's scrolled
                    // well off screen. Without this bounds check, zooming into one region still
                    // pools every not-obscured country worldwide as a candidate, and the
                    // top-N-by-real-world-size cap below then favors objectively huge countries
                    // (Russia, Algeria, Kazakhstan, ...) wherever they happen to sit on the front
                    // hemisphere, crowding out smaller countries that are actually on screen
                    // (this was the exact bug: zoomed into Europe, only Norway/Sweden/Morocco -
                    // all large by real-world extent - survived the cap over France/Germany/Spain).
                    if (screen.x < 0f || screen.x > size.width || screen.y < 0f || screen.y > size.height) return@forEach
                    val projectedSizePx = baseRadiusPx * zoom * Math.toRadians(label.extentDeg.toDouble()).toFloat()
                    if (projectedSizePx < MIN_COUNTRY_LABEL_SIZE_DP.dp.toPx()) return@forEach
                    candidates.add(CountryLabelCandidate(name = label.name, screen = screen, sizePx = projectedSizePx))
                }
                // Sort-then-cap so the cap (if ever hit) hides the smallest/least-significant
                // labels first, rather than an arbitrary subset in source-file order.
                candidates.sortByDescending { it.sizePx }
                candidates.take(MAX_COUNTRY_LABELS).forEach { candidate ->
                    drawText(
                        textMeasurer = textMeasurer,
                        text = candidate.name,
                        topLeft = Offset(candidate.screen.x - 20.dp.toPx(), candidate.screen.y - 5.dp.toPx()),
                        style = COUNTRY_LABEL_TEXT_STYLE
                    )
                }
            }

            // 13. Satellite trajectories, then markers with name labels. Trajectory drawn first so
            // the marker dot sits visually on top of the line rather than under it.
            trackedSats.forEach { sat ->
                val satColor = getSatelliteColor(sat.noradId, satelliteColorOverrides)
                val trajectoryPoints = trajectoriesByNoradId[sat.noradId].orEmpty()
                if (trajectoryPoints.size >= 2) {
                    val minOffset = trajectoryPoints.first().offsetMillis
                    val maxOffset = trajectoryPoints.last().offsetMillis
                    // Kept in world space rather than pre-rotated, because the map needs the
                    // unrotated point to recover its lat/lon. In map mode this list IS the ground
                    // track: MapLayout.project normalises away the altitude, so the orbital path and
                    // its sub-satellite trace are the same samples seen two ways, with no second
                    // pass through SGP4.
                    val worldTrajectory = trajectoryPoints.map { p ->
                        Vec3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                    }
                    val rotatedTrajectory = worldTrajectory.map { orientation.rotate(it) }

                    // Trailing (past) arc: discrete dots, one per sample point, reading as a
                    // dotted line at the sample spacing. Deliberately NOT a dashPathEffect on the
                    // stroked path below: that path is drawn one drawPath call per sample pair,
                    // and a dash pattern's phase resets on every call - producing a zoom- and
                    // segment-length-dependent stipple that shimmers during interaction instead
                    // of a stable dotted line. Dots also keep the exact per-point alpha fade.
                    // Occlusion: isObscuredByGlobe is the same elevated-point test the satellite
                    // marker itself uses, so dots and the marker always agree on visibility.
                    val dotRadius = 1.5.dp.toPx()
                    for (i in worldTrajectory.indices) {
                        if (trajectoryPoints[i].offsetMillis > 0L) break // ordered past -> future
                        val p = worldTrajectory[i]
                        if (hidden(p)) continue
                        drawCircle(
                            color = satColor.copy(alpha = trajectoryAlpha(trajectoryPoints[i].offsetMillis, minOffset, maxOffset)),
                            radius = dotRadius,
                            center = screenOf(p)
                        )
                    }

                    // Leading (future) arc: solid stroked line. Starts from the last non-future
                    // point (the segment straddling "now" is included) so the line meets the
                    // dotted trail without a gap at the satellite's current position.
                    for (i in 0 until worldTrajectory.size - 1) {
                        if (trajectoryPoints[i + 1].offsetMillis <= 0L) continue
                        val alphaStart = trajectoryAlpha(trajectoryPoints[i].offsetMillis, minOffset, maxOffset)
                        val alphaEnd = trajectoryAlpha(trajectoryPoints[i + 1].offsetMillis, minOffset, maxOffset)

                        if (layerLayout != null) {
                            // Same early-unwrap cull the trailing dots get from hidden(), or the
                            // leading arc alone would sweep across the front of the globe while the
                            // dotted trail behind it was still correctly hidden.
                            if (hidden(worldTrajectory[i]) || hidden(worldTrajectory[i + 1])) continue
                            val a = screenOf(worldTrajectory[i])
                            val b = screenOf(worldTrajectory[i + 1])
                            // The one segment per orbit that straddles the antimeridian is dropped
                            // rather than split: at this sampling density it is a single missing
                            // step out of a hundred, invisible, and far cheaper than reconstructing
                            // where the track leaves one edge and rejoins the other.
                            //
                            // Straddling is a property of where the two ends are on the MAP, not of
                            // where the blend has currently put them: part way through the unwrap
                            // the seam segment's ends are still close together on screen, so a test
                            // on the blended positions only catches it once they have separated -
                            // after it has spent half the animation drawn across the whole view.
                            val seamSpan = if (morphTransform == null) abs(b.x - a.x) else abs(
                                layerLayout.project(worldTrajectory[i]).x -
                                    layerLayout.project(worldTrajectory[i + 1]).x
                            )
                            if (seamSpan > layerLayout.width / 2f) continue
                            // One flat colour rather than a gradient along the segment. A gradient
                            // per segment meant a brush and a native shader object for each of the
                            // hundred-odd steps of every satellite's trail, every frame; the alpha
                            // only moves by about a hundredth of the trail's whole fade across one
                            // step, so the difference between ramping it and taking its midpoint is
                            // not something the eye has any way to resolve.
                            drawLine(
                                color = satColor.copy(alpha = (alphaStart + alphaEnd) / 2f),
                                start = a,
                                end = b,
                                strokeWidth = 1.5.dp.toPx()
                            )
                            continue
                        }

                        // visibleSegmentRuns (not buildVisibleSubpaths) because trajectory points
                        // are elevated above the surface just like the satellite marker itself -
                        // the same reasoning as the active-pass connector line above.
                        val runs = visibleSegmentRuns(rotatedTrajectory[i], rotatedTrajectory[i + 1])
                        if (runs.isEmpty()) continue
                        runs.forEach { run ->
                            val path = globeScratch.obtainPath()
                            run.forEachIndexed { idx, p ->
                                val screen = projectRotated(p, zoom, panOffset, center, baseRadiusPx)
                                if (idx == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
                            }
                            // Flat colour per run, for the same reason as the map's segments above -
                            // and it drops the two extra projections the gradient's endpoints needed.
                            drawPath(
                                path,
                                color = satColor.copy(alpha = (alphaStart + alphaEnd) / 2f),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                }

                val rawCoords = viewModel.getSatellite3DPosition(sat.noradId, currentFrameTime)
                if (rawCoords != null) {
                    val (x, y, z) = rawCoords
                    val v = Vec3(x.toFloat(), y.toFloat(), z.toFloat())
                    val screen = screenOf(v)

                    if (!hidden(v)) {
                        drawCircle(color = satColor, radius = 6.dp.toPx(), center = screen)
                        drawText(
                            textMeasurer = textMeasurer,
                            text = viewModel.getSatelliteName(sat.noradId),
                            topLeft = Offset(screen.x + 9.dp.toPx(), screen.y - 8.dp.toPx()),
                            style = LABEL_TEXT_STYLE
                        )
                    } else {
                        drawCircle(color = satColor.copy(alpha = 0.15f), radius = 4.dp.toPx(), center = screen)
                    }
                }
            }



            // 14. Atmosphere rim at the exact silhouette edge, lit by the sun rather than drawn as
            // one uniform ring: full blue where the limb faces daylight, fading to nothing as it
            // approaches the terminator, and absent entirely along the night side - the atmosphere
            // is only visible where there is sunlight passing through it to scatter.
            //
            // A point of the limb sits at (cos theta, sin theta, 0) in view space, so rotating it
            // back into Earth-fixed space and dotting with sunDir gives that point's illumination.
            //
            // Skipped under the shader, which produces the same sun-dependent rim as a continuous
            // Fresnel term - the thing these 160 arcs are approximating.
            val rimStroke = Stroke(width = 2.dp.toPx())
            val rimBounds = Rect(center = globeCenter, radius = globeRadius)
            val rimSweep = 360f / LIMB_SEGMENTS
            // How far past the viewport an arc's midpoint can sit and still have part of the arc on
            // screen: its own chord, plus the stroke it is drawn with.
            val rimMargin = globeRadius * TWO_PI_F / LIMB_SEGMENTS + 2.dp.toPx()
            for (i in 0 until LIMB_SEGMENTS) {
                if (!globeSurface) break
                // Half-segment offset samples the middle of each arc rather than its leading edge,
                // so the fade stays centred on the segment it applies to.
                val theta = TWO_PI_F * (i + 0.5f) / LIMB_SEGMENTS
                val cosTheta = cos(theta)
                val sinTheta = sin(theta)
                // Zoomed in, most of the rim is off screen. Rejecting those arcs here skips both the
                // stroke and its tessellation; drawArc would otherwise build the geometry first and
                // discover it was clipped away afterwards.
                val rimX = globeCenter.x + cosTheta * globeRadius
                val rimY = globeCenter.y - sinTheta * globeRadius
                if (rimX < -rimMargin || rimX > size.width + rimMargin ||
                    rimY < -rimMargin || rimY > size.height + rimMargin
                ) continue
                // dot(inverse * limb, sun) is the same number as dot(limb, orientation * sun), and
                // the latter is already to hand as `subsolar` - which turns a Vec3 allocation and a
                // quaternion rotation per segment into two multiplies. The limb has no z component.
                val illumination = cosTheta * subsolar.x + sinTheta * subsolar.y

                // Ramps from nothing at the terminator to the full rim once well into daylight.
                // Clamping at zero is what keeps the night limb clear rather than merely dim.
                val ramp = (illumination / LIMB_FADE_DEPTH).coerceIn(0f, 1f)
                if (ramp <= 0f) continue
                // Smoothstep rather than the raw linear ramp: a linear fade still has a corner at
                // each end, and a corner in brightness across adjacent arcs is exactly what the eye
                // picks out as a visible edge.
                val strength = ramp * ramp * (3f - 2f * ramp)

                drawArc(
                    color = ATMOSPHERE_COLOR.copy(alpha = 0.55f * strength),
                    // Canvas angles run clockwise from 3 o'clock while theta is the counter-
                    // clockwise view-space angle, so the start is negated to keep the two in step.
                    startAngle = -Math.toDegrees(theta.toDouble()).toFloat() - rimSweep / 2f,
                    sweepAngle = rimSweep,
                    useCenter = false,
                    topLeft = Offset(rimBounds.left, rimBounds.top),
                    size = Size(rimBounds.width, rimBounds.height),
                    style = rimStroke
                )
            }
        }

        // In fullscreen MainAppShell drops the Scaffold inset so the globe can reach the physical
        // edges of the display - but nothing ever hides the system bars, so without this the
        // controls drew underneath them. The exit button (top-end, 16dp padding, 56dp) had its top
        // overlapped by the status bar, which consumes touches in its own region, and the bottom
        // controls sat under the navigation bar. Applied to the controls only, never to the canvas,
        // so the globe still reaches the edges. Not applied outside fullscreen, where the Scaffold
        // inset already positions everything.
        val controlInsets = if (is3DFullscreen) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        } else {
            Modifier
        }

        // 2D/3D switch, top-LEFT - opposite the fullscreen button, and the last free corner. A
        // segmented pair rather than an icon button because the two states are peers, not on/off:
        // an icon would have to imply "you are in X" or "tap for Y" and either reading is ambiguous
        // when both options are equally valid places to be.
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(animationSpec = tween(CONTROLS_FADE_IN_MS)),
            exit = fadeOut(animationSpec = tween(CONTROLS_FADE_OUT_MS)),
            modifier = controlInsets.align(Alignment.TopStart)
        ) {
            Surface(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.45f)
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    // Explicit order rather than enum declaration order: 2D reads left, 3D right.
                    listOf(GlobeViewMode.MAP, GlobeViewMode.GLOBE).forEach { mode ->
                        val selected = viewMode == mode
                        Surface(
                            onClick = { viewMode = mode },
                            shape = RoundedCornerShape(50),
                            color = if (selected) Color.White else Color.Transparent,
                            contentColor = if (selected) Color.Black else Color.White.copy(alpha = 0.75f)
                        ) {
                            Text(
                                text = if (mode == GlobeViewMode.MAP) "2D" else "3D",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen toggle, top-RIGHT - the only corner none of the other controls occupy. Hidden
        // along with everything else once the controls are dismissed in fullscreen, so the globe is
        // genuinely unobstructed; tapping anywhere brings it (and the rest) back.
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(animationSpec = tween(CONTROLS_FADE_IN_MS)),
            exit = fadeOut(animationSpec = tween(CONTROLS_FADE_OUT_MS)),
            modifier = controlInsets.align(Alignment.TopEnd)
        ) {
            Surface(
                onClick = { viewModel.set3DFullscreen(!is3DFullscreen) },
                modifier = Modifier
                    .padding(16.dp)
                    .size(56.dp),
                shape = CircleShape,
                // Brighter while active - together with the icon swap below, that's what makes the
                // toggle's state readable at a glance rather than having to infer it from whether
                // the nav bar happens to be on screen.
                color = Color.Black.copy(alpha = if (is3DFullscreen) 0.55f else 0.35f),
                contentColor = Color.White
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (is3DFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (is3DFullscreen) "Exit fullscreen" else "Enter fullscreen",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }

        // 3D view display settings (orbit lines + map detail), bottom-LEFT corner (the
        // north-up/reset pair owns bottom-right). Same plain-Surface pattern as those buttons -
        // see the comment on the Row below for why Surface rather than FilledIconButton; Surface
        // also consumes its own down event, which is what keeps
        // awaitFirstDown(requireUnconsumed = true) above from treating this tap as the start of a
        // globe drag.
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(animationSpec = tween(CONTROLS_FADE_IN_MS)),
            exit = fadeOut(animationSpec = tween(CONTROLS_FADE_OUT_MS)),
            modifier = controlInsets.align(Alignment.BottomStart)
        ) {
        Surface(
            onClick = { showTrajectorySettings = true },
            modifier = Modifier
                .padding(16.dp)
                .size(56.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "3D view settings",
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        }

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(animationSpec = tween(CONTROLS_FADE_IN_MS)),
            exit = fadeOut(animationSpec = tween(CONTROLS_FADE_OUT_MS)),
            modifier = controlInsets.align(Alignment.BottomEnd)
        ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            // Plain Surface(onClick=...) rather than FilledIconButton: FilledIconButton pins its
            // container to a fixed internal token size (40dp) *after* applying the caller's own
            // modifier, so a Modifier.size() passed in from here was silently overridden - the
            // button never actually grew, even though the reserved touch-target area did. Surface
            // has no such built-in size, so the size set here is really what renders.
            Surface(
                onClick = {
                    val startOrientation = orientation
                    viewAnimationJob?.cancel()
                    viewAnimationJob = coroutineScope.launch {
                        val targetOrientation = northUpOrientation(startOrientation)
                        animate(0f, 1f, animationSpec = tween(RESET_ANIMATION_MS, easing = FastOutSlowInEasing)) { value, _ ->
                            orientation = slerp(startOrientation, targetOrientation, value)
                        }
                    }
                },
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    // The Explore glyph's needle points to the upper-right by default (not
                    // straight up), which reads wrong for a "north up" action - rotate it back to
                    // vertical.
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = "Rotate north up",
                        modifier = Modifier.size(30.dp).rotate(-45f)
                    )
                }
            }

            Surface(
                onClick = {
                    val startOrientation = orientation
                    val startZoom = zoom
                    val startPan = panOffset
                    viewAnimationJob?.cancel()
                    viewAnimationJob = coroutineScope.launch {
                        animate(0f, 1f, animationSpec = tween(RESET_ANIMATION_MS, easing = FastOutSlowInEasing)) { value, _ ->
                            orientation = slerp(startOrientation, Quaternion.IDENTITY, value)
                            zoom = startZoom + (1f - startZoom) * value
                            panOffset = Offset(startPan.x * (1f - value), startPan.y * (1f - value))
                        }
                    }
                },
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset view",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
        }

        if (showTrajectorySettings) {
            TrajectorySettingsDialog(
                current = trajectoryConfig,
                showGraticule = showGraticule,
                onShowGraticuleChange = { viewModel.setShowGraticule(it) },
                showCountryBorders = showCountryBorders,
                onShowCountryBordersChange = { viewModel.setShowCountryBorders(it) },
                showCountryLabels = showCountryLabels,
                onShowCountryLabelsChange = { viewModel.setShowCountryLabels(it) },
                photorealisticEarth = photorealisticEarth,
                onPhotorealisticEarthChange = { viewModel.setPhotorealisticEarth(it) },
                onDismiss = { showTrajectorySettings = false },
                onConfirm = { config ->
                    viewModel.setTrajectoryConfig(config)
                    showTrajectorySettings = false
                }
            )
        }
    }
}

// Bounds for the duration inputs - generous enough for any real use (a full day of minutes, or
// ten complete orbits) while keeping the per-satellite SGP4 sample count bounded (see
// getSatelliteTrajectory's adaptive stepMillis).
private const val MAX_TRAJECTORY_MINUTES = 1440f
private const val MAX_TRAJECTORY_REVOLUTIONS = 10f

@Composable
private fun TrajectorySettingsDialog(
    current: TrajectoryConfig,
    showGraticule: Boolean,
    onShowGraticuleChange: (Boolean) -> Unit,
    showCountryBorders: Boolean,
    onShowCountryBordersChange: (Boolean) -> Unit,
    showCountryLabels: Boolean,
    onShowCountryLabelsChange: (Boolean) -> Unit,
    photorealisticEarth: Boolean,
    onPhotorealisticEarthChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (TrajectoryConfig) -> Unit
) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var pastText by remember { mutableStateOf(formatDurationValue(current.pastValue)) }
    var pastUnit by remember { mutableStateOf(current.pastUnit) }
    var futureText by remember { mutableStateOf(formatDurationValue(current.futureValue)) }
    var futureUnit by remember { mutableStateOf(current.futureUnit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("View Settings") },
        text = {
            // Scrollable, and not optionally so: AlertDialog gives its text slot a bounded height,
            // and once the rows below overflow it they are measured with no height left. A Text
            // obeys that and collapses to nothing, so the label vanishes - while a Switch applies
            // requiredSize internally, ignores the constraint, and keeps drawing at full size on
            // top of the row above it. That is what produced the reported "two unlabelled toggles
            // stacked on each other" on a device with a larger display size, and it gets easier to
            // hit with every setting added here.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "TRAJECTORIES",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show trajectories", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                TrajectoryDurationRow(
                    label = "Past (dotted)",
                    valueText = pastText,
                    onValueChange = { pastText = it },
                    unit = pastUnit,
                    onUnitChange = { pastUnit = it },
                    enabled = enabled
                )
                TrajectoryDurationRow(
                    label = "Future (solid)",
                    valueText = futureText,
                    onValueChange = { futureText = it },
                    unit = futureUnit,
                    onUnitChange = { futureUnit = it },
                    enabled = enabled
                )

                Text(
                    text = "Revolutions scale with each satellite's own orbital period. " +
                        "Limits: ${MAX_TRAJECTORY_MINUTES.toInt()} min / ${MAX_TRAJECTORY_REVOLUTIONS.toInt()} rev.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )

                HorizontalDivider()

                Text(
                    text = "MAP DETAIL",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
                // Commit immediately rather than waiting for this dialog's Apply button - unlike
                // the orbit-line fields above (which need batched validation before applying),
                // these are plain independent booleans with nothing to validate, so they follow
                // the same instant-commit convention every other toggle in the app already uses.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show lat/lon grid", modifier = Modifier.weight(1f))
                    Switch(checked = showGraticule, onCheckedChange = onShowGraticuleChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show country borders", modifier = Modifier.weight(1f))
                    Switch(checked = showCountryBorders, onCheckedChange = onShowCountryBordersChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show country names", modifier = Modifier.weight(1f))
                    Switch(checked = showCountryLabels, onCheckedChange = onShowCountryLabelsChange)
                }

                // Shown even where it cannot be switched on, with the reason: a row that silently
                // does not exist on older phones is harder to make sense of than one that says why.
                val shaderSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Photorealistic Earth",
                            color = if (shaderSupported) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            text = if (shaderSupported) {
                                "Satellite imagery. Uses more memory."
                            } else {
                                "Requires Android 13 or newer"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = photorealisticEarth && shaderSupported,
                        onCheckedChange = onPhotorealisticEarthChange,
                        enabled = shaderSupported
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    TrajectoryConfig(
                        enabled = enabled,
                        // Unparseable/non-positive input silently keeps the previous saved value
                        // rather than blocking the dialog on validation errors.
                        pastValue = parseDurationValue(pastText, pastUnit, current.pastValue),
                        pastUnit = pastUnit,
                        futureValue = parseDurationValue(futureText, futureUnit, current.futureValue),
                        futureUnit = futureUnit
                    )
                )
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TrajectoryDurationRow(
    label: String,
    valueText: String,
    onValueChange: (String) -> Unit,
    unit: TrajectoryDurationUnit,
    onUnitChange: (TrajectoryDurationUnit) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = valueText,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(90.dp)
            )
            FilterChip(
                selected = unit == TrajectoryDurationUnit.MINUTES,
                onClick = { onUnitChange(TrajectoryDurationUnit.MINUTES) },
                label = { Text("min") },
                enabled = enabled
            )
            FilterChip(
                selected = unit == TrajectoryDurationUnit.REVOLUTIONS,
                onClick = { onUnitChange(TrajectoryDurationUnit.REVOLUTIONS) },
                label = { Text("rev") },
                enabled = enabled
            )
        }
    }
}

private fun formatDurationValue(value: Float): String =
    if (value == floor(value)) value.toInt().toString() else value.toString()

private fun parseDurationValue(text: String, unit: TrajectoryDurationUnit, fallback: Float): Float {
    // Comma accepted as decimal separator - the numeric soft keyboard offers ',' not '.' on
    // comma-decimal-locale devices (German etc.).
    val parsed = text.trim().replace(',', '.').toFloatOrNull() ?: return fallback
    // Zero is valid (e.g. "0 min past, 2 rev future" for a leading-arc-only view) - only reject
    // negative input. Zero on both sides naturally draws nothing at all: getSatelliteTrajectory
    // then samples a single point at "now", and the renderer requires >=2 points to draw
    // anything, so 0/0 is equivalent to toggling the whole feature off without any extra code.
    if (parsed < 0f) return fallback
    val max = when (unit) {
        TrajectoryDurationUnit.MINUTES -> MAX_TRAJECTORY_MINUTES
        TrajectoryDurationUnit.REVOLUTIONS -> MAX_TRAJECTORY_REVOLUTIONS
    }
    return parsed.coerceAtMost(max)
}

private fun centroidOf(pointers: List<PointerInputChange>): Offset =
    pointers.map { it.position }.reduce { a, b -> a + b } / pointers.size.toFloat()

private fun averageSpread(pointers: List<PointerInputChange>, centroid: Offset): Float {
    if (pointers.isEmpty()) return 0f
    return pointers.map { (it.position - centroid).getDistance() }.average().toFloat()
}

// Screen-space angle of the vector between the first two pointers, for the twist gesture. Sorted
// by pointer id so the pair's order (and therefore the vector's direction) stays stable across
// events - `event.changes` ordering isn't guaranteed, and a swap would flip the vector 180
// degrees, reading as a huge spurious twist in one frame.
private fun twistAngleOf(pointers: List<PointerInputChange>): Float? {
    if (pointers.size < 2) return null
    val sorted = pointers.sortedBy { it.id.value }
    val v = sorted[1].position - sorted[0].position
    return atan2(v.y, v.x)
}

// Wraps an angle delta to [-PI, PI] so a finger pair crossing the atan2 discontinuity at +/-180
// degrees produces a small continuous delta instead of a near-full-turn jump.
private fun normalizeAngleRad(delta: Float): Float {
    var d = delta
    val twoPi = (2.0 * Math.PI).toFloat()
    while (d > Math.PI.toFloat()) d -= twoPi
    while (d < -Math.PI.toFloat()) d += twoPi
    return d
}

private fun triangleAt(center: Offset, radiusPx: Float): Path = Path().apply {
    moveTo(center.x, center.y - radiusPx)
    lineTo(center.x - radiusPx * 0.87f, center.y + radiusPx * 0.5f)
    lineTo(center.x + radiusPx * 0.87f, center.y + radiusPx * 0.5f)
    close()
}

// Direction-of-travel cue for a trajectory point: the trailing (past) arc fades in from near-
// invisible at its tail up to a moderate alpha at "now"; the leading (future) arc starts brighter
// right at "now" and fades out more gently over a longer window - deliberately more prominent
// than the trailing arc, since it's the part that previews upcoming ground-station passes.
private fun trajectoryAlpha(offsetMillis: Long, minOffsetMillis: Long, maxOffsetMillis: Long): Float {
    return if (offsetMillis <= 0L) {
        val t = if (minOffsetMillis == 0L) 1f else (1f - offsetMillis.toFloat() / minOffsetMillis.toFloat()).coerceIn(0f, 1f)
        0.08f + t * (0.55f - 0.08f)
    } else {
        val t = if (maxOffsetMillis == 0L) 0f else (offsetMillis.toFloat() / maxOffsetMillis.toFloat()).coerceIn(0f, 1f)
        0.75f - t * (0.75f - 0.3f)
    }
}
