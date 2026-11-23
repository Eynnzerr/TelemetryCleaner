package com.eynnzerr.telemetrycleaner.data.preferences

import androidx.annotation.StringRes
import com.eynnzerr.telemetrycleaner.R

/** Returns the localized status label shared by settings and the foreground service notification. */
@StringRes
internal fun AutoCleanupStatus.label(): Int = when (this) {
    AutoCleanupStatus.OFF -> R.string.auto_cleanup_off
    AutoCleanupStatus.STARTING -> R.string.auto_cleanup_starting
    AutoCleanupStatus.WAITING_FOR_SHIZUKU -> R.string.auto_cleanup_waiting_for_shizuku
    AutoCleanupStatus.WAITING_FOR_ROOT -> R.string.auto_cleanup_waiting_for_root
    AutoCleanupStatus.CHECKING -> R.string.auto_cleanup_checking
    AutoCleanupStatus.MONITORING -> R.string.auto_cleanup_monitoring
    AutoCleanupStatus.NO_SPACES -> R.string.scope_empty
    AutoCleanupStatus.CHECK_FAILED -> R.string.auto_cleanup_check_failed
    AutoCleanupStatus.START_FAILED -> R.string.auto_cleanup_start_failed
}
