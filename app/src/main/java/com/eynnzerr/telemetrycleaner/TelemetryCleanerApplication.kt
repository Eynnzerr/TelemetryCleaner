package com.eynnzerr.telemetrycleaner

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.eynnzerr.telemetrycleaner.data.AutoCleanupHistoryStore
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.CleanerSettings
import com.eynnzerr.telemetrycleaner.feature.CleanerViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** One device-local Preferences DataStore for settings and automatic cleanup history. */
private val Context.cleanerDataStore by preferencesDataStore(name = "cleaner_settings")

/** Shares settings, the selected backend and its session across the screen and foreground service. */
private val cleanerModule = module {
    single { androidContext().cleanerDataStore }
    single { CleanerSettings(get()) }
    single { AutoCleanupHistoryStore(get()) }
    single { CleanerRepository(androidContext(), get()) }
    viewModelOf(::CleanerViewModel)
}

/** Starts the process-wide Koin container before the first activity is created. */
class TelemetryCleanerApplication : Application() {
    /** Makes the application context and cleaner dependencies available to Android entry points. */
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TelemetryCleanerApplication)
            modules(cleanerModule)
        }
    }
}
