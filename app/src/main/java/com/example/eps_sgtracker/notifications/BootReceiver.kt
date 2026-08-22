package com.example.eps_sgtracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.eps_sgtracker.data.ReminderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// AlarmManager alarms do not survive a reboot - this re-arms every still-future reminder from the
// persisted store when the device comes back up, so a reminder set before an overnight reboot
// still fires.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = ReminderRepository(appContext)
                val now = System.currentTimeMillis()
                repository.pruneExpired(now)
                repository.remindersFlow.first()
                    .filter { it.aosMillis > now }
                    .forEach { ReminderScheduler.schedule(appContext, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
