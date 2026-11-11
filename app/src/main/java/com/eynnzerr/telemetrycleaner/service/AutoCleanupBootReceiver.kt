package com.eynnzerr.telemetrycleaner.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eynnzerr.telemetrycleaner.data.CleanerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Resumes a previously enabled monitor after a reboot or an app update. */
class AutoCleanupBootReceiver : BroadcastReceiver(), KoinComponent {
    /** Reads the opt-in from this Android user's application storage. */
    private val settings: CleanerSettings by inject()

    /** Responds to system broadcasts that permit starting the foreground service. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                AutoCleanupService.startIfEnabled(context.applicationContext, settings)
            } finally {
                pending.finish()
            }
        }
    }
}
