package com.example.eps_sgtracker.data

import android.content.Context
import com.example.eps_sgtracker.model.CountryLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET_FILE_NAME = "ne_110m_admin_0_countries.geojson"

/** Loads country name/label-placement data from Natural Earth's 110m admin-0 countries layer. */
object CountryLabelRepository {

    @Volatile
    private var cache: List<CountryLabel>? = null

    suspend fun loadCountryLabels(context: Context): List<CountryLabel> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val labels = parseCountryLabels(context, ASSET_FILE_NAME)
            cache = labels
            labels
        }
    }
}
