package com.example.eps_sgtracker.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.reminderDataStore by preferencesDataStore(
    name = "pass_reminders",
    // Replaces an unparseable file rather than throwing CorruptionException forever. Without it a
    // single truncated write - process killed mid-flush, disk full - bricks the app permanently:
    // the throw propagates out of .data into viewModelScope, which has no CoroutineExceptionHandler.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

// Stands in for the orbit number of a reminder written before the identity below included one.
// propagatedOrbitNumber always returns a value re-wrapped into 0..99999, so a negative sentinel can
// never collide with a real revolution.
private const val LEGACY_ORBIT_NUMBER = -1

// One scheduled pass reminder.
//
// A SatellitePass has no stable identity of its own - it is rebuilt from scratch on every recompute
// - so the composite noradId|stationCode|orbitNumber IS the reminder's identity. It deliberately
// does NOT include aosMillis, which it used to: a TLE refresh shifts a pass's AOS by a few seconds,
// and that minted a brand new key that matched nothing. The bell indicator vanished from the row,
// the dialog offered "Set" instead of "Update"/"Remove" so the reminder could no longer be
// cancelled, and setting it again scheduled a SECOND alarm - a different key hashes to a different
// PendingIntent request code - while the orphaned first one still fired. A revolution number names
// the same physical pass across refreshes, which removes all three.
//
// fireAtMillis still derives from the aosMillis captured when the reminder was set, so a later
// refresh leaves the alarm accurate to within that few-second shift - immaterial against a lead
// time measured in minutes.
//
// satelliteName is persisted rather than re-derived so the boot-time rescheduler and the
// notification itself never depend on a TLE fetch having happened.
data class PassReminder(
    val noradId: Int,
    val stationCode: String,
    val orbitNumber: Int,
    val aosMillis: Long,
    val leadMinutes: Int,
    val satelliteName: String
) {
    val key: String get() = "$noradId|$stationCode|$orbitNumber"
    val fireAtMillis: Long get() = aosMillis - leadMinutes * 60_000L
}

class ReminderRepository(private val context: Context) {

    companion object {
        private val REMINDERS_KEY = stringSetPreferencesKey("pass_reminders")

        // Serialized as noradId|stationCode|aosMillis|leadMinutes|orbitNumber|satelliteName - the
        // free-text name comes LAST so a (theoretical) '|' inside a satellite name can't shift the
        // numeric fields; the limit on split keeps any such name intact.
        private fun serialize(r: PassReminder): String =
            "${r.noradId}|${r.stationCode}|${r.aosMillis}|${r.leadMinutes}|${r.orbitNumber}|${r.satelliteName}"

        // Reads both the current six-field form and the five-field one written before the identity
        // gained an orbit number. A legacy entry is kept rather than discarded so its alarm - long
        // since scheduled under the old key's request code - still fires; it simply carries
        // LEGACY_ORBIT_NUMBER, which matches no real pass, so it shows no bell until pruneExpired
        // drops it at AOS. Reminders only ever cover upcoming passes, so that self-heals within
        // hours and needs no migration pass of its own.
        private fun deserialize(raw: String): PassReminder? {
            val parts = raw.split("|", limit = 6)
            if (parts.size < 5) return null

            val noradId = parts[0].toIntOrNull() ?: return null
            val aosMillis = parts[2].toLongOrNull() ?: return null
            val leadMinutes = parts[3].toIntOrNull() ?: return null

            // Field five is the orbit number in the current form and the first chunk of the
            // satellite name in the old one, so "does it parse as an integer" is what separates
            // them - and that stays correct even for the theoretical satellite name containing a
            // '|', which would otherwise split into six parts and be mistaken for the newer form.
            val orbitNumber = if (parts.size == 6) parts[4].toIntOrNull() else null

            return PassReminder(
                noradId = noradId,
                stationCode = parts[1],
                orbitNumber = orbitNumber ?: LEGACY_ORBIT_NUMBER,
                aosMillis = aosMillis,
                leadMinutes = leadMinutes,
                satelliteName = if (orbitNumber != null) parts[5] else parts.drop(4).joinToString("|")
            )
        }
    }

    // Guards the READ path, which the corruptionHandler above does not: .data still throws
    // IOException for ordinary I/O failure. Every public flow maps off this, never .data directly.
    private val prefs: Flow<Preferences> = context.reminderDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val remindersFlow: Flow<List<PassReminder>> = prefs.map { preferences ->
        (preferences[REMINDERS_KEY] ?: emptySet()).mapNotNull { deserialize(it) }
    }

    // Replaces any existing reminder for the same pass rather than accumulating a second one.
    suspend fun addReminder(reminder: PassReminder) {
        context.reminderDataStore.edit { preferences ->
            val current = preferences[REMINDERS_KEY] ?: emptySet()
            preferences[REMINDERS_KEY] =
                current.filterNot { deserialize(it)?.key == reminder.key }.toSet() + serialize(reminder)
        }
    }

    suspend fun removeReminder(key: String) {
        context.reminderDataStore.edit { preferences ->
            val current = preferences[REMINDERS_KEY] ?: emptySet()
            preferences[REMINDERS_KEY] = current.filterNot { deserialize(it)?.key == key }.toSet()
        }
    }

    // A reminder whose pass AOS is already behind us serves no purpose - called opportunistically
    // (app start, boot) so the store never accumulates stale entries.
    suspend fun pruneExpired(nowMillis: Long) {
        context.reminderDataStore.edit { preferences ->
            val current = preferences[REMINDERS_KEY] ?: emptySet()
            preferences[REMINDERS_KEY] = current.filterNot {
                val r = deserialize(it)
                r == null || r.aosMillis < nowMillis
            }.toSet()
        }
    }
}
