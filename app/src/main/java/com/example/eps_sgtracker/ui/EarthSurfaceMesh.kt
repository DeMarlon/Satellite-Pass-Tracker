package com.example.eps_sgtracker.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import kotlin.math.floor

/**
 * Draws the textured Earth part-way between a globe and a flat map, for the unwrap animation.
 *
 * ## Why this exists rather than the shader
 *
 * The photorealistic shader is an *inverse* map - for each screen pixel it solves backwards for a
 * point on the sphere - and there is no closed-form interpolation between the sphere inverse and the
 * map inverse. A textured mesh is a *forward* map: each lat/lon vertex is projected to screen, so
 * interpolating just means lerping the output position. That turns an intractable problem into a
 * trivial one, which is why the shader steps aside for the duration of the transition.
 *
 * ## Why drawVertices rather than drawBitmapMesh
 *
 * `drawBitmapMesh` takes a fixed grid and draws it in a fixed order, which is fatal here: under
 * orthographic projection the far hemisphere lands on the *same disc* as the near one, so a
 * morphing grid draws the back of the globe on top of the front. There is no way to cull or reorder.
 *
 * `drawVertices` takes a triangle list the caller builds, so the triangles can be sorted back to
 * front by their interpolated depth and drawn painter's-algorithm style. At t=0 the far side is
 * drawn first and completely covered by the near side; as t approaches 1 the two separate into the
 * flat map. Correct occlusion the whole way through, with no depth buffer.
 *
 * Its per-vertex `colors` array is also genuinely honoured, unlike `drawBitmapMesh`'s, which is what
 * lets the day/night terminator survive the transition instead of blinking out.
 *
 * ## The strip, and why the grid is not simply lat/lon
 *
 * The map repeats the world horizontally and lets each layer pick whichever copy it lands in. A mesh
 * cannot: it is one continuous sheet, so it has to be *positioned* rather than repeated, and the
 * choice of which turn of longitude each column sits on is what makes it line up with the map.
 *
 * Asking the map projection for each vertex independently is the obvious approach and is wrong,
 * because that projection wraps every longitude into +/-180 of the map centre. Every row then
 * contains one step where the wrap fires, and the quad spanning it runs the entire width of the map
 * *backwards* - a single 3-degree slice of the texture smeared across the whole world, in all 64
 * rows at once. It only looked right at all because a view that has never been rotated is centred on
 * longitude 0, where the wrap lands on the seam the grid already has.
 *
 * So the grid is built as a strip: column 0 starts at whichever sample sits at the left edge of the
 * map, and longitude then accumulates continuously across the strip for exactly one turn. The wrap
 * happens once, in the *indexing* of which sphere sample a column reads, where it costs nothing.
 */

// Grid resolution. Longitude needs roughly twice latitude's density to keep quads square-ish, and
// this is a transient animation, so a few thousand triangles is cheap next to the alternative of
// visible faceting along the limb.
// Lighting is carried per vertex and Gouraud-interpolated across each quad, so the grid has to be
// fine enough to resolve the TERMINATOR, not just the silhouette. At 48x24 a quad spanned 7.5
// degrees and only about two of them covered the whole twilight band, which read as blocky shading
// along it. The ordering below is O(n) rather than a sort, which is what makes this affordable.
private const val MESH_COLS = 128
private const val MESH_ROWS = 64
private const val MESH_LON_STEP = 360f / MESH_COLS

// The strip is one quad column WIDER than a full turn. Its first column is snapped to a whole
// sample, so on its own a turn's worth of columns can start up to one column short of the map's left
// edge and end that far short of the right one; the extra column is what covers the difference. At
// zoom 1 the world is exactly as wide as the viewport, so without it a sliver of bare background
// showed down one edge for the last part of the unwrap.
private const val MESH_QUAD_COLS = MESH_COLS + 1
private const val MESH_ROW_VERTS = MESH_QUAD_COLS + 1

/**
 * A paint bound to one bitmap, rebound only when the bitmap itself changes.
 *
 * Antialiasing is OFF deliberately, and this is not a quality compromise - it is the fix for a
 * specific artifact. drawVertices antialiases every triangle INDIVIDUALLY, so each internal edge
 * shared between neighbouring triangles gets partial coverage from both sides and shows as a
 * hairline seam. Across a grid those seams line up into visible streaks, and the finer the mesh the
 * worse it gets: at 48x24 they were sparse enough to miss, at 128x64 the row edges read as
 * horizontal tearing straight across the globe.
 *
 * The mesh only covers the interior of a shape whose own outline is not drawn here, so there is no
 * silhouette to soften - all AA could do is damage the seams. Bilinear filtering stays on; that is
 * what actually smooths the texture.
 */
private class MeshPaint(additive: Boolean) {
    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = true
        // Night imagery is ADDED to what the day pass already drew rather than composited over it,
        // exactly as the flat map adds it through BlendMode.Plus - the night texture is black
        // wherever nothing is lit, so adding it leaves the darkened day imagery intact instead of
        // flattening it. PorterDuff rather than the BlendMode API, which needs API 29.
        if (additive) xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private var bound: Bitmap? = null

    fun forBitmap(bitmap: Bitmap): Paint {
        if (bound !== bitmap) {
            // REPEAT across x because the strip's texture coordinates run CONTINUOUSLY past the edge
            // of the image rather than being wrapped per column - that is what keeps the seam
            // interpolating across the join instead of running the whole width of the image
            // backwards. CLAMP in y so the poles hold their last row.
            paint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            bound = bitmap
        }
        return paint
    }
}

/**
 * A reusable buffer set for one mesh draw. Held across frames because the animation runs at 30fps
 * and these arrays run to several thousand floats - reallocating them per frame is exactly the
 * churn this renderer is otherwise careful to avoid.
 */
class EarthMeshBuffers {
    private val vertexCount = MESH_ROW_VERTS * (MESH_ROWS + 1)
    private val quadCount = MESH_QUAD_COLS * MESH_ROWS

    val positions = FloatArray(vertexCount * 2)
    val colors = IntArray(vertexCount)
    val indices = ShortArray(quadCount * 6)

    /**
     * Texture coordinates in the bitmap's own pixels.
     *
     * Rebuilt every frame rather than once, because which sample a column reads depends on where the
     * map is currently centred - see the strip note above.
     */
    val texCoords = FloatArray(vertexCount * 2)

    /** Per-vertex view depth, so the quad ordering below re-reads it rather than re-rotating. */
    val viewZ = FloatArray(vertexCount)

    /**
     * Per-quad view depth, used only to split far-facing quads from near-facing ones.
     *
     * No ordering scratch alongside it: under orthographic projection a sphere's near half never
     * overlaps itself, so a two-pass partition on the sign of this is sufficient and needs nothing
     * to sort into.
     */
    val quadDepth = FloatArray(quadCount)

    /**
     * Unit-sphere position of every sample - fixed, so it is built once.
     *
     * One full turn per row with NO duplicated seam column: the duplicate belongs to the grid, which
     * shifts, not to the samples, which do not.
     */
    val spherePoints: Array<Vec3> = Array((MESH_ROWS + 1) * MESH_COLS) { index ->
        val row = index / MESH_COLS
        val col = index % MESH_COLS
        latLonDegToUnitSphere(
            (90.0 - row * 180.0 / MESH_ROWS),
            (-180.0 + col * 360.0 / MESH_COLS)
        )
    }

    // Only allocated where there is night imagery to add, which is the photorealistic mode alone.
    private var nightTexCoordsOrNull: FloatArray? = null
    private var nightColorsOrNull: IntArray? = null

    val nightTexCoords: FloatArray
        get() = nightTexCoordsOrNull ?: FloatArray(vertexCount * 2).also { nightTexCoordsOrNull = it }

    val nightColors: IntArray
        get() = nightColorsOrNull ?: IntArray(vertexCount).also { nightColorsOrNull = it }

    private val dayPaint = MeshPaint(additive = false)
    private val nightPaint = MeshPaint(additive = true)

    fun dayPaintFor(bitmap: Bitmap): Paint = dayPaint.forBitmap(bitmap)
    fun nightPaintFor(bitmap: Bitmap): Paint = nightPaint.forBitmap(bitmap)
}

/**
 * Builds and draws the morphing Earth.
 *
 * [morph] is 0 for a full globe and 1 for a full map; every vertex is simply lerped between the two
 * projections. [globeProject] and [globeDepth] are supplied by the caller so this stays ignorant of
 * zoom, pan and orientation - it only needs to know where a given sphere point lands on the globe -
 * while [layout] carries the same for the map, which the strip has to read rather than be told point
 * by point.
 *
 * The mesh deliberately carries NO lighting constants of its own. For the moment it owns the surface
 * it is standing in for another renderer, and any model of its own would show as the night side
 * changing brightness at each end of the transition, so [dayTint] and [nightTint] hand it that
 * renderer's own. [dayTint] is multiplied into the texture; [nightTint] modulates [nightBitmap],
 * which is then added on top.
 */
fun Canvas.drawEarthMesh(
    buffers: EarthMeshBuffers,
    bitmap: Bitmap,
    dayTint: (cosSun: Float) -> Int,
    nightBitmap: Bitmap?,
    nightTint: ((cosSun: Float) -> Int)?,
    morph: Float,
    sunDir: Vec3,
    layout: MapLayout,
    globeProject: (Vec3) -> Offset,
    globeDepth: (Vec3) -> Float
) {
    val night = nightBitmap
    val nightLevel = nightTint

    // Which sample the strip starts on. Floor rather than round so the strip begins at or BEFORE the
    // map's left edge, leaving the whole of its one-column overhang at the right where it finishes
    // the job of covering the viewport.
    val shift = floor(layout.centerLon / MESH_LON_STEP).toInt()

    var vertex = 0
    for (row in 0..MESH_ROWS) {
        val lat = 90f - row * 180f / MESH_ROWS
        val mapY = layout.yForLat(lat)
        val sampleRow = row * MESH_COLS
        val texV = row.toFloat() / MESH_ROWS
        for (col in 0..MESH_QUAD_COLS) {
            // The strip's own longitude, running continuously across it; the sample it reads is that
            // longitude wrapped back into the one turn the sphere actually has.
            val sample = shift + col
            val wrapped = sample % MESH_COLS
            val v = buffers.spherePoints[sampleRow + if (wrapped < 0) wrapped + MESH_COLS else wrapped]

            val g = globeProject(v)
            val mapX = layout.xForLonOffset(-180f + sample * MESH_LON_STEP - layout.centerLon)
            buffers.positions[vertex * 2] = g.x + (mapX - g.x) * morph
            buffers.positions[vertex * 2 + 1] = g.y + (mapY - g.y) * morph

            // Texture coordinates run past the edge of the image with the strip rather than wrapping
            // with the sample, which is what the shader's REPEAT tiling is there for.
            val texU = sample.toFloat() / MESH_COLS
            buffers.texCoords[vertex * 2] = texU * bitmap.width
            buffers.texCoords[vertex * 2 + 1] = texV * bitmap.height

            // Day/night as a per-vertex tint. Gouraud interpolation across the grid is smooth enough
            // for a terminator this soft, and it means the transition never loses the lighting.
            val cosSun = v.x * sunDir.x + v.y * sunDir.y + v.z * sunDir.z
            buffers.colors[vertex] = dayTint(cosSun)
            if (night != null && nightLevel != null) {
                buffers.nightTexCoords[vertex * 2] = texU * night.width
                buffers.nightTexCoords[vertex * 2 + 1] = texV * night.height
                buffers.nightColors[vertex] = nightLevel(cosSun)
            }

            buffers.viewZ[vertex] = globeDepth(v)
            vertex++
        }
    }

    // Painter's algorithm, but by HEMISPHERE rather than by a full depth sort.
    //
    // Under orthographic projection a sphere's near half never overlaps itself - only the far half
    // can be hidden behind it - so emitting every far-facing quad before every near-facing one is
    // sufficient, and it is O(n) with no allocation. A comparison sort would be O(n log n) on boxed
    // indices every frame, which is what previously kept the grid too coarse to shade smoothly.
    var i = 0

    fun emitQuad(quad: Int) {
        val topLeft = ((quad / MESH_QUAD_COLS) * MESH_ROW_VERTS + quad % MESH_QUAD_COLS).toShort()
        val topRight = (topLeft + 1).toShort()
        val bottomLeft = (topLeft + MESH_ROW_VERTS).toShort()
        val bottomRight = (bottomLeft + 1).toShort()
        buffers.indices[i++] = topLeft
        buffers.indices[i++] = topRight
        buffers.indices[i++] = bottomRight
        buffers.indices[i++] = topLeft
        buffers.indices[i++] = bottomRight
        buffers.indices[i++] = bottomLeft
    }

    for (quad in buffers.quadDepth.indices) {
        val topLeft = (quad / MESH_QUAD_COLS) * MESH_ROW_VERTS + quad % MESH_QUAD_COLS
        buffers.quadDepth[quad] = buffers.viewZ[topLeft] + buffers.viewZ[topLeft + MESH_ROW_VERTS + 1]
    }
    for (quad in buffers.quadDepth.indices) if (buffers.quadDepth[quad] < 0f) emitQuad(quad)
    val farIndexCount = i
    for (quad in buffers.quadDepth.indices) if (buffers.quadDepth[quad] >= 0f) emitQuad(quad)

    // Each hemisphere is drawn COMPLETE before the next one starts, night imagery included.
    //
    // The night pass is additive, so it is not covered by anything drawn after it - and running it
    // as a second sweep over the whole mesh therefore undid the ordering above, adding the far side's
    // city lights on top of near-side pixels that had already been drawn over them. The far half read
    // as showing through the front of the Earth, which is exactly what a lost depth order looks like.
    fun drawRange(offset: Int, count: Int) {
        if (count <= 0) return
        drawVertices(
            Canvas.VertexMode.TRIANGLES,
            buffers.positions.size,
            buffers.positions,
            0,
            buffers.texCoords,
            0,
            buffers.colors,
            0,
            buffers.indices,
            offset,
            count,
            buffers.dayPaintFor(bitmap)
        )
        // Over exactly the same triangles. Its vertex colours are opaque greys like the day pass's -
        // the transparency that lets it fade in across the terminator is the paint's additive blend
        // against a surface the pass above has just made opaque, not per-vertex alpha.
        if (night != null && nightLevel != null) {
            drawVertices(
                Canvas.VertexMode.TRIANGLES,
                buffers.positions.size,
                buffers.positions,
                0,
                buffers.nightTexCoords,
                0,
                buffers.nightColors,
                0,
                buffers.indices,
                offset,
                count,
                buffers.nightPaintFor(night)
            )
        }
    }

    drawRange(0, farIndexCount)
    drawRange(farIndexCount, i - farIndexCount)
}
