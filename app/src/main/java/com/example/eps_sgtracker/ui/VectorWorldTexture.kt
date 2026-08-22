package com.example.eps_sgtracker.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import com.example.eps_sgtracker.model.GlobePolygon
import com.example.eps_sgtracker.model.GlobeRing

/**
 * Renders the hand-drawn vector world into an equirectangular bitmap.
 *
 * This exists so the unwrap animation works without the photorealistic imagery. The morph warps a
 * *texture* across a mesh, so with no texture there is nothing to deform - the surface simply
 * vanished for the duration, which read as a fade to black. Baking the vector layers into a bitmap
 * once gives that mode a texture of its own, and the exact same mesh path then animates it.
 *
 * The projection work is not reimplemented: constructing a [MapLayout] over the bitmap's own bounds
 * lets this reuse [buildMapPolygonPath] and [buildMapSubpaths] verbatim, which is what keeps the
 * antimeridian seam and Antarctica's polar ring correct here rather than needing a second, subtly
 * different implementation of both.
 */
private const val TEXTURE_WIDTH = 2048
private const val TEXTURE_HEIGHT = TEXTURE_WIDTH / 2

/** Identifies what a cached texture was built from, so it is rebuilt only when that changes. */
data class VectorWorldKey(
    val landCount: Int,
    val glacierCount: Int,
    val lakeCount: Int,
    val riverCount: Int,
    val borderCount: Int,
    val showBorders: Boolean
)

fun renderVectorWorldTexture(
    land: List<GlobePolygon>,
    glaciers: List<GlobePolygon>,
    lakes: List<GlobePolygon>,
    rivers: List<GlobeRing>,
    borders: List<GlobeRing>,
    showBorders: Boolean,
    oceanColor: Color,
    landColor: Color,
    landStroke: Color,
    glacierColor: Color,
    lakeColor: Color,
    riverColor: Color,
    borderColor: Color
): Bitmap {
    val bitmap = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // A layout describing the bitmap itself: centred on 0N 0E, exactly one world wide. Every helper
    // the live map uses then applies unchanged.
    val layout = MapLayout(
        centerLat = 0f,
        centerLon = 0f,
        screenCenter = Offset(TEXTURE_WIDTH / 2f, TEXTURE_HEIGHT / 2f),
        width = TEXTURE_WIDTH.toFloat(),
        height = TEXTURE_HEIGHT.toFloat(),
        viewportWidth = TEXTURE_WIDTH.toFloat()
    )

    val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    val stroke = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    // Scaled off the texture rather than the screen: these lines are baked in, so they have to be
    // sized in texture space or they come out hairline once it is stretched over the globe.
    val thinStroke = TEXTURE_WIDTH / 1024f

    canvas.drawColor(oceanColor.toArgb())

    fun fillPolygons(polygons: List<GlobePolygon>, color: Color, outline: Color?) {
        fill.color = color.toArgb()
        stroke.color = outline?.toArgb() ?: 0
        stroke.strokeWidth = thinStroke
        polygons.forEach { polygon ->
            // Fill and outline differ for a polar ring - the fill's closure along the pole is not a
            // coastline, so stroking it would bake a line across the texture.
            val paths = buildMapPolygonPath(polygon.rings, layout)
            paths.fill.forEach { canvas.drawPath(it.asAndroidPath(), fill) }
            if (outline != null) paths.outline.forEach { canvas.drawPath(it.asAndroidPath(), stroke) }
        }
    }

    fun strokeRings(rings: List<GlobeRing>, color: Color, width: Float) {
        stroke.color = color.toArgb()
        stroke.strokeWidth = width
        rings.forEach { ring ->
            buildMapSubpaths(ring.points, ring.isClosed, layout).forEach {
                canvas.drawPath(it.asAndroidPath(), stroke)
            }
        }
    }

    fillPolygons(land, landColor, landStroke)
    fillPolygons(glaciers, glacierColor, null)
    fillPolygons(lakes, lakeColor, null)
    strokeRings(rivers, riverColor, thinStroke)
    if (showBorders) strokeRings(borders, borderColor, thinStroke)

    return bitmap
}
