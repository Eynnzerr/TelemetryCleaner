package com.eynnzerr.telemetrycleaner.feature.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.CriticalSystemPackages
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.isExactPackageName
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Validation or device-lookup outcome inside the add-custom-package dialog. */
internal sealed interface AddCustomPackageStatus {
    data object Idle : AddCustomPackageStatus
    data object Verifying : AddCustomPackageStatus
    data object InvalidFormat : AddCustomPackageStatus
    data object CriticalPackage : AddCustomPackageStatus
    data object AlreadyBundled : AddCustomPackageStatus
    data object AlreadyAdded : AddCustomPackageStatus
    data object NeedsConnection : AddCustomPackageStatus
    data class NotFound(val packageName: String) : AddCustomPackageStatus
    data object LookupFailed : AddCustomPackageStatus
    data object SaveFailed : AddCustomPackageStatus
}

/** Immutable screen state for [com.eynnzerr.telemetrycleaner.ui.packages.PackageDirectoryScreen]. */
internal data class PackageDirectoryUiState(
    val customPackageNames: List<String> = emptyList(),
    val connection: PrivilegeConnection = PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.CONNECTING),
    val addDialogOpen: Boolean = false,
    val input: String = "",
    val status: AddCustomPackageStatus = AddCustomPackageStatus.Idle,
)

/** One-shot events emitted by [PackageDirectoryViewModel]. */
internal sealed interface PackageDirectoryEvent {
    data object SaveFailed : PackageDirectoryEvent
}

/**
 * Validates a user-entered package identifier before querying Android's package manager.
 * Rejects malformed identifiers, protected core system packages, bundled entries, and duplicates.
 */
internal fun validateCustomPackageInput(
    rawInput: String,
    existingCustomNames: Set<String>,
): AddCustomPackageStatus? {
    val trimmed = rawInput.trim()
    return when {
        !isExactPackageName(trimmed) -> AddCustomPackageStatus.InvalidFormat
        trimmed in CriticalSystemPackages.names -> AddCustomPackageStatus.CriticalPackage
        trimmed in PackageDirectory.variants -> AddCustomPackageStatus.AlreadyBundled
        trimmed in existingCustomNames -> AddCustomPackageStatus.AlreadyAdded
        else -> null
    }
}

/** Owns custom test-package verification, addition, and removal within the component directory. */
internal class PackageDirectoryViewModel(
    private val connectionFlow: StateFlow<PrivilegeConnection>,
    private val lookupPackage: suspend (String) -> PackageEntry?,
    private val setCustomPackage: suspend (String, Boolean) -> Boolean,
    settings: CleanerSettings,
) : ViewModel() {
    constructor(
        repository: CleanerRepository,
        settings: CleanerSettings,
    ) : this(
        connectionFlow = repository.connection,
        lookupPackage = repository::lookupPackage,
        setCustomPackage = repository::setCustomPackage,
        settings = settings,
    )

    private val eventChannel = Channel<PackageDirectoryEvent>(Channel.BUFFERED)
    val events: Flow<PackageDirectoryEvent> = eventChannel.receiveAsFlow()

    private val addDialogOpen = MutableStateFlow(false)
    private val inputFlow = MutableStateFlow("")
    private val statusFlow = MutableStateFlow<AddCustomPackageStatus>(AddCustomPackageStatus.Idle)

    private var verifyJob: Job? = null
    private var activeRequestId: Long = 0L

    private val customPackageNames = settings.preferences
        .map { it.customPackageNames }
        .distinctUntilChanged()

    val state: StateFlow<PackageDirectoryUiState> = combine(
        customPackageNames,
        connectionFlow,
        addDialogOpen,
        inputFlow,
        statusFlow,
    ) { customNames, connection, dialogOpen, input, status ->
        PackageDirectoryUiState(
            customPackageNames = customNames.sorted(),
            connection = connection,
            addDialogOpen = dialogOpen,
            input = input,
            status = status,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PackageDirectoryUiState(connection = connectionFlow.value),
    )

    /** Opens a fresh exact-package-name dialog. */
    fun openAddDialog() {
        activeRequestId += 1
        verifyJob?.cancel()
        verifyJob = null
        inputFlow.value = ""
        statusFlow.value = AddCustomPackageStatus.Idle
        addDialogOpen.value = true
    }

    /** Closes the dialog and cancels any in-flight verification. */
    fun dismissAddDialog() {
        activeRequestId += 1
        verifyJob?.cancel()
        verifyJob = null
        addDialogOpen.value = false
        statusFlow.value = AddCustomPackageStatus.Idle
    }

    /** Updates the entered package identifier and clears stale validation errors. */
    fun onInputChange(value: String) {
        if (verifyJob != null) {
            activeRequestId += 1
            verifyJob?.cancel()
            verifyJob = null
        }
        inputFlow.value = value
        if (statusFlow.value != AddCustomPackageStatus.Idle) {
            statusFlow.value = AddCustomPackageStatus.Idle
        }
    }

    /** Validates syntax, verifies device presence via Shizuku/Root, and persists the custom package. */
    fun submitAdd() {
        val packageName = inputFlow.value.trim()
        val current = state.value
        val localError = validateCustomPackageInput(packageName, current.customPackageNames.toSet())
        if (localError != null) {
            statusFlow.value = localError
            return
        }
        if (connectionFlow.value.status != PrivilegeStatus.READY) {
            statusFlow.value = AddCustomPackageStatus.NeedsConnection
            return
        }

        val requestId = ++activeRequestId
        verifyJob?.cancel()
        statusFlow.value = AddCustomPackageStatus.Verifying
        verifyJob = viewModelScope.launch {
            val found = try {
                lookupPackage(packageName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isActive && activeRequestId == requestId) {
                    statusFlow.value = AddCustomPackageStatus.LookupFailed
                }
                return@launch
            }
            if (!isActive || activeRequestId != requestId) return@launch
            if (found == null) {
                statusFlow.value = AddCustomPackageStatus.NotFound(packageName)
                return@launch
            }
            val saved = setCustomPackage(packageName, true)
            if (!isActive || activeRequestId != requestId) return@launch
            if (saved) {
                addDialogOpen.value = false
                inputFlow.value = ""
                statusFlow.value = AddCustomPackageStatus.Idle
            } else {
                statusFlow.value = AddCustomPackageStatus.SaveFailed
            }
        }
    }

    /** Removes a custom package from the directory and notifies on persistence failures. */
    fun removeCustomPackage(packageName: String) {
        viewModelScope.launch {
            if (!setCustomPackage(packageName, false)) {
                eventChannel.send(PackageDirectoryEvent.SaveFailed)
            }
        }
    }

    override fun onCleared() {
        eventChannel.close()
    }
}
