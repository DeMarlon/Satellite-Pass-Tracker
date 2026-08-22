package com.example.eps_sgtracker.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import com.example.eps_sgtracker.model.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map's day/night lighting, baked into two small equirectangular bitmaps.
 *
 * ## Why bitmaps rather than bands
 *
 * The terminator was previously approximated by stacked rectangles - one per latitude row, per
 * twilight band. That fails in two ways at once, and both were visible:
 *
 *  - **Blockiness.** Near the poles the lines of equal illumination run almost parallel to the
 *    latitude circles, so each discrete band becomes a wide horizontal stripe. No band count fixes
 *    this; it is what banding *is* wherever the gradient is spatially slow.
 *  - **Seams.** Any tiling of alpha rectangles leaves antialiased edges meeting on shared
 *    boundaries, which show as hairlines, and any overlap double-darkens instead.
 *
 * Sampling the exact lighting function per texel and letting the GPU interpolate removes both, and
 * is cheaper: this is rebuilt only when the sun moves (about once a minute), not per frame, and it
 * replaces thousands of rectangle fills with two scaled blits.
 *
 * The resolution only needs to resolve the terminator, which is the sharpest feature here and still
 * spans several degrees, so this is far coarser than the imagery it shades.
 */
private const val LIGHTING_WIDTH = 512
private const val LIGHTING_HEIGHT = LIGHTING_WIDTH / 2

/**
 * [darkening] is black with the alpha needed to bring the day imagery down to its lit level, drawn
 * straight over the map. [nightMask] is white with the alpha the night imagery should show at, used
 * as a DstIn mask so the city lights fade in across the terminator rather than switching on.
 */
class MapLighting(val darkening: Bitmap, val nightMask: Bitmap)

/**
 * The same layers wrapped for Compose's drawImage, converted once rather than per frame.
 *
 * [nightMask] is null for the vector map: there is no night photograph to reveal through a mask,
 * only fills to dim.
 */
class MapLightingImages(
    val darkening: androidx.compose.ui.graphics.ImageBitmap,
    val nightMask: androidx.compose.ui.graphics.ImageBitmap?
)

/**
 * The vector map's night tint, sampled per texel like [buildMapLighting].
 *
 * Kept separate from the imagery path because the two are shading different things: imagery
 * crossfades into a real night photograph, while the flat vector fills only need dimming and a shift
 * from warm to cool, which is what the globe's own terminator does.
 *
 * [totalDarkening] is reached at [twilightSpanDeg] below the horizon. The exponential is not
 * arbitrary - stacking N translucent layers of equal alpha yields `1 - (1-a)^N`, so this is the exact
 * continuous limit of the banded version it replaces, and lands on the same darkness at full night.
 */
fun buildMapNightTint(
    sunDir: Vec3,
    warm: androidx.compose.ui.graphics.Color,
    cool: androidx.compose.ui.graphics.Color,
    totalDarkening: Float,
    twilightSpanDeg: Float
): Bitmap {
    val pixels = IntArray(LIGHTING_WIDTH * LIGHTING_HEIGHT)
    val span = sin(Math.toRadians(twilightSpanDeg.toDouble())).toFloat().coerceAtLeast(1e-4f)

    for (row in 0 until LIGHTING_HEIGHT) {
        val lat = Math.toRadians(90.0 - (row + 0.5) * 180.0 / LIGHTING_HEIGHT)
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val rowStart = row * LIGHTING_WIDTH

        for (col in 0 until LIGHTING_WIDTH) {
            val lon = Math.toRadians(-180.0 + (col + 0.5) * 360.0 / LIGHTING_WIDTH)
            val cosSun = (cosLat * sin(lon) * sunDir.x +
                sinLat * sunDir.y +
                cosLat * cos(lon) * sunDir.z).toFloat()

            val depth = (-cosSun / span).coerceIn(0f, 1f)
            val darkness = 1f - Math.pow((1f - totalDarkening).toDouble(), depth.toDouble()).toFloat()
            val tint = androidx.compose.ui.graphics.lerp(warm, cool, depth)
            pixels[rowStart + col] = tint.copy(alpha = darkness).toArgb()
        }
    }

    return Bitmap.createBitmap(pixels, LIGHTING_WIDTH, LIGHTING_HEIGHT, Bitmap.Config.ARGB_8888)
}

fun buildMapLighting(sunDir: Vec3): MapLighting {
    val darkenPixels = IntArray(LIGHTING_WIDTH * LIGHTING_HEIGHT)
    val maskPixels = IntArray(LIGHTING_WIDTH * LIGHTING_HEIGHT)

    for (row in 0 until LIGHTING_HEIGHT) {
        // Sampled at texel CENTRES, so bilinear filtering interpolates symmetrically rather than
        // shifting the terminator half a texel north.
        val lat = Math.toRadians(90.0 - (row + 0.5) * 180.0 / LIGHTING_HEIGHT)
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val rowStart = row * LIGHTING_WIDTH

        for (col in 0 until LIGHTING_WIDTH) {
            val lon = Math.toRadians(-180.0 + (col + 0.5) * 360.0 / LIGHTING_WIDTH)
            val cosSun = (cosLat * sin(lon) * sunDir.x +
                sinLat * sunDir.y +
                cosLat * cos(lon) * sunDir.z).toFloat()

            // The same function the shader evaluates per pixel, so the two renderers cannot disagree
            // about where night begins or how dark it gets.
            val lighting = earthLightingAt(cosSun)

            val darkAlpha = ((1f - lighting.dayLevel).coerceIn(0f, 1f) * 255f).toInt()
            darkenPixels[rowStart + col] = darkAlpha shl 24

            val nightAlpha = (lighting.nightLevel.coerceIn(0f, 1f) * 255f).toInt()
            maskPixels[rowStart + col] = (nightAlpha shl 24) or 0x00FFFFFF
        }
    }

    return MapLighting(
        darkening = Bitmap.createBitmap(
            darkenPixels, LIGHTING_WIDTH, LIGHTING_HEIGHT, Bitmap.Config.ARGB_8888
        ),
        nightMask = Bitmap.createBitmap(
            maskPixels, LIGHTING_WIDTH, LIGHTING_HEIGHT, Bitmap.Config.ARGB_8888
        )
    )
}
