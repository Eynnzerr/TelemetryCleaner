@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.eynnzerr.telemetrycleaner.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared Android 16 / Material 3 Expressive Segmented List spacing and corner tokens. */
internal object SegmentedListDefaults {
    val Gap: Dp = 2.dp
    val OuterCorner: Dp = 20.dp
    val InnerCorner: Dp = 4.dp
    val PressedCorner: Dp = 14.dp
}

/** Computes the Material 3 Expressive segmented corner shape for an item at [index] of [count]. */
internal fun segmentedItemShape(
    index: Int,
    count: Int,
    outerCorner: Dp = SegmentedListDefaults.OuterCorner,
    innerCorner: Dp = SegmentedListDefaults.InnerCorner,
): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(outerCorner)
    index == 0 -> RoundedCornerShape(
        topStart = outerCorner,
        topEnd = outerCorner,
        bottomStart = innerCorner,
        bottomEnd = innerCorner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = innerCorner,
        topEnd = innerCorner,
        bottomStart = outerCorner,
        bottomEnd = outerCorner,
    )
    else -> RoundedCornerShape(innerCorner)
}

/**
 * Animates segmented list item corners smoothly on press using Material 3 Expressive spring motion.
 */
@Composable
internal fun rememberAnimatedSegmentedShape(
    index: Int,
    count: Int,
    interactionSource: InteractionSource? = null,
    outerCorner: Dp = SegmentedListDefaults.OuterCorner,
    innerCorner: Dp = SegmentedListDefaults.InnerCorner,
): Shape {
    val pressed = interactionSource?.collectIsPressedAsState()?.value == true
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val topTarget = when {
        pressed -> SegmentedListDefaults.PressedCorner
        count <= 1 || index == 0 -> outerCorner
        else -> innerCorner
    }
    val bottomTarget = when {
        pressed -> SegmentedListDefaults.PressedCorner
        count <= 1 || index == count - 1 -> outerCorner
        else -> innerCorner
    }
    val top by animateDpAsState(targetValue = topTarget, animationSpec = spec, label = "segmentedTopCorner")
    val bottom by animateDpAsState(targetValue = bottomTarget, animationSpec = spec, label = "segmentedBottomCorner")
    return RoundedCornerShape(
        topStart = top,
        topEnd = top,
        bottomStart = bottom,
        bottomEnd = bottom,
    )
}

/** Semantic color roles for compact Material 3 Expressive status pills. */
internal enum class StatusTone { PRIMARY, SECONDARY, TERTIARY, ERROR, NEUTRAL }

/** Renders a compact tonal status badge for installation states, app types, and permission risk. */
@Composable
internal fun StatusBadge(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (tone) {
        StatusTone.PRIMARY -> colors.primaryContainer to colors.onPrimaryContainer
        StatusTone.SECONDARY -> colors.secondaryContainer to colors.onSecondaryContainer
        StatusTone.TERTIARY -> colors.tertiaryContainer to colors.onTertiaryContainer
        StatusTone.ERROR -> colors.errorContainer to colors.onErrorContainer
        StatusTone.NEUTRAL -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Resolves the container color used for standard segmented list surfaces across all screens. */
@Composable
internal fun segmentedContainerColor(selected: Boolean = false): Color =
    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
