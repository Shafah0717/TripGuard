package com.tripguard.app.ui.screens.comparison

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.FieldEvidenceStatus
import com.tripguard.app.domain.model.RequirementEvaluation
import com.tripguard.app.domain.rules.EvidenceExplanation
import com.tripguard.app.domain.rules.RequirementEvaluator
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ErrorStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.StatusChip
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.Navy
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
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ComparisonUiState(
    val applicationId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val visaTypeName: String = "",
    val evaluations: List<RequirementEvaluation> = emptyList(),
)

class ComparisonViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Comparison.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ComparisonUiState(applicationId = applicationId))
    val uiState: StateFlow<ComparisonUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
            ) { app, catalog -> app to catalog }
                .collect { (app, catalog) ->
                    val visa = catalog?.visaTypes?.firstOrNull { it.id == app?.visaTypeId }
                    _uiState.value = when {
                        app == null || catalog == null ->
                            _uiState.value.copy(isLoading = true, error = null)

                        visa == null ->
                            _uiState.value.copy(isLoading = false, error = "This visa category is no longer available.")

                        else -> _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            visaTypeName = visa.displayName,
                            evaluations = RequirementEvaluator.evaluate(catalog, visa, app),
                        )
                    }
                }
        }
    }
}

@Composable
fun ComparisonScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ComparisonViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Compare with requirements", onBack = onBack)

        if (state.isLoading) {
            LoadingState(message = "Matching evidence to requirements…")
            return
        }
        if (state.error != null) {
            ErrorStateView(message = state.error ?: "Comparison unavailable.")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.md,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                TripGuardCard {
                    Text(state.visaTypeName, style = MaterialTheme.typography.titleMedium, color = Navy)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "Each row matches the evidence held on this device against a requirement " +
                            "declared in the local dataset. No rule is invented and no document is treated " +
                            "as verified merely because it was uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }

            if (state.evaluations.isEmpty()) {
                item {
                    InfoBanner(
                        text = "No requirements are defined for this visa route in the dataset.",
                        containerColor = SoftSlateBg,
                        contentColor = SoftSlateFg,
                        icon = Icons.Outlined.Info,
                    )
                }
            }

            items(state.evaluations, key = { it.requirementId + it.ruleId }) { evaluation ->
                EvaluationCard(evaluation)
            }
        }
    }
}

@Composable
private fun EvaluationCard(evaluation: RequirementEvaluation) {
    val (bg, fg) = outcomeColors(evaluation.outcome)
    var showDetails by remember { mutableStateOf(false) }
    val valuesNeedReview = evaluation.evidenceFields.any { it.status == FieldEvidenceStatus.AMBIGUOUS }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (valuesNeedReview) SoftAmberFg.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(evaluation.requirementName, style = MaterialTheme.typography.titleSmall, color = Navy)
                    if (valuesNeedReview) {
                        Spacer(Modifier.height(4.dp))
                        StatusChip(
                            text = "Values need review",
                            background = SoftAmberBg,
                            foreground = SoftAmberFg,
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
                StatusChip(text = evaluation.outcome.label, background = bg, foreground = fg)
            }

            if (evaluation.evidenceSummary.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = evaluation.evidenceSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = EvidenceExplanation.shortReason(evaluation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (evaluation.nextAction.isNotBlank() && !evaluation.nextAction.equals("No action needed.", ignoreCase = true)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Next: ${evaluation.nextAction}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
            ) {
                Icon(
                    imageVector = if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Navy,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "Why this result?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            if (showDetails) {
                Spacer(Modifier.height(Spacing.xs))
                DetailRow("Requirement", "${evaluation.requirementId} · ${evaluation.documentCategoryId}")
                DetailRow("Visa route", evaluation.visaRouteName)
                DetailRow("Applicability", applicabilityLabel(evaluation.applicability))
                DetailRow("Document", presenceLabel(evaluation.documentPresence))
                DetailRow(
                    "Extraction",
                    buildString {
                        append(extractionStatusLabel(evaluation.extractionStatus))
                        evaluation.extractionMethod?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                )

                if (evaluation.evidenceFields.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Extracted evidence", style = MaterialTheme.typography.labelMedium, color = Navy)
                    evaluation.evidenceFields.forEach { field ->
                        EvidenceRow(field)
                    }
                } else if (evaluation.extractedValue != null) {
                    DetailRow("Extracted value", evaluation.extractedValue)
                }

                Spacer(Modifier.height(Spacing.sm))
                evaluation.ruleType?.let { DetailRow("Rule", "$it · ${evaluation.ruleId}") }
                    ?: DetailRow("Rule", "Not defined")
                DetailRow("Rule status", EvidenceExplanation.ruleVerificationLabel(evaluation.ruleVerification))

                val sources = evaluation.sourceLabels.ifEmpty { evaluation.sourceIds }
                if (sources.isNotEmpty()) {
                    DetailRow("Source refs", sources.joinToString(", "))
                }

                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = evaluation.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                InfoBanner(
                    text = evaluation.nextAction,
                    containerColor = SoftBlueBg,
                    contentColor = SoftBlueFg,
                    icon = Icons.Outlined.Info,
                )
            }
        }
    }
}

@Composable
private fun EvidenceRow(field: com.tripguard.app.domain.model.EvidenceField) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = EvidenceExplanation.evidenceFieldLine(field),
            style = MaterialTheme.typography.bodySmall,
            color = if (field.value.isBlank()) TextSecondary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun applicabilityLabel(app: com.tripguard.app.domain.model.RequirementApplicability): String = when (app) {
    com.tripguard.app.domain.model.RequirementApplicability.REQUIRED -> "Required"
    com.tripguard.app.domain.model.RequirementApplicability.CONDITIONAL -> "Conditional"
    com.tripguard.app.domain.model.RequirementApplicability.NOT_APPLICABLE -> "Not applicable"
}

private fun presenceLabel(presence: com.tripguard.app.domain.model.DocumentPresence): String = when (presence) {
    com.tripguard.app.domain.model.DocumentPresence.MISSING -> "Missing"
    com.tripguard.app.domain.model.DocumentPresence.PRESENT -> "Present (on device)"
}

private fun extractionStatusLabel(status: com.tripguard.app.domain.model.ExtractionStatus): String = when (status) {
    com.tripguard.app.domain.model.ExtractionStatus.NOT_PROCESSED -> "Not processed yet"
    com.tripguard.app.domain.model.ExtractionStatus.PROCESSING -> "Processing"
    com.tripguard.app.domain.model.ExtractionStatus.SUCCESS -> "Success"
    com.tripguard.app.domain.model.ExtractionStatus.PARTIAL -> "Partial"
    com.tripguard.app.domain.model.ExtractionStatus.FAILED -> "Failed"
}

private fun outcomeColors(outcome: EvaluationOutcome): Pair<Color, Color> = when (outcome) {
    EvaluationOutcome.SATISFIED -> SoftEmeraldBg to SoftEmeraldFg
    EvaluationOutcome.MISSING -> SoftRedBg to SoftRedFg
    EvaluationOutcome.POTENTIAL_ISSUE -> SoftRedBg to SoftRedFg
    EvaluationOutcome.COULD_NOT_VERIFY -> SoftAmberBg to SoftAmberFg
    EvaluationOutcome.MANUAL_REVIEW_REQUIRED -> SoftAmberBg to SoftAmberFg
    EvaluationOutcome.RULE_UNVERIFIED -> SoftSlateBg to SoftSlateFg
}
