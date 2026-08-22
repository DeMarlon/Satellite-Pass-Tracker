package com.example.eps_sgtracker.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches NASA GIBS' daily MODIS true-color mosaic in the EPSG:4326 (equirectangular) projection,
 * at the coarsest zoom level (0): just two 512x512 tiles side by side cover the entire globe.
 * No API key required.
 */
object GibsCloudApi {
    // See CelestrakApi's identical client for why callTimeout specifically (not just the default
    // per-phase timeouts) is needed here.
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // tileCol 0 = western hemisphere (lon -180..0), tileCol 1 = eastern hemisphere (lon 0..180).
    suspend fun fetchWorldTile(dateIso: String, tileCol: Int): ByteArray = withContext(Dispatchers.IO) {
        val url = "https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/" +
            "MODIS_Terra_CorrectedReflectance_TrueColor/default/$dateIso/250m/0/0/$tileCol.jpg"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected response code: ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }
}
