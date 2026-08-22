package com.example.eps_sgtracker.ui

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import com.example.eps_sgtracker.data.EarthTextures
import com.example.eps_sgtracker.model.Vec3

/**
 * The whole lit Earth in one fragment shader.
 *
 * This replaces the ocean gradient, the continent/glacier/lake/river fills, the stacked terminator
 * bands, the city-light dots and the segmented limb rim - a few hundred CPU-side Path operations per
 * frame - with a single `drawRect`. Per pixel the GPU reconstructs the sphere normal, rotates it
 * into earth-fixed space, converts to lat/lon, samples the imagery and lights it.
 *
 * ## Conventions, which must stay in lockstep with GlobeMath/GeoMath
 *
 * The vector layers still draw over this, so any disagreement shows up immediately as country
 * borders sitting off the coastlines:
 *
 *  - **Cartesian**: `latLonDegToUnitSphere` uses `x = cos(lat)sin(lon)`, `y = sin(lat)`,
 *    `z = cos(lat)cos(lon)`, so the inverse here is `lat = asin(y)`, `lon = atan(x, z)`.
 *  - **Screen**: `projectRotated` maps `x -> +screenX` but `y -> -screenY`, hence the negated y when
 *    rebuilding the view-space normal.
 *  - **Rotation**: `orientation` takes earth-fixed to view space, so its CONJUGATE is what takes a
 *    view-space normal back to earth-fixed. That is what gets uploaded.
 *  - **Sun**: `sunDirectionAt` returns an earth-fixed vector, which is the space the lighting dot
 *    products are done in - no rotation needed.
 *
 * Zoom and pan need no handling at all: both are entirely absorbed by the globe centre and radius
 * uniforms, which the draw scope already computes for the vector layers.
 */
private const val EARTH_AGSL = """
uniform shader dayTex;
uniform shader nightTex;
uniform float2 dayTexSize;
uniform float2 nightTexSize;
uniform float2 globeCenter;
uniform float globeRadius;
uniform float3 sunDir;
uniform float4 viewQuat;

const float PI = 3.14159265;
const float TWO_PI = 6.28318531;

// Half-width of the day/night crossfade in cos(angle). 0.14 is a little over 8 degrees of solar
// elevation, which is roughly civil twilight and reads as a soft edge rather than a hard line.
const float TWILIGHT = 0.14;
// Sunlight wrapped slightly past the geometric terminator. Real Earth from orbit is not a hard
// Lambert ball - atmosphere scatters light around the limb - and without this the day side falls off
// far too fast near the edges.
const float LIGHT_WRAP = 0.35;
// Ambient on the lit side so shadowed terrain never goes fully black.
const float AMBIENT = 0.14;
// Earthshine: how much of the day texture stays faintly visible on the night side, so continents are
// still readable where there are no cities.
const float EARTHSHINE = 0.05;
const float NIGHT_GAIN = 0.85;
// Sea mask ramp, in blue-as-a-fraction-of-total. Measured against the day imagery rather than
// guessed: land and ice top out at 0.333 - neutral grey is exactly one third whatever its
// brightness, which is why Antarctica and Greenland both land there - while the least blue real
// water, the Bahamas shelf, sits at 0.476. Deep ocean runs 0.66-0.74.
//
// This ramp sits in that empty band, low enough that even shallow shelves get the full effect and
// high enough to keep ~0.03 of headroom above neutral ice for JPEG noise.
const float SEA_MASK_START = 0.37;
const float SEA_MASK_END = 0.45;
// Warm tint applied where the sun is low, standing in for the reddening of light through a long
// atmospheric path.
const half3 SUNSET_TINT = half3(1.25, 0.72, 0.42);
const half3 ATMOSPHERE = half3(0.38, 0.62, 1.0);
// Outer halo thickness as a fraction of the globe radius, and its peak opacity.
const float RIM_THICKNESS = 0.11;
const float RIM_ALPHA = 0.34;
// Inner limb glow: how sharply the atmosphere gathers toward the silhouette edge. The exponent is
// what actually controls how far the blue bleeds inward, and it is easy to underestimate - at 7 the
// term is still 0.32 at 85% of the radius, which tinted roughly a third of the disc and read as a
// blue overlay rather than as atmosphere. At 14 it is 0.10 there, confining the glow to the outer
// sliver where a real limb sits.
const float LIMB_POWER = 14.0;
const float LIMB_ALPHA = 0.38;
// Sun glint, in two concentric lobes to match the vector renderer - see the glint block in main().
// GLINT_COLOR is that renderer's SUN_GLINT_COLOR (0xFFFFF6DC): warm off-white, not neutral, which is
// a good part of why the effect reads as sunlight rather than as a bright patch.
const half3 GLINT_COLOR = half3(1.0, 0.965, 0.863);
// Deliberately well above the vector renderer's 0.06/0.14 peaks, for two reasons. That renderer
// masks land by DRAW ORDER, so its alphas are full strength, whereas these get multiplied by the
// water mask and by sunFacing squared - roughly 0.25 in an ordinary side-lit view - before anything
// reaches the pixel. And a pow lobe concentrates its energy far more tightly than the linear radial
// gradient it is standing in for, so equal peaks would cover much less of the ocean.
const float GLINT_GLOW_POWER = 6.0;
const float GLINT_GLOW_ALPHA = 0.16;
const float GLINT_CORE_POWER = 70.0;
const float GLINT_CORE_ALPHA = 0.38;

float3 rotateByQuat(float4 q, float3 v) {
    float3 uv = cross(q.xyz, v);
    float3 uuv = cross(q.xyz, uv);
    return v + 2.0 * (q.w * uv + uuv);
}

half4 main(float2 fragCoord) {
    float2 p = (fragCoord - globeCenter) / globeRadius;
    float r2 = dot(p, p);
    float r = sqrt(r2);
    float safeR = max(r, 1e-6);

    // Inside the disc this is the true sphere normal; outside it degenerates to the limb direction,
    // which is what the halo wants anyway. Computing one vector for both keeps the shader
    // branch-free apart from the final select.
    float3 dir = (r > 1.0)
        ? float3(p.x / safeR, -p.y / safeR, 0.0)
        : float3(p.x, -p.y, sqrt(max(0.0, 1.0 - r2)));
    float3 n = rotateByQuat(viewQuat, dir);

    float cosSun = dot(n, sunDir);

    // --- Surface ---
    float lat = asin(clamp(n.y, -1.0, 1.0));
    float lon = atan(n.x, n.z);
    float2 uv = float2(lon / TWO_PI + 0.5, 0.5 - lat / PI);

    half3 dayC = dayTex.eval(uv * dayTexSize).rgb;
    half3 nightC = nightTex.eval(uv * nightTexSize).rgb;

    // Sea mask for the sun glint. The imagery already carries the ocean's own colour, so this only
    // decides where water IS - it stands in for the land/sea mask the vector renderer gets for free
    // by drawing its glint UNDER the continent fills.
    //
    // Blue FRACTION, not blue-minus-red. A difference depends on absolute pixel values, so it
    // collapses if the texel arrives linearised rather than gamma-encoded - the same colour that
    // gives a difference of 0.18 in sRGB gives 0.028 linearised, and anything keyed on it silently
    // disappears. A ratio is immune to that, and to exposure generally.
    float channelSum = max(float(dayC.r) + float(dayC.g) + float(dayC.b), 1e-4);
    float water = smoothstep(SEA_MASK_START, SEA_MASK_END, float(dayC.b) / channelSum);

    // Wrapped diffuse rather than max(cosSun, 0): see LIGHT_WRAP.
    float diffuse = clamp((cosSun + LIGHT_WRAP) / (1.0 + LIGHT_WRAP), 0.0, 1.0);
    // Strongest exactly at the terminator and gone by mid-morning.
    float sunsetAmt = smoothstep(0.30, 0.0, cosSun) * step(-TWILIGHT, cosSun);
    half3 litC = dayC * half(diffuse * (1.0 - AMBIENT) + AMBIENT);
    litC = mix(litC, litC * SUNSET_TINT, half(sunsetAmt * 0.55));

    half3 darkC = nightC * half(NIGHT_GAIN) + dayC * half(EARTHSHINE);

    float dayAmt = smoothstep(-TWILIGHT, TWILIGHT, cosSun);
    half3 col = mix(darkC, litC, half(dayAmt));

    // Sun glint off water, in the same two concentric lobes the vector renderer uses: a broad faint
    // sheen with a tight bright core inside it. Both are needed - that renderer's own note records
    // that one wide gradient alone "was too diffuse to read as a reflection, it just lightened half
    // the ocean", and a first pass here that had only the core failed the other way, being a dot
    // small enough to miss entirely.
    //
    // The exponents are the Blinn-Phong equivalents of that renderer's screen-space radii, not
    // guesses: a lobe halves where pow(cos a, p) = 0.5, and a screen offset d from the glint point
    // tilts the surface normal by asin(d) - so its 0.45-radius sheen works out at p ~ 6 and its
    // 0.14-radius core at p ~ 70.
    float3 viewDir = rotateByQuat(viewQuat, float3(0.0, 0.0, 1.0));
    float3 halfVec = normalize(sunDir + viewDir);
    float nDotH = max(dot(n, halfVec), 0.0);

    // The same fade the vector renderer applies: dot(sunDir, viewDir) is exactly its `subsolar.z`,
    // so the glint dissolves as the sun swings behind the globe. Without it the reflection point
    // slides out to the limb and smears along the edge instead of going away.
    float sunFacing = (1.0 + dot(sunDir, viewDir)) * 0.5;

    float glint = pow(nDotH, GLINT_GLOW_POWER) * GLINT_GLOW_ALPHA +
                  pow(nDotH, GLINT_CORE_POWER) * GLINT_CORE_ALPHA;
    col += GLINT_COLOR * half(glint * water * sunFacing * sunFacing * dayAmt);

    // Atmosphere gathering toward the silhouette edge, from inside.
    float limb = pow(clamp(r, 0.0, 1.0), LIMB_POWER);
    col = mix(col, ATMOSPHERE, half(limb * LIMB_ALPHA * dayAmt));

    half4 surface = half4(col, 1.0);

    // --- Halo outside the silhouette ---
    // Faded by illumination at this bearing so it disappears around the dark limb rather than
    // ringing the whole globe, which is the behaviour the vector renderer needed 160 arcs to get.
    float d = clamp((r - 1.0) / RIM_THICKNESS, 0.0, 1.0);
    float falloff = (1.0 - d) * (1.0 - d);
    float haloLit = smoothstep(-0.35, 0.25, cosSun);
    float a = falloff * RIM_ALPHA * haloLit;
    // AGSL expects premultiplied alpha - without multiplying through, the halo reads as a dark ring.
    half4 halo = half4(ATMOSPHERE * half(a), half(a));

    return (r > 1.0) ? halo : surface;
}
"""

/**
 * How far past the globe silhouette the shader rect must extend to contain the halo.
 *
 * Must stay at or above `1 + RIM_THICKNESS`, or the halo gets clipped into a visible hard edge.
 */
const val EARTH_SHADER_RIM_EXTENT = 1.13f

/**
 * Mirrors the AGSL `TWILIGHT` constant above: half the width of the day/night crossfade, measured in
 * cos(angle between the surface normal and the sun).
 *
 * Exposed because the renderers that cannot read AGSL still have to reproduce the same terminator -
 * they go through the functions below rather than this directly - and the shape is easy to get
 * subtly wrong. The
 * crossfade is centred ON the geometric terminator, running from +TWILIGHT to -TWILIGHT - so night
 * starts bleeding in slightly BEFORE the sun geometrically sets. Rebuilding it one-sided, starting
 * at the terminator and only darkening from there, lights the cities visibly later than the globe
 * does, which is exactly how the two modes first drifted apart.
 */
const val EARTH_TWILIGHT_COS = 0.14f

/**
 * The rest of the shader's lighting model, mirrored for the renderers that cannot read AGSL.
 *
 * These are the single source of truth for how Earth is lit, and the flat map and the morph mesh
 * derive their own constants from them rather than carrying independent ones. That indirection is
 * the point: the map previously had its own darkness figure expressed in unrelated units, which is
 * how 2D ended up with a night side five times brighter than 3D's, tinted purple by tinting colours
 * the shader does not have.
 *
 * Each must stay equal to the AGSL constant of the same name above.
 */
const val EARTH_NIGHT_GAIN = 0.85f
const val EARTH_EARTHSHINE = 0.05f
const val EARTH_AMBIENT = 0.14f
const val EARTH_LIGHT_WRAP = 0.35f

/**
 * The shader's day/night split, evaluated on the CPU for a given cos(angle to the sun).
 *
 * Returns how much of the day imagery survives, and how strongly the night imagery shows - exactly
 * the two quantities `mix(darkC, litC, dayAmt)` resolves to per pixel. Any renderer that shades by
 * region rather than per pixel can ask this per region and match the globe by construction.
 */
data class EarthLighting(val dayLevel: Float, val nightLevel: Float)

fun earthLightingAt(cosSun: Float): EarthLighting =
    EarthLighting(earthDayLevelAt(cosSun), earthNightLevelAt(cosSun))

/**
 * The two halves of [earthLightingAt] on their own, for callers shading per vertex rather than per
 * region - several thousand times a frame is enough for the pair object to be worth not allocating.
 */
fun earthDayLevelAt(cosSun: Float): Float {
    val dayAmount = earthDayAmountAt(cosSun)
    val diffuse = ((cosSun + EARTH_LIGHT_WRAP) / (1f + EARTH_LIGHT_WRAP)).coerceIn(0f, 1f)
    val lit = diffuse * (1f - EARTH_AMBIENT) + EARTH_AMBIENT
    return dayAmount * lit + (1f - dayAmount) * EARTH_EARTHSHINE
}

fun earthNightLevelAt(cosSun: Float): Float = (1f - earthDayAmountAt(cosSun)) * EARTH_NIGHT_GAIN

private fun earthDayAmountAt(cosSun: Float): Float {
    val t = ((cosSun + EARTH_TWILIGHT_COS) / (2f * EARTH_TWILIGHT_COS)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Binds [EarthTextures] to the Earth shader and re-uploads the per-frame uniforms.
 *
 * Textures and their sizes are bound once at construction; only the four values that actually change
 * per frame are written in [brushFor].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class EarthShader(textures: EarthTextures) {

    private val shader = RuntimeShader(EARTH_AGSL)

    // One brush, reused. ShaderBrush built from an explicit Shader holds that instance rather than
    // rebuilding per size, so mutating the RuntimeShader's uniforms below is picked up on the next
    // draw without reallocating anything.
    private val brush = ShaderBrush(shader)

    init {
        // REPEAT across x so sampling straight through the antimeridian blends between the map's two
        // edges instead of clamping to a seam; CLAMP in y so the poles hold their last row.
        shader.setInputShader(
            "dayTex",
            BitmapShader(textures.day, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        )
        shader.setInputShader(
            "nightTex",
            BitmapShader(textures.night, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        )
        // AGSL samples an input shader in the source bitmap's PIXELS, not normalised 0..1, so the
        // shader needs the dimensions to scale its uv by. Each texture carries its own because the
        // two are decoded at different sample sizes.
        shader.setFloatUniform("dayTexSize", textures.day.width.toFloat(), textures.day.height.toFloat())
        shader.setFloatUniform("nightTexSize", textures.night.width.toFloat(), textures.night.height.toFloat())
    }

    fun brushFor(globeCenter: Offset, globeRadius: Float, sunDir: Vec3, orientation: Quaternion): ShaderBrush {
        shader.setFloatUniform("globeCenter", globeCenter.x, globeCenter.y)
        shader.setFloatUniform("globeRadius", globeRadius)
        shader.setFloatUniform("sunDir", sunDir.x, sunDir.y, sunDir.z)
        // Conjugate, because the shader needs view-space -> earth-fixed while `orientation` is the
        // other way round. Packed xyzw so the shader's q.xyz/q.w reads match GLSL convention.
        val inverse = orientation.conjugate()
        shader.setFloatUniform("viewQuat", inverse.x, inverse.y, inverse.z, inverse.w)
        return brush
    }
}

/**
 * Builds an [EarthShader], or returns null if this device can't run one.
 *
 * `RuntimeShader` arrived in API 33 while the app supports 26, so this is the single place that
 * decides. A null result means the caller keeps the vector renderer, which is the intended fallback
 * rather than an error path.
 */
fun createEarthShader(textures: EarthTextures): EarthShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return try {
        EarthShader(textures)
    } catch (e: Exception) {
        // Logged rather than swallowed. RuntimeShader compiles its AGSL in the constructor and
        // throws on any syntax or type error, and because the failure path here is a silent fall
        // back to the vector globe, a broken shader would otherwise present as "the toggle does
        // nothing" with no way to tell that from an unsupported device.
        Log.w("EarthShader", "Earth shader unavailable, falling back to the vector globe", e)
        null
    }
}
