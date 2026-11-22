package com.eynnzerr.telemetrycleaner.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eynnzerr.telemetrycleaner.R

/** Shows the standard back icon and its localized accessibility label. */
@Composable
internal fun NavigateBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.navigate_back),
        )
    }
}

/** Shows the title and back action for a secondary page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopAppBar(@StringRes title: Int, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(title)) },
        navigationIcon = { NavigateBackButton(onClick = onBack) },
    )
}

/** Lays out a titled list using the padding and maximum width shared by history and the catalogue. */
@Composable
internal fun SecondaryListScreen(
    @StringRes title: Int,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(topBar = { BackTopAppBar(title, onBack) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** Labels a section within a secondary list. */
@Composable
internal fun SecondaryListHeading(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}
