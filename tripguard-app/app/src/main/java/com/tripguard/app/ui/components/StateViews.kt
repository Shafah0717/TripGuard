package com.tripguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.WifiTetheringOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary

@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String = "Loading…") {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    iconTint: Color = SoftBlueFg,
    iconBackground: Color = SoftBlueBg,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Spacing.xl))
            action()
        }
    }
}

@Composable
fun ErrorStateView(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    offline: Boolean = false,
) {
    val icon = if (offline) Icons.Outlined.WifiTetheringOff else Icons.Outlined.CloudOff
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(SoftRedBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = SoftRedFg, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = if (offline) "You're offline" else "Something went wrong",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.xl))
            SecondaryButton(text = "Try again", onClick = onRetry, modifier = Modifier.widthIn(max = 200.dp))
        }
    }
}

/** Compact offline/queued banner for inline use above lists that still have local data. */
@Composable
fun OfflineBanner(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        color = SoftRedBg,
        shape = RoundedCornerShape(Spacing.sm),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = SoftRedFg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}
