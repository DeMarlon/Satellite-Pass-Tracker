package com.example.eps_sgtracker.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.satelliteDataStore by preferencesDataStore(
    name = "satellite_settings",
    // See ReminderRepository for why: an unparseable file would otherwise throw out of .data,
    // through stateIn, into viewModelScope - which has no CoroutineExceptionHandler - on launch.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class SatelliteRepository(private val context: Context) {

    companion object {
        private val TRACKED_SATS_KEY = stringSetPreferencesKey("tracked_norad_ids")
        private val TRACKED_ORDER_KEY = stringPreferencesKey("tracked_norad_order")
        private val HIDDEN_SATS_KEY = stringSetPreferencesKey("hidden_norad_ids")
        private val ALLOWED_STATIONS_KEY = stringSetPreferencesKey("satellite_allowed_stations")
        private val ALLOWED_STATIONS_CONFIGURED_KEY = stringSetPreferencesKey("satellite_allowed_stations_configured")
        private val ORBIT_OFFSETS_KEY = stringSetPreferencesKey("satellite_orbit_offsets")
    }

    // Guards the READ path, which the corruptionHandler does not: .data still throws IOException
    // for ordinary I/O failure. Every public flow below maps off this, never .data directly.
    private val prefs: Flow<Preferences> = context.satelliteDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    // Manual per-satellite correction added to the computed revolution number, as "noradId|offset"
    // pairs (same flattened-pair convention as the allow-lists above). Negative values are valid.
    // Exists because operators sometimes count revolutions from a different reference entirely
    // (from launch, or from a different reference node), which no TLE-derived formula can recover -
    // see propagatedOrbitNumber. Absent entry means no correction.
    val orbitOffsetsFlow: Flow<Map<Int, Int>> = prefs.map { preferences ->
        val raw = preferences[ORBIT_OFFSETS_KEY] ?: emptySet()
        raw.mapNotNull { entry ->
            val idx = entry.lastIndexOf('|')
            if (idx < 0) return@mapNotNull null
            val noradId = entry.substring(0, idx).toIntOrNull() ?: return@mapNotNull null
            val offset = entry.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            noradId to offset
        }.toMap()
    }

    suspend fun setOrbitOffset(noradId: Int, offset: Int) {
        context.satelliteDataStore.edit { preferences ->
            val prefix = "$noradId|"
            val current = preferences[ORBIT_OFFSETS_KEY] ?: emptySet()
            val without = current.filterNot { it.startsWith(prefix) }.toSet()
            // 0 is the default, so it's stored as absence rather than an explicit entry - keeps the
            // set from accumulating a no-op row for every satellite the user ever opened.
            preferences[ORBIT_OFFSETS_KEY] =
                if (offset == 0) without else without + "$noradId|$offset"
        }
    }

    // Emits the current tracked NORAD IDs, in the user's drag-to-reorder order.
    //
    // The order lives in its own key rather than in TRACKED_SATS_KEY because that one is a Set,
    // which has no ordering contract at all - the fact that today's display order happens to match
    // insertion order is an accident of LinkedHashSet plus how DataStore round-trips a string set,
    // and a Set can't express an arbitrary user-chosen permutation regardless. Keeping the two
    // separate also means add/remove stay untouched.
    //
    // Composed rather than read straight from TRACKED_ORDER_KEY so the two can never disagree:
    // ordered IDs first (intersected with what's actually tracked, dropping stale entries left
    // behind by a removal), then any tracked ID the order doesn't mention yet. That second part is
    // what makes this work with no migration - an existing install has no order key at all and
    // simply keeps its current order, and a newly added satellite always appears (at the end)
    // even before the next reorder rewrites the key.
    val trackedSatelliteIdsFlow: Flow<List<Int>> = prefs.map { preferences ->
        val trackedIds = (preferences[TRACKED_SATS_KEY] ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
        val savedOrder = (preferences[TRACKED_ORDER_KEY] ?: "")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        savedOrder.filter { it in trackedIds } + trackedIds.filterNot { it in savedOrder }
    }

    // Tracked-but-hidden NORAD IDs (mirrors ground stations' activeStationCodesFlow, inverted: a
    // *hidden* set rather than a *visible* one, so newly-added satellites are visible by default
    // without needing to be explicitly seeded into a "visible" set on add).
    val hiddenSatelliteIdsFlow: Flow<Set<Int>> = prefs.map { preferences ->
        val stringSet = preferences[HIDDEN_SATS_KEY] ?: emptySet()
        stringSet.mapNotNull { it.toIntOrNull() }.toSet()
    }

    // Per-satellite ground station allow-list, stored as one "noradId|code" entry per allowed
    // (satellite, station) pairing - same flattened-pair convention as everything else in this
    // file. A satellite absent from allowedStationsConfiguredFlow has never had this customized,
    // so callers should treat it as "all currently active stations apply" (matching behavior
    // from before this feature existed); the configured-marker set is what makes an *explicit,
    // possibly empty* selection distinguishable from "never touched" - without it there'd be no
    // way to persist "no stations for this satellite" without it looking identical to "not yet
    // configured, defaults to everything".
    val allowedStationsFlow: Flow<Map<Int, Set<String>>> = prefs.map { preferences ->
        val raw = preferences[ALLOWED_STATIONS_KEY] ?: emptySet()
        raw.mapNotNull { entry ->
            val idx = entry.lastIndexOf('|')
            if (idx < 0) return@mapNotNull null
            val noradId = entry.substring(0, idx).toIntOrNull() ?: return@mapNotNull null
            noradId to entry.substring(idx + 1)
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    val allowedStationsConfiguredFlow: Flow<Set<Int>> = prefs.map { preferences ->
        val stringSet = preferences[ALLOWED_STATIONS_CONFIGURED_KEY] ?: emptySet()
        stringSet.mapNotNull { it.toIntOrNull() }.toSet()
    }

    // Add a NORAD ID to persistent storage
    suspend fun saveSatelliteId(noradId: Int) {
        context.satelliteDataStore.edit { preferences ->
            val currentSet = preferences[TRACKED_SATS_KEY] ?: emptySet()
            preferences[TRACKED_SATS_KEY] = currentSet + noradId.toString()
        }
    }

    // Remove a NORAD ID from persistent storage, along with any hidden-state flag and per-station
    // allow-list it had - no sense leaving orphaned entries behind for an ID that's no longer
    // tracked at all.
    suspend fun removeSatelliteId(noradId: Int) {
        context.satelliteDataStore.edit { preferences ->
            val currentSet = preferences[TRACKED_SATS_KEY] ?: emptySet()
            preferences[TRACKED_SATS_KEY] = currentSet - noradId.toString()
            val hiddenSet = preferences[HIDDEN_SATS_KEY] ?: emptySet()
            preferences[HIDDEN_SATS_KEY] = hiddenSet - noradId.toString()
            val prefix = "$noradId|"
            val allowedSet = preferences[ALLOWED_STATIONS_KEY] ?: emptySet()
            preferences[ALLOWED_STATIONS_KEY] = allowedSet.filterNot { it.startsWith(prefix) }.toSet()
            val configuredSet = preferences[ALLOWED_STATIONS_CONFIGURED_KEY] ?: emptySet()
            preferences[ALLOWED_STATIONS_CONFIGURED_KEY] = configuredSet - noradId.toString()
            val offsets = preferences[ORBIT_OFFSETS_KEY] ?: emptySet()
            preferences[ORBIT_OFFSETS_KEY] = offsets.filterNot { it.startsWith(prefix) }.toSet()
        }
    }

    // Persists the user's drag-to-reorder result. Stored verbatim; trackedSatelliteIdsFlow is what
    // reconciles it against the tracked set, so this doesn't need to filter or validate.
    suspend fun setSatelliteOrder(orderedIds: List<Int>) {
        context.satelliteDataStore.edit { preferences ->
            preferences[TRACKED_ORDER_KEY] = orderedIds.joinToString(",")
        }
    }

    suspend fun setSatelliteHidden(noradId: Int, hidden: Boolean) {
        context.satelliteDataStore.edit { preferences ->
            val currentSet = preferences[HIDDEN_SATS_KEY] ?: emptySet()
            preferences[HIDDEN_SATS_KEY] = if (hidden) currentSet + noradId.toString() else currentSet - noradId.toString()
        }
    }

    // Always marks the satellite as explicitly configured, even if [codes] is empty - an empty
    // save means "no stations should apply to this satellite", which is different from "never
    // configured, defaults to all active stations".
    suspend fun setAllowedStations(noradId: Int, codes: Set<String>) {
        context.satelliteDataStore.edit { preferences ->
            val prefix = "$noradId|"
            val currentPairs = preferences[ALLOWED_STATIONS_KEY] ?: emptySet()
            preferences[ALLOWED_STATIONS_KEY] =
                currentPairs.filterNot { it.startsWith(prefix) }.toSet() + codes.map { "$noradId|$it" }
            val configuredSet = preferences[ALLOWED_STATIONS_CONFIGURED_KEY] ?: emptySet()
            preferences[ALLOWED_STATIONS_CONFIGURED_KEY] = configuredSet + noradId.toString()
        }
    }

    // Mirrors the by-NORAD-ID pruning in removeSatelliteId(), but by station code instead: called
    // when a custom ground station is deleted, so no satellite's allow-list is left referencing a
    // station code that no longer exists. Without this, deleting a custom station and later
    // creating a new one that happens to reuse the same code would silently inherit whichever
    // satellites' allow-lists still had stale "noradId|code" entries from the deleted station.
    suspend fun removeStationFromAllAllowLists(code: String) {
        context.satelliteDataStore.edit { preferences ->
            val suffix = "|$code"
            val currentPairs = preferences[ALLOWED_STATIONS_KEY] ?: emptySet()
            preferences[ALLOWED_STATIONS_KEY] = currentPairs.filterNot { it.endsWith(suffix) }.toSet()
        }
    }
}