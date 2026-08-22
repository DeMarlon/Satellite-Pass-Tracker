package com.example.eps_sgtracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.eps_sgtracker.model.CloudContour
import com.example.eps_sgtracker.model.Vec3
import com.example.eps_sgtracker.model.latLonDegToUnitSphere
import com.example.eps_sgtracker.network.GibsCloudApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

const val CLOUD_GRID_COLS = 90
const val CLOUD_GRID_ROWS = 45
private const val CACHE_FILE_NAME = "cloud_grid_cache.txt"
private const val NO_DATA = -1f

// Two density bands layered on top of each other: a lighter, larger halo and a smaller, denser
// core, so cloud masses read as having some internal texture rather than a flat silhouette.
private const val THRESHOLD_LIGHT = 0.55f
private const val THRESHOLD_DENSE = 0.75f
private const val ALPHA_LIGHT = 0.22f
private const val ALPHA_DENSE = 0.40f

// GIBS' "best available" mosaic for the current day is typically still empty (not yet
// composited from that day's orbital passes), so request imagery from a couple of days back to
// reliably land on a fully populated global mosaic.
private const val FETCH_LOOKBACK_DAYS = 2

/**
 * Loads a global cloud-cover layer traced from NASA GIBS' daily true-color satellite mosaic.
 *
 * The source imagery is downsampled to a coarse point grid using a simple bright-and-desaturated
 * heuristic ("cloudiness": clouds are white/light-gray; ocean/land are more saturated), then
 * contoured with marching squares at two thresholds. Tracing real connected cloud-mass boundaries
 * (rather than sampling independent cells and drawing each as its own shape) is what makes
 * adjacent cloudy cells merge into one seamless region instead of a field of separate blobs.
 *
 * A single day of MODIS Terra imagery has real no-data gaps (the satellite's orbital swaths
 * don't tile the globe perfectly in one day - visible as black wedges in the source imagery), so
 * gaps are filled from the nearest point that does have data rather than left out, which
 * previously read as "clouds just stop" partway across the globe.
 *
 * Fetched at most once per UTC day - the derived grid (not the source imagery) is cached to disk
 * so repeat launches on the same day never hit the network.
 */
object CloudRepository {

    @Volatile
    private var cache: List<CloudContour>? = null

    suspend fun loadCloudCells(context: Context): List<CloudContour> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            val grid = readCachedGrid(cacheFile) ?: fetchAndCacheGrid(cacheFile)
            if (grid == null) {
                // Fetch failed (network error surviving the retries below, or an undecodable
                // response) - deliberately leave `cache` null rather than caching an emptyList()
                // "result". `cache != null` is what short-circuits every future call in this
                // process, so caching a failure as an empty success would silently disable the
                // cloud layer for the rest of the app's lifetime instead of retrying next time
                // it's requested (e.g. the user toggling the Setup-screen checkbox off and on).
                return@withContext emptyList()
            }
            val contours = toContours(grid)
            cache = contours
            contours
        }
    }

    private fun utcDateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun readCachedGrid(file: File): Array<FloatArray>? {
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != utcDateFormat().format(Date())) return null
            Array(CLOUD_GRID_ROWS) { r -> lines[1 + r].split(",").map { it.toFloat() }.toFloatArray() }
        } catch (e: Exception) {
            null
        }
    }

    // Mirrors TleRepository.forceRefreshSatelliteTle's retry pattern: a couple of quick retries
    // absorb a transient timeout/hiccup instead of giving up on the very first failure, since this
    // only runs once a day and a failure here used to mean no clouds at all until app restart.
    private suspend fun fetchWorldTileWithRetry(dateIso: String, tileCol: Int): ByteArray? {
        repeat(3) { attempt ->
            try {
                return GibsCloudApi.fetchWorldTile(dateIso, tileCol)
            } catch (e: Exception) {
                if (attempt < 2) delay(800L * (attempt + 1))
            }
        }
        return null
    }

    private suspend fun fetchAndCacheGrid(cacheFile: File): Array<FloatArray>? {
        val grid = try {
            val fetchDate = utcDateFormat().format(
                Date(System.currentTimeMillis() - FETCH_LOOKBACK_DAYS * 24L * 60 * 60 * 1000)
            )
            val westBytes = fetchWorldTileWithRetry(fetchDate, 0) ?: return null
            val eastBytes = fetchWorldTileWithRetry(fetchDate, 1) ?: return null
            val west = BitmapFactory.decodeByteArray(westBytes, 0, westBytes.size) ?: return null
            val east = BitmapFactory.decodeByteArray(eastBytes, 0, eastBytes.size) ?: return null

            // Sample at exact grid-point positions (not cell-area centers): marching squares
            // needs point samples on a regular lattice, not area averages.
            val result = Array(CLOUD_GRID_ROWS) { FloatArray(CLOUD_GRID_COLS) }
            val lonStep = 360.0 / CLOUD_GRID_COLS
            val latStep = 180.0 / (CLOUD_GRID_ROWS - 1)
            for (row in 0 until CLOUD_GRID_ROWS) {
                val latDeg = 90.0 - row * latStep
                val fracDown = (90.0 - latDeg) / 180.0
                val cy = (fracDown * (west.height - 1)).toInt().coerceIn(0, west.height - 1)
                for (col in 0 until CLOUD_GRID_COLS) {
                    val lonDeg = -180.0 + col * lonStep
                    val bitmap: Bitmap
                    val fracAcross: Double
                    if (lonDeg < 0) {
                        bitmap = west
                        fracAcross = (lonDeg + 180.0) / 180.0
                    } else {
                        bitmap = east
                        fracAcross = lonDeg / 180.0
                    }
                    val cx = (fracAcross * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
                    result[row][col] = sampleCloudinessAround(bitmap, cx, cy) ?: NO_DATA
                }
            }
            west.recycle()
            east.recycle()
            fillGaps(result)
            result
        } catch (e: Exception) {
            null
        }

        if (grid != null) {
            try {
                cacheFile.printWriter().use { out ->
                    out.println(utcDateFormat().format(Date()))
                    grid.forEach { row -> out.println(row.joinToString(",")) }
                }
            } catch (e: Exception) {
                // Non-fatal: just means we re-fetch next launch instead of using a disk cache.
            }
        }
        return grid
    }

    /** Returns null if every sampled pixel in the neighborhood is a no-data swath gap. */
    private fun sampleCloudinessAround(bitmap: Bitmap, cx: Int, cy: Int): Float? {
        var sum = 0f
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val px = (cx + dx).coerceIn(0, bitmap.width - 1)
                val py = (cy + dy).coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                if (maxC < 12) continue // near-black: satellite swath no-data gap
                val brightness = (r + g + b) / (3f * 255f)
                val saturation = (maxC - minC).toFloat() / maxC
                sum += (brightness * (1f - saturation)).coerceIn(0f, 1f)
                count++
            }
        }
        return if (count == 0) null else sum / count
    }

    /**
     * Fills NO_DATA points with the average of the nearest ring of points that do have data,
     * searching outward (and wrapping around the antimeridian, since longitude is cyclic) until
     * something is found. Ensures the rendered layer has no "holes" shaped like that day's
     * satellite swath gaps.
     */
    private fun fillGaps(grid: Array<FloatArray>) {
        val rows = grid.size
        val cols = grid[0].size
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (grid[row][col] != NO_DATA) continue
                var radius = 1
                while (radius <= max(rows, cols)) {
                    var sum = 0f
                    var count = 0
                    for (dr in -radius..radius) {
                        val r = row + dr
                        if (r !in 0 until rows) continue
                        for (dc in -radius..radius) {
                            if (max(abs(dr), abs(dc)) != radius) continue // ring perimeter only
                            val c = ((col + dc) % cols + cols) % cols
                            val v = grid[r][c]
                            if (v != NO_DATA) {
                                sum += v
                                count++
                            }
                        }
                    }
                    if (count > 0) {
                        grid[row][col] = sum / count
                        break
                    }
                    radius++
                }
            }
        }
    }

    private fun toContours(grid: Array<FloatArray>): List<CloudContour> {
        val contours = ArrayList<CloudContour>()
        extractContourPolygons(grid, THRESHOLD_LIGHT).forEach { contours.add(CloudContour(it, ALPHA_LIGHT)) }
        extractContourPolygons(grid, THRESHOLD_DENSE).forEach { contours.add(CloudContour(it, ALPHA_DENSE)) }
        return contours
    }

    /**
     * Marching squares over the point grid, extracting one small polygon per grid cell that
     * straddles or sits fully inside the threshold. Adjacent "fully inside" cells share exact
     * edges, so they render as one seamless connected region instead of independent shapes;
     * only cells that straddle the threshold get a smoothed, edge-interpolated partial polygon.
     * Longitude wraps at the antimeridian; latitude does not (rows stop at the poles).
     */
    private fun extractContourPolygons(grid: Array<FloatArray>, threshold: Float): List<List<Vec3>> {
        val rows = grid.size
        val cols = grid[0].size
        val latStep = 180.0 / (rows - 1)
        val lonStep = 360.0 / cols

        fun gridPoint(row: Int, col: Int): Vec3 =
            latLonDegToUnitSphere(90.0 - row * latStep, -180.0 + col * lonStep)

        fun edgePoint(rowA: Int, colA: Int, rowB: Int, colB: Int): Vec3 {
            val a = grid[rowA][colA]
            val b = grid[rowB][colB]
            val t = ((threshold - a) / (b - a)).toDouble().coerceIn(0.0, 1.0)
            val latA = 90.0 - rowA * latStep
            val latB = 90.0 - rowB * latStep
            val lonA = -180.0 + colA * lonStep
            var lonB = -180.0 + colB * lonStep
            if (colB == 0 && colA == cols - 1) lonB += 360.0 // antimeridian wrap
            return latLonDegToUnitSphere(latA + (latB - latA) * t, lonA + (lonB - lonA) * t)
        }

        val polygons = ArrayList<List<Vec3>>()
        for (row in 0 until rows - 1) {
            for (col in 0 until cols) {
                val colNext = (col + 1) % cols
                val vTL = grid[row][col]
                val vTR = grid[row][colNext]
                val vBR = grid[row + 1][colNext]
                val vBL = grid[row + 1][col]

                val caseIndex = (if (vTL >= threshold) 1 else 0) or
                    (if (vTR >= threshold) 2 else 0) or
                    (if (vBR >= threshold) 4 else 0) or
                    (if (vBL >= threshold) 8 else 0)
                if (caseIndex == 0) continue

                // This runs once per day in the background, not per frame, so there's no need to
                // bother computing edge points lazily even though not every case uses all four.
                val cTL = gridPoint(row, col)
                val cTR = gridPoint(row, colNext)
                val cBR = gridPoint(row + 1, colNext)
                val cBL = gridPoint(row + 1, col)
                val eTop = edgePoint(row, col, row, colNext)
                val eRight = edgePoint(row, colNext, row + 1, colNext)
                val eBottom = edgePoint(row + 1, col, row + 1, colNext)
                val eLeft = edgePoint(row, col, row + 1, col)

                when (caseIndex) {
                    1 -> polygons.add(listOf(cTL, eTop, eLeft))
                    2 -> polygons.add(listOf(eTop, cTR, eRight))
                    3 -> polygons.add(listOf(cTL, cTR, eRight, eLeft))
                    4 -> polygons.add(listOf(eRight, cBR, eBottom))
                    5 -> {
                        // Ambiguous saddle: rendered as two disjoint triangles rather than
                        // resolving via an asymptotic decider - a fine simplification here.
                        polygons.add(listOf(cTL, eTop, eLeft))
                        polygons.add(listOf(eRight, cBR, eBottom))
                    }
                    6 -> polygons.add(listOf(eTop, cTR, cBR, eBottom))
                    7 -> polygons.add(listOf(cTL, cTR, cBR, eBottom, eLeft))
                    8 -> polygons.add(listOf(eBottom, cBL, eLeft))
                    9 -> polygons.add(listOf(cTL, eTop, eBottom, cBL))
                    10 -> {
                        polygons.add(listOf(eTop, cTR, eRight))
                        polygons.add(listOf(eBottom, cBL, eLeft))
                    }
                    11 -> polygons.add(listOf(cTL, cTR, eRight, eBottom, cBL))
                    12 -> polygons.add(listOf(eLeft, cBL, cBR, eRight))
                    13 -> polygons.add(listOf(cTL, eTop, eRight, cBR, cBL))
                    14 -> polygons.add(listOf(eTop, cTR, cBR, cBL, eLeft))
                    15 -> polygons.add(listOf(cTL, cTR, cBR, cBL))
                }
            }
        }
        return polygons
    }
}
