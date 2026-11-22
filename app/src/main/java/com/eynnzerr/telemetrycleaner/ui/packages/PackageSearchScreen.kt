package com.eynnzerr.telemetrycleaner.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R
import com.eynnzerr.telemetrycleaner.data.PackageEntry
import com.eynnzerr.telemetrycleaner.data.PrivilegeStatus
import com.eynnzerr.telemetrycleaner.feature.packages.PackageDetailsUiState
import com.eynnzerr.telemetrycleaner.feature.packages.PackageSearchUiState
import com.eynnzerr.telemetrycleaner.ui.components.NavigateBackButton
import kotlinx.coroutines.launch

/** Stateless search screen backed by [com.eynnzerr.telemetrycleaner.feature.packages.PackageSearchViewModel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PackageSearchScreen(
    state: PackageSearchUiState,
    onScreenOpened: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onAdd: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onDismissDetails: () -> Unit,
    onBack: () -> Unit,
) {
    val query = rememberTextFieldState(initialText = state.term)
    val searchBarState = rememberSearchBarState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        onScreenOpened()
    }
    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collect(onQueryChange)
    }

    val inputField: @Composable (onNavigateBack: () -> Unit) -> Unit = { onNavigateBack ->
        val keyboard = LocalSoftwareKeyboardController.current
        SearchBarDefaults.InputField(
            textFieldState = query,
            searchBarState = searchBarState,
            onSearch = { keyboard?.hide() },
            placeholder = { Text(stringResource(R.string.package_search_hint)) },
            leadingIcon = { NavigateBackButton(onClick = onNavigateBack) },
        )
    }
    val searchResults: @Composable ColumnScope.() -> Unit = {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Column(
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 620.dp).fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.connection.status == PrivilegeStatus.CONNECTING) {
                SearchResultsLoading()
            } else if (state.connection.status != PrivilegeStatus.READY) {
                Text(stringResource(R.string.package_search_needs_connection))
            } else {
                val failure = when {
                    state.exactLookupFailed -> R.string.package_search_exact_failed
                    state.inventoryFailed -> R.string.package_search_failed
                    else -> null
                }
                if (failure != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(failure), Modifier.weight(1f))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.refresh)) }
                    }
                }
                when {
                    state.results.isNotEmpty() -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.results, key = PackageEntry::packageName) { entry ->
                            SearchResult(
                                entry = entry,
                                added = entry.packageName in state.customPackageNames,
                                onAdd = onAdd,
                                onOpen = {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    onOpenDetails(entry.packageName)
                                },
                            )
                        }
                    }
                    state.exactLookupMissing -> Text(stringResource(R.string.package_search_exact_missing, state.term))
                    state.isLoadingResults -> SearchResultsLoading()
                    failure == null -> Text(stringResource(R.string.package_search_no_matches))
                    else -> Unit
                }
            }
        }
    }
    Scaffold(
        topBar = {
            Box(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(SearchBarDefaults.windowInsets),
                contentAlignment = Alignment.TopCenter,
            ) {
                SearchBar(
                    state = searchBarState,
                    inputField = { inputField(onBack) },
                    modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            content = searchResults,
        )
    }
    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = { inputField { coroutineScope.launch { searchBarState.animateToCollapsed() } } },
        content = searchResults,
    )
    if (state.details !is PackageDetailsUiState.Closed) {
        SearchPackageDetailsSheet(
            details = state.details,
            onDismiss = onDismissDetails,
        )
    }
}
