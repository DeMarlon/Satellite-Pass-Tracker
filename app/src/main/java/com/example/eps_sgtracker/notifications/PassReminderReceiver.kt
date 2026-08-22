package com.example.eps_sgtracker.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.eps_sgtracker.MainActivity
import com.example.eps_sgtracker.R
import com.example.eps_sgtracker.data.ReminderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val REMINDER_CHANNEL_ID = "pass_reminders"

// Safe to call repeatedly - createNotificationChannel is idempotent. IMPORTANCE_HIGH gives
// heads-up presentation with the channel's default notification sound, which the OS itself
// suppresses when the phone is muted or in Do Not Disturb - exactly the desired behavior
// ("notification + sound, if phone is not muted on OS level").
fun ensureReminderChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Pass reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications ahead of upcoming satellite passes"
        }
    )
}

class PassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val satelliteName = intent.getStringExtra(ReminderScheduler.EXTRA_SATELLITE_NAME) ?: "Satellite"
        val stationCode = intent.getStringExtra(ReminderScheduler.EXTRA_STATION_CODE) ?: "?"
        val aosMillis = intent.getLongExtra(ReminderScheduler.EXTRA_AOS_MILLIS, 0L)
        val leadMinutes = intent.getIntExtra(ReminderScheduler.EXTRA_LEAD_MINUTES, 0)
        val reminderKey = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_KEY)

        ensureReminderChannel(context)

        // POST_NOTIFICATIONS is a runtime permission on API 33+ (auto-granted below); if the user
        // denied it there is nothing useful to do here beyond not crashing.
        val canNotify = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            val aosText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(aosMillis))
            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
                // The themed-icon stencil vector doubles perfectly as a status-bar small icon:
                // both contexts want a pure-alpha single-color glyph.
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Upcoming pass: $satelliteName")
                .setContentText("AOS over $stationCode at $aosText (in $leadMinutes min)")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .build()
            NotificationManagerCompat.from(context)
                .notify(reminderKey?.hashCode() ?: aosMillis.toInt(), notification)
        }

        // A fired reminder is spent - drop the persisted entry so the store and the Pass List's
        // bell indicators don't accumulate stale state. goAsync() keeps the process alive for
        // the short DataStore write.
        if (reminderKey != null) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ReminderRepository(context.applicationContext).removeReminder(reminderKey)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
