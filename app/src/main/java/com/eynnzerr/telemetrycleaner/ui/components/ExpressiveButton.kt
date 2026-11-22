@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Expressive container sizes with matched shape, typography, and horizontal padding tokens. */
internal enum class ExpressiveButtonSize { SMALL, MEDIUM }

/**
 * Renders an Expressive primary action using matched Material 3 Expressive size tokens
 * (`shapesFor`, `contentPaddingFor`, `textStyleFor`) so short CJK labels remain balanced pills.
 */
@Composable
internal fun ExpressiveButton(
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    size: ExpressiveButtonSize = ExpressiveButtonSize.SMALL,
    @DrawableRes leadingIcon: Int? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerHeight = when (size) {
        ExpressiveButtonSize.SMALL -> ButtonDefaults.MinHeight
        ExpressiveButtonSize.MEDIUM -> ButtonDefaults.MediumContainerHeight
    }
    val minWidth = when (size) {
        ExpressiveButtonSize.SMALL -> 84.dp
        ExpressiveButtonSize.MEDIUM -> 112.dp
    }
    val iconSize = when (size) {
        ExpressiveButtonSize.SMALL -> 18.dp
        ExpressiveButtonSize.MEDIUM -> 20.dp
    }
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(containerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(containerHeight),
        modifier = modifier
            .heightIn(min = containerHeight)
            .widthIn(min = minWidth),
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = colorScheme.error,
                contentColor = colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = stringResource(label),
            style = ButtonDefaults.textStyleFor(containerHeight),
            maxLines = 1,
        )
    }
}
