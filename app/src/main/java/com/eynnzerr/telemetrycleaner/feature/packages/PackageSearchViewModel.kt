package com.eynnzerr.telemetrycleaner.feature.packages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eynnzerr.telemetrycleaner.data.CleanerRepository
import com.eynnzerr.telemetrycleaner.data.PackageDirectory
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PrivilegeBackend
import com.eynnzerr.telemetrycleaner.data.PrivilegeConnection
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.data.ScanSnapshot
import com.eynnzerr.telemetrycleaner.data.isExactPackageName
import com.eynnzerr.telemetrycleaner.data.preferences.CleanerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Loading state for the detail sheet opened from a search result. */
internal sealed interface PackageDetailsUiState {
    data object Closed : PackageDetailsUiState
    data class Loading(val packageName: String) : PackageDetailsUiState
    data class Loaded(val packageName: String, val snapshot: ScanSnapshot) : PackageDetailsUiState
    data class Failed(val packageName: String) : PackageDetailsUiState
}

/** Exact-name lookup outcome for package identifiers absent from the device inventory. */
internal sealed interface ExactLookupState {
    data object Idle : ExactLookupState
    data class Loading(val packageName: String) : ExactLookupState
    data class Found(val entry: PackageEntry) : ExactLookupState
    data class Missing(val packageName: String) : ExactLookupState
    data class Failed(val packageName: String) : ExactLookupState
}

/** Immutable screen state for [com.eynnzerr.telemetrycleaner.ui.packages.PackageSearchScreen]. */
internal data class PackageSearchUiState(
    val connection: PrivilegeConnection = PrivilegeConnection(PrivilegeBackend.SHIZUKU, PrivilegeStatus.CONNECTING),
    val term: String = "",
    val customPackageNames: Set<String> = emptySet(),
    val results: List<PackageEntry> = emptyList(),
    val inventoryLoading: Boolean = false,
    val inventoryFailed: Boolean = false,
    val exactLookup: ExactLookupState = ExactLookupState.Idle,
    val details: PackageDetailsUiState = PackageDetailsUiState.Closed,
) {
    /** Whether the exact-lookup error takes precedence over the general inventory failure banner. */
    val exactLookupFailed: Boolean get() = exactLookup is ExactLookupState.Failed

    /** Whether an exact-name search confirmed the package is absent on the device. */
    val exactLookupMissing: Boolean get() = exactLookup is ExactLookupState.Missing

    /** Whether results are still pending with nothing yet to display. */
    val isLoadingResults: Boolean
        get() = results.isEmpty() && (exactLookup is ExactLookupState.Loading || inventoryLoading)
}

/** One-shot events emitted by [PackageSearchViewModel]. */
internal sealed interface PackageSearchEvent {
    data object SaveFailed : PackageSearchEvent
}

/**
 * Owns device package discovery, debounced exact-name lookups, and search detail loading
 * so the package index survives configuration changes without re-querying Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PackageSearchViewModel(
    private val repository: CleanerRepository,
    settings: CleanerSettings,
) : ViewModel() {
    private val eventChannel = Channel<PackageSearchEvent>(Channel.BUFFERED)
    val events: Flow<PackageSearchEvent> = eventChannel.receiveAsFlow()

    private val screenActivated = MutableStateFlow(false)
    private val queryFlow = MutableStateFlow("")
    private val attemptFlow = MutableStateFlow(0)
    private val discoveredPackages = MutableStateFlow<List<PackageEntry>?>(null)
    private val inventoryFailed = MutableStateFlow(false)
    private val exactLookupState = MutableStateFlow<ExactLookupState>(ExactLookupState.Idle)
    private val detailsState = MutableStateFlow<PackageDetailsUiState>(PackageDetailsUiState.Closed)

    private var detailsJob: Job? = null

    private val customPackageNames = settings.preferences
        .map { it.customPackageNames }
        .distinctUntilChanged()

    private val filteredMatches = combine(
        repository.latestSnapshot,
        discoveredPackages,
        queryFlow,
    ) { snapshot, discovered, rawQuery ->
        val term = rawQuery.trim()
        val scanned = snapshot?.packages.orEmpty()
            .asSequence()
            .filter { it.hasVisibleRecord() }
            .map { it.entry }
        val matches = (scanned + discovered.orEmpty().asSequence())
            .filter { entry ->
                entry.packageName.contains(term, ignoreCase = true) ||
                    entry.displayName.contains(term, ignoreCase = true)
            }
            .distinctBy(PackageEntry::packageName)
            .toList()
        term to matches
    }

    private val inventoryState = combine(
        repository.connection,
        customPackageNames,
        discoveredPackages,
        inventoryFailed,
    ) { connection, customNames, discovered, failed ->
        InventorySnapshot(connection, customNames, discovered, failed)
    }

    val state: StateFlow<PackageSearchUiState> = combine(
        inventoryState,
        filteredMatches,
        exactLookupState,
        detailsState,
    ) { inventory, (term, matches), exactLookup, details ->
        val exactEntry = (exactLookup as? ExactLookupState.Found)?.entry
        val bundledNames = PackageDirectory.variants.keys
        // Keep ordering stable when the user taps "Add" so items update in place instead of jumping off-screen.
        val sortedResults = (listOfNotNull(exactEntry) + matches)
            .distinctBy(PackageEntry::packageName)
            .sortedWith(
                compareBy<PackageEntry> { it.packageName != term }
                    .thenBy { it.packageName !in bundledNames }
                    .thenBy(String.CASE_INSENSITIVE_ORDER, PackageEntry::displayName)
                    .thenBy(PackageEntry::packageName),
            )

        PackageSearchUiState(
            connection = inventory.connection,
            term = term,
            customPackageNames = inventory.customPackageNames,
            results = sortedResults,
            inventoryLoading = inventory.connection.status == PrivilegeStatus.READY &&
                inventory.discovered == null && !inventory.failed,
            inventoryFailed = inventory.failed,
            exactLookup = exactLookup,
            details = details,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PackageSearchUiState(connection = repository.connection.value),
    )

    private data class InventorySnapshot(
        val connection: PrivilegeConnection,
        val customPackageNames: Set<String>,
        val discovered: List<PackageEntry>?,
        val failed: Boolean,
    )

    init {
        viewModelScope.launch {
            combine(screenActivated, repository.connection, attemptFlow) { active, connection, attempt ->
                Triple(active, connection, attempt)
            }.collectLatest { (active, connection, _) ->
                if (!active) return@collectLatest
                discoveredPackages.value = null
                inventoryFailed.value = false
                if (connection.status == PrivilegeStatus.READY) {
                    try {
                        discoveredPackages.value = repository.discoverPackages()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        inventoryFailed.value = true
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(
                screenActivated,
                repository.connection,
                attemptFlow,
                filteredMatches,
            ) { active, connection, attempt, (term, matches) ->
                val exactName = term.takeIf {
                    isExactPackageName(it) && matches.none { entry -> entry.packageName == term }
                }
                Triple(active && connection.status == PrivilegeStatus.READY, attempt, exactName)
            }.distinctUntilChanged().collectLatest { (canLookup, _, exactName) ->
                if (!canLookup || exactName == null) {
                    exactLookupState.value = ExactLookupState.Idle
                    return@collectLatest
                }
                exactLookupState.value = ExactLookupState.Loading(exactName)
                delay(EXACT_LOOKUP_DEBOUNCE_MS)
                exactLookupState.value = try {
                    val entry = repository.lookupPackage(exactName)
                    if (entry != null) ExactLookupState.Found(entry) else ExactLookupState.Missing(exactName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ExactLookupState.Failed(exactName)
                }
            }
        }
    }

    /** Activates package discovery when the search screen becomes visible. */
    fun onScreenOpened() {
        if (!screenActivated.value) {
            screenActivated.value = true
        }
    }

    /** Clears transient query and sheet state when navigating freshly into search while keeping the package cache warm. */
    fun resetSearch() {
        queryFlow.value = ""
        exactLookupState.value = ExactLookupState.Idle
        dismissDetails()
    }

    /** Updates the current search filter text. */
    fun onQueryChange(rawQuery: String) {
        queryFlow.value = rawQuery
    }

    /** Retries device package discovery and any active exact-name lookup. */
    fun retry() {
        attemptFlow.value += 1
    }

    /** Adds [packageName] to the custom directory and notifies the UI if saving fails. */
    fun addCustomPackage(packageName: String) {
        viewModelScope.launch {
            if (!repository.setCustomPackage(packageName, included = true)) {
                eventChannel.send(PackageSearchEvent.SaveFailed)
            }
        }
    }

    /** Opens the detail sheet for [packageName] and loads its per-space metadata. */
    fun openDetails(packageName: String) {
        detailsJob?.cancel()
        if (repository.connection.value.status != PrivilegeStatus.READY) {
            detailsState.value = PackageDetailsUiState.Failed(packageName)
            return
        }
        detailsState.value = PackageDetailsUiState.Loading(packageName)
        detailsJob = viewModelScope.launch {
            detailsState.value = try {
                PackageDetailsUiState.Loaded(packageName, repository.loadPackageDetails(packageName))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PackageDetailsUiState.Failed(packageName)
            }
        }
    }

    /** Dismisses the currently open search detail sheet. */
    fun dismissDetails() {
        detailsJob?.cancel()
        detailsJob = null
        detailsState.value = PackageDetailsUiState.Closed
    }

    override fun onCleared() {
        eventChannel.close()
    }

    private companion object {
        const val EXACT_LOOKUP_DEBOUNCE_MS = 350L
    }
}
