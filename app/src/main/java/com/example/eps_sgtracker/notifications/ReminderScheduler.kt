package com.example.eps_sgtracker.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.eps_sgtracker.data.PassReminder

// Schedules/cancels the AlarmManager alarms behind pass reminders. Exact alarms need the
// user-revocable SCHEDULE_EXACT_ALARM special permission on API 31+; if it hasn't been granted
// this falls back to an inexact alarm, which is far better than silently scheduling nothing or
// crashing with a SecurityException.
//
// Be clear-eyed about that fallback though: setAndAllowWhileIdle is rate-limited by Doze to roughly
// one firing per 9-15 minute window, so a reminder set 5 minutes before AOS can arrive after the
// pass has already ended. That is why canScheduleExact is exposed - the UI offers the user a route
// to grant the permission rather than silently degrading, since on API 33+ it is NOT granted by
// default to a fresh install.
object ReminderScheduler {

    const val EXTRA_SATELLITE_NAME = "satellite_name"
    const val EXTRA_STATION_CODE = "station_code"
    const val EXTRA_AOS_MILLIS = "aos_millis"
    const val EXTRA_LEAD_MINUTES = "lead_minutes"
    const val EXTRA_REMINDER_KEY = "reminder_key"

    /**
     * Whether exact alarms are currently permitted. Always true below API 31, where the permission
     * did not exist; from API 33 onward SCHEDULE_EXACT_ALARM is not granted by default to a fresh
     * install, so this is false for most new users until they explicitly opt in via Settings.
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun schedule(context: Context, reminder: PassReminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(context, reminder)
        val canExact = canScheduleExact(context)
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtMillis, pendingIntent)
        }
    }

    fun cancel(context: Context, reminder: PassReminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context, reminder))
    }

    private fun buildPendingIntent(context: Context, reminder: PassReminder): PendingIntent {
        val intent = Intent(context, PassReminderReceiver::class.java).apply {
            putExtra(EXTRA_SATELLITE_NAME, reminder.satelliteName)
            putExtra(EXTRA_STATION_CODE, reminder.stationCode)
            putExtra(EXTRA_AOS_MILLIS, reminder.aosMillis)
            putExtra(EXTRA_LEAD_MINUTES, reminder.leadMinutes)
            putExtra(EXTRA_REMINDER_KEY, reminder.key)
        }
        // Request code derived from the reminder key so each pass's reminder is an independent
        // alarm, while re-scheduling the SAME pass (changed lead time) replaces its predecessor
        // via FLAG_UPDATE_CURRENT instead of stacking a second alarm.
        return PendingIntent.getBroadcast(
            context,
            reminder.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
