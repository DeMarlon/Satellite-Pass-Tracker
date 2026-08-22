package com.example.eps_sgtracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// NASA imagery, public domain. Both are equirectangular full-globe maps at exactly 2:1, which is
// what the shader's lat/lon -> uv mapping assumes.
// The combined Blue Marble Next Generation product: shaded land relief AND ocean bathymetry already
// composited in as real colour. Deliberately this variant rather than the plain `world.200406`,
// whose ocean is raw water radiance and comes out near-black on screen - reconstructing a sea colour
// from separate greyscale depth layers was tried first and meant inventing constants for something
// NASA had already measured.
private const val DAY_ASSET = "world.topo.bathy.200406.3x5400x2700.jpg"
private const val NIGHT_ASSET = "BlackMarble_2016_global_7km.jpg"

// The source images are 5400 and 5760 pixels wide. Neither can be used as-is:
//
//  - At full resolution they decode to roughly 58MB and 66MB. That much texture is what pushed this
//    app into the low-memory killer's sights on a small device once already.
//  - Both exceed 4096, and plenty of mobile GPUs cap GL_MAX_TEXTURE_SIZE there, where an oversized
//    texture either fails to bind or gets silently rescaled by the driver.
//
// inSampleSize is applied *during* decode, so the oversized bitmap never exists in memory even
// briefly - unlike decoding then scaling, which would allocate the full 58MB first.
//
// The two are sampled down by different amounts on purpose: the day map carries the detail anyone
// actually looks at, so it only halves, while the night map is diffuse city glow where a quarter of
// the resolution is indistinguishable. That asymmetry alone saves ~12MB.
private const val DAY_SAMPLE_SIZE = 2
// Was 4, on the assumption that city lights are diffuse enough to survive heavy downsampling. They
// are not: they are closer to point sources, and box-averaging a bright city against the dark
// countryside around it lowers the peak and spreads it - which reads exactly as the washed-out night
// side that prompted this. Halving the step preserves four times the detail.
//
// 2 is also the floor: the source is 5760 wide, and decoding it whole would both exceed the 4096
// texture limit plenty of mobile GPUs enforce and cost 66MB. At this step it is 2880x1440, safely
// under that limit, for ~16.6MB.
private const val NIGHT_SAMPLE_SIZE = 2

/** The equirectangular maps the Earth shader samples. */
data class EarthTextures(val day: Bitmap, val night: Bitmap)

/**
 * Loads the photorealistic Earth textures, cached for the process lifetime.
 *
 * Mirrors [LandRepository]'s double-checked `@Volatile` cache: these are expensive to decode, never
 * change, and are shared by every recomposition of the 3D view.
 *
 * Returns null rather than throwing if either asset is missing or fails to decode. The caller treats
 * that as "stay on the vector renderer", so a bad texture degrades the globe's appearance instead of
 * taking the screen down.
 */
object EarthTextureRepository {

    @Volatile
    private var cache: EarthTextures? = null

    suspend fun loadTextures(context: Context): EarthTextures? {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val day = decodeAsset(context, DAY_ASSET, DAY_SAMPLE_SIZE) ?: return@withContext null
            val night = decodeAsset(context, NIGHT_ASSET, NIGHT_SAMPLE_SIZE)
                ?: return@withContext null
            EarthTextures(day, night).also { cache = it }
        }
    }

    private fun decodeAsset(context: Context, assetName: String, sampleSize: Int): Bitmap? = try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // HARDWARE puts the decoded pixels in GPU memory rather than on the Java heap. That is
            // exactly right for these: they are only ever sampled by a BitmapShader on a
            // hardware-accelerated canvas and never read back, so the heap copy would be pure waste
            // - and heap is the pressure that matters for being killed in the background.
            inPreferredConfig = Bitmap.Config.HARDWARE
        }
        context.assets.open(assetName).use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
