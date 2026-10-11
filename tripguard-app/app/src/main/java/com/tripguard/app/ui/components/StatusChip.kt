package com.tripguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.ui.theme.SoftAmberBg
import com.tripguard.app.ui.theme.SoftAmberFg
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftEmeraldBg
import com.tripguard.app.ui.theme.SoftEmeraldFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.SoftSlateBg
import com.tripguard.app.ui.theme.SoftSlateFg

@Composable
fun StatusChip(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

data class StatusVisual(val label: String, val bg: Color, val fg: Color, val icon: ImageVector)

fun DocumentState.visual(): StatusVisual = when (this) {
    DocumentState.NOT_UPLOADED ->
        StatusVisual(label, SoftSlateBg, SoftSlateFg, Icons.Outlined.UploadFile)

    DocumentState.UPLOADING ->
        StatusVisual(label, SoftBlueBg, SoftBlueFg, Icons.Outlined.UploadFile)

    DocumentState.UPLOAD_FAILED ->
        StatusVisual(label, SoftRedBg, SoftRedFg, Icons.Outlined.Cancel)

    DocumentState.UPLOADED ->
        StatusVisual(label, SoftBlueBg, SoftBlueFg, Icons.Outlined.UploadFile)

    DocumentState.PROCESSING ->
        StatusVisual(label, SoftAmberBg, SoftAmberFg, Icons.Outlined.HourglassTop)

    DocumentState.EXTRACTED ->
        StatusVisual(label, SoftBlueBg, SoftBlueFg, Icons.Outlined.Info)

    DocumentState.NEEDS_REVIEW ->
        StatusVisual(label, SoftAmberBg, SoftAmberFg, Icons.Outlined.RateReview)

    DocumentState.POTENTIAL_ISSUE ->
        StatusVisual(label, SoftRedBg, SoftRedFg, Icons.Outlined.ErrorOutline)

    DocumentState.ACCEPTED ->
        StatusVisual(label, SoftEmeraldBg, SoftEmeraldFg, Icons.Filled.CheckCircle)
}

fun ApplicationStatus.visual(): StatusVisual = when (this) {
    ApplicationStatus.DRAFT ->
        StatusVisual(label, SoftSlateBg, SoftSlateFg, Icons.Outlined.Info)

    ApplicationStatus.IN_PROGRESS ->
        StatusVisual(label, SoftBlueBg, SoftBlueFg, Icons.Outlined.HourglassTop)

    ApplicationStatus.ACTION_REQUIRED ->
        StatusVisual(label, SoftAmberBg, SoftAmberFg, Icons.Outlined.RateReview)

    ApplicationStatus.UNDER_REVIEW ->
        StatusVisual(label, SoftBlueBg, SoftBlueFg, Icons.Outlined.HourglassTop)

    ApplicationStatus.READY_FOR_AGENCY ->
        StatusVisual(label, SoftEmeraldBg, SoftEmeraldFg, Icons.Filled.CheckCircle)
}

@Composable
fun DocumentStatusChip(state: DocumentState, modifier: Modifier = Modifier) {
    val visual = state.visual()
    StatusChip(text = visual.label, background = visual.bg, foreground = visual.fg, icon = visual.icon, modifier = modifier)
}

@Composable
fun ApplicationStatusChip(status: ApplicationStatus, modifier: Modifier = Modifier) {
    val visual = status.visual()
    StatusChip(text = visual.label, background = visual.bg, foreground = visual.fg, icon = visual.icon, modifier = modifier)
}

@Composable
fun RequirementChip(applicability: RequirementApplicability, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (applicability) {
        RequirementApplicability.REQUIRED -> Triple("Required", SoftRedBg, SoftRedFg)
        RequirementApplicability.CONDITIONAL -> Triple("Conditional", SoftAmberBg, SoftAmberFg)
        RequirementApplicability.NOT_APPLICABLE -> Triple("Not applicable", SoftSlateBg, SoftSlateFg)
    }
    StatusChip(text = label, background = bg, foreground = fg, modifier = modifier)
}
