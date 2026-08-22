package com.example.eps_sgtracker.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.eps_sgtracker.model.GroundStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Extension property to instantiate DataStore on Context safely
private val Context.dataStore by preferencesDataStore(
    name = "tracker_settings",
    // See ReminderRepository for why: an unparseable file would otherwise throw out of .data,
    // through stateIn, into viewModelScope - which has no CoroutineExceptionHandler - on launch.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class GroundStationRepository(private val context: Context) {

    companion object {
        // Hardcoded predefined ground stations pool
        val predefinedStations = listOf(
            GroundStation(name = "Svalbard", code = "SVL", latitude = 78.2292, longitude = 15.3936),
            GroundStation(name = "McMurdo", code = "MCM", latitude = -77.8390, longitude = 166.6663),
            GroundStation(name = "Fucino", code = "FCN", latitude = 41.9775, longitude = 13.5994),
            GroundStation(name = "Lario", code = "LAR", latitude = 46.1576, longitude = 9.4094)
        )

        // DataStore key for custom station CSV serialization
        private val CUSTOM_STATIONS_KEY = stringSetPreferencesKey("custom_ground_stations")
        private val ACTIVE_STATION_CODES_KEY = stringSetPreferencesKey("active_station_codes")
    }

    // Emits a unified list: Predefined Stations + Custom Saved Stations
    // Guards the READ path, which the corruptionHandler does not: .data still throws IOException
    // for ordinary I/O failure. Both public flows map off this, never .data directly.
    private val prefs: Flow<Preferences> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val allStationsFlow: Flow<List<GroundStation>> = prefs.map { preferences ->
        val serializedCustomSet = preferences[CUSTOM_STATIONS_KEY] ?: emptySet()

        // Deserialize CSV strings back into concrete GroundStation objects
        val customStations = serializedCustomSet.mapNotNull { csvLine ->
            try {
                val parts = csvLine.split("|")
                GroundStation(
                    name = parts[0],
                    code = parts[1],
                    latitude = parts[2].toDouble(),
                    longitude = parts[3].toDouble(),
                    isCustom = true
                )
            } catch (e: Exception) {
                null // Skip malformed or corrupted storage items safely
            }
        }

        predefinedStations + customStations
    }

    val activeStationCodesFlow: Flow<Set<String>> = prefs.map { preferences ->
        preferences[ACTIVE_STATION_CODES_KEY] ?: emptySet()
    }

    // Persists a new custom station directly onto the local disk
    suspend fun saveCustomStation(station: GroundStation) {
        context.dataStore.edit { preferences ->
            val currentSet = preferences[CUSTOM_STATIONS_KEY] ?: emptySet()
            // Serialize to a simple flat CSV string format: Name|Code|Lat|Lon
            val serializedString = "${station.name}|${station.code}|${station.latitude}|${station.longitude}"
            preferences[CUSTOM_STATIONS_KEY] = currentSet + serializedString
        }
    }

    // Permanently removes a custom station's serialized entry - predefined stations (SVL, MCM,
    // FUC, LAR) aren't stored here at all, so this can never touch them. Also drops the code from
    // the active set in the same transaction so a deleted station can't linger as "active" with no
    // backing GroundStation object.
    suspend fun removeCustomStation(code: String) {
        context.dataStore.edit { preferences ->
            val currentSet = preferences[CUSTOM_STATIONS_KEY] ?: emptySet()
            preferences[CUSTOM_STATIONS_KEY] = currentSet.filterNot { csvLine ->
                csvLine.split("|").getOrNull(1) == code
            }.toSet()

            val activeSet = preferences[ACTIVE_STATION_CODES_KEY] ?: emptySet()
            preferences[ACTIVE_STATION_CODES_KEY] = activeSet - code
        }
    }

    suspend fun saveActiveStationCodes(codes: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[ACTIVE_STATION_CODES_KEY] = codes
        }
    }
}