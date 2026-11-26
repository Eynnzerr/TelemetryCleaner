package com.eynnzerr.telemetrycleaner.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.DhizukuStatus
import com.eynnzerr.telemetrycleaner.data.packagemanager.DhizukuBridge

/** Returns the localized action label for resolving a non-ready [DhizukuStatus]. */
@StringRes
internal fun DhizukuStatus.actionLabel(): Int = when (this) {
    DhizukuStatus.PERMISSION_REQUIRED -> R.string.authorize
    DhizukuStatus.INACTIVE -> R.string.open_dhizuku
    DhizukuStatus.MISSING -> R.string.get_dhizuku
    DhizukuStatus.ERROR -> R.string.try_again
    DhizukuStatus.READY -> R.string.authorize
}

/** Runs the contextual setup, launch, authorization, or retry action for [status]. */
internal fun performDhizukuStatusAction(
    context: Context,
    status: DhizukuStatus,
    onRequestPermission: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    when (status) {
        DhizukuStatus.PERMISSION_REQUIRED -> onRequestPermission()
        DhizukuStatus.INACTIVE -> {
            val launch = context.packageManager
                .getLaunchIntentForPackage(DhizukuBridge.OFFICIAL_DHIZUKU_PACKAGE)
            try {
                context.startActivity(
                    launch ?: Intent(Intent.ACTION_VIEW, DhizukuBridge.DHIZUKU_PROJECT_URL.toUri()),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.settings_open_link_failed, Toast.LENGTH_SHORT).show()
            }
        }
        DhizukuStatus.MISSING -> {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, DhizukuBridge.DHIZUKU_PROJECT_URL.toUri()),
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.settings_open_link_failed, Toast.LENGTH_SHORT).show()
            }
        }
        DhizukuStatus.ERROR -> onRefreshStatus()
        DhizukuStatus.READY -> Unit
    }
}
