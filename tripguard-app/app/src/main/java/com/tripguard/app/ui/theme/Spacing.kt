package com.tripguard.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Shared spacing, sizing and radius tokens. Screens must use these instead of ad-hoc dp
 * values so padding, corner radii and control heights stay consistent across the product.
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Standard horizontal page gutter. */
    val screenHorizontal = 20.dp

    /** Padding applied to most screen content blocks. */
    val screenPadding = PaddingValues(horizontal = screenHorizontal, vertical = 16.dp)

    /** Vertical rhythm between major blocks on a screen. */
    val sectionGap = 24.dp
}

object Sizes {
    val buttonHeight = 54.dp
    val inputHeight = 56.dp
    val topBarHeight = 60.dp
    val bottomBarHeight = 64.dp
    val cardRadius = 18.dp
    val controlRadius = 14.dp
    val chipRadius = 100.dp
    val minTouchTarget = 48.dp
}
