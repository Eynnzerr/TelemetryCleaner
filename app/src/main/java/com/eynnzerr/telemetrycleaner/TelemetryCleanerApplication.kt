package com.eynnzerr.telemetrycleaner

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.packagemanager.DhizukuBridge
import com.eynnzerr.telemetrycleaner.data.packagemanager.DhizukuController
import com.eynnzerr.telemetrycleaner.data.packagemanager.PrivilegeSessionManager
import com.eynnzerr.telemetrycleaner.data.preferences.AutoCleanupHistoryStore
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import com.eynnzerr.telemetrycleaner.feature.cleaner.CleanerViewModel
import com.eynnzerr.telemetrycleaner.feature.history.AutoCleanupHistoryViewModel
import com.eynnzerr.telemetrycleaner.feature.packages.PackageDirectoryViewModel
import com.eynnzerr.telemetrycleaner.feature.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** One device-local Preferences DataStore for settings and automatic cleanup history. */
private val Context.cleanerDataStore by preferencesDataStore(name = "cleaner_settings")

/** Shares settings, the privilege session, and repository across screens and the foreground service. */
private val cleanerModule = module {
    single { androidContext().cleanerDataStore }
    single { CleanerSettings(get()) }
    single { AutoCleanupHistoryStore(get()) }
    single { PrivilegeSessionManager(androidContext(), get()) }
    single { DhizukuBridge(androidContext()) }
    single<DhizukuController> { get<DhizukuBridge>() }
    single { CleanerRepository(androidContext(), get(), get(), get()) }
    viewModelOf(::CleanerViewModel)
    viewModel { PackageDirectoryViewModel(get<CleanerRepository>(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModelOf(::AutoCleanupHistoryViewModel)
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
