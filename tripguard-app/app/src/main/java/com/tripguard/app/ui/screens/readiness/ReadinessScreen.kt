package com.tripguard.app.ui.screens.readiness

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.AgencyInquiryUi
import com.tripguard.app.domain.model.ChecklistItem
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.RuleVerification
import com.tripguard.app.domain.readiness.ReadinessCalculator
import com.tripguard.app.domain.readiness.ReadinessReport
import com.tripguard.app.domain.rules.RequirementEvaluator
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ErrorStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SecondaryButton
import com.tripguard.app.ui.components.StatusChip
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.screens.common.DocumentSummary
import com.tripguard.app.ui.screens.common.DocumentSummaryFactory
import com.tripguard.app.ui.screens.common.documentIcon
import com.tripguard.app.ui.screens.common.documentToneColors
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

/** Tone of the honest overall "Preparation summary" label. */
enum class PreparationTone { SLATE, BLUE, AMBER, RED }

data class ReadinessUiState(
    val applicationId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val visaTypeName: String = "",
    val report: ReadinessReport? = null,
    val application: Application? = null,
    val overallLabel: String = "",
    val overallTone: PreparationTone = PreparationTone.BLUE,
    val eligibilityNote: String = "",
    val agencyInquiry: AgencyInquiryUi? = null,
)

class ReadinessViewModel(
    application: android.app.Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Readiness.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ReadinessUiState(applicationId = applicationId))
    val uiState: StateFlow<ReadinessUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
                container.agency.localState,
            ) { app, catalog, agency -> Triple(app, catalog, agency) }
                .collect { (app, catalog, agency) ->
                    val visa = catalog?.visaTypes?.firstOrNull { it.id == app?.visaTypeId }
                    _uiState.value = when {
                        app == null || catalog == null ->
                            _uiState.value.copy(isLoading = true, error = null)

                        visa == null ->
                            _uiState.value.copy(isLoading = false, error = "This visa category is no longer available.")

                        else -> {
                            val report = ReadinessCalculator.calculate(visa, app)
                            val evaluations = RequirementEvaluator.evaluate(catalog, visa, app)
                            val autoVerified = evaluations.count { it.ruleVerification == RuleVerification.VERIFIED }

                            val overall = when {
                                report.missingItems.isNotEmpty() -> "Preparation incomplete" to PreparationTone.AMBER
                                report.needsReviewItems.isNotEmpty() ||
                                    report.potentialIssueItems.isNotEmpty() ||
                                    report.outstandingAgencyActions.isNotEmpty() ->
                                    "Review needed" to PreparationTone.RED

                                else -> "Document checks completed — manual review remains" to PreparationTone.BLUE
                            }

                            val eligibility = when {
                                evaluations.isEmpty() ->
                                    "The dataset defines no eligibility rules for this route."

                                autoVerified == 0 ->
                                    "None of the ${evaluations.size} requirement(s) can be verified automatically " +
                                        "in this dataset — every substantive check is decided by a human reviewer."

                                autoVerified == evaluations.size ->
                                    "All ${evaluations.size} requirement(s) are machine-verifiable in this dataset."

                                else ->
                                    "$autoVerified of ${evaluations.size} requirement(s) are machine-verifiable; " +
                                        "the rest require human review."
                            }

                            _uiState.value.copy(
                                isLoading = false,
                                error = null,
                                visaTypeName = visa.displayName,
                                report = report,
                                application = app,
                                overallLabel = overall.first,
                                overallTone = overall.second,
                                eligibilityNote = eligibility,
                                agencyInquiry = agency.inquiry,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
fun ReadinessScreen(
    onBack: () -> Unit,
    onOpenDocument: (applicationId: String, documentId: String) -> Unit,
    onBackToDocuments: (applicationId: String) -> Unit,
    onOpenComparison: (applicationId: String) -> Unit,
    onContactAgency: (applicationId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReadinessViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Preparation summary", onBack = onBack)

        if (state.isLoading) {
            LoadingState(message = "Preparing your summary…")
        } else if (state.error != null) {
            ErrorStateView(message = state.error ?: "Preparation summary unavailable.")
        } else {
            val report = state.report ?: return
            val application = state.application

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
                item { PreparationHero(state, report, application) }

                report.passportExpiryWarning?.let { warning ->
                    item {
                        InfoBanner(
                            text = warning,
                            containerColor = SoftAmberBg,
                            contentColor = SoftAmberFg,
                            icon = Icons.Outlined.WarningAmber,
                        )
                    }
                }

                if (report.outstandingAgencyActions.isNotEmpty()) {
                    item {
                        InfoBanner(
                            text = "Your agency requested ${report.outstandingAgencyActions.size} correction(s). " +
                                "Open the application to resolve them.",
                            containerColor = SoftRedBg,
                            contentColor = SoftRedFg,
                            icon = Icons.Outlined.ErrorOutline,
                        )
                    }
                }

                val addedItems = report.processingItems +
                    report.needsReviewItems +
                    report.potentialIssueItems +
                    report.acceptedItems
                if (addedItems.isNotEmpty()) {
                    item { GroupHeader("Documents added", Icons.Outlined.CheckCircle, SoftEmeraldBg, SoftEmeraldFg) }
                    items(addedItems, key = { it.documentId }) { item ->
                        DocumentLine(item, summaryFor(application, item)) {
                            onOpenDocument(state.applicationId, item.documentId)
                        }
                    }
                }

                val attention = report.missingItems + report.needsReviewItems + report.potentialIssueItems
                if (attention.isNotEmpty()) {
                    item { GroupHeader("Needs your attention", Icons.Outlined.ErrorOutline, SoftRedBg, SoftRedFg) }
                    items(attention, key = { it.documentId }) { item ->
                        DocumentLine(item, summaryFor(application, item)) {
                            onOpenDocument(state.applicationId, item.documentId)
                        }
                    }
                }

                item { GroupHeader("Eligibility requirements", Icons.Outlined.Info, SoftBlueBg, SoftBlueFg) }
                item {
                    InfoBanner(
                        text = state.eligibilityNote,
                        containerColor = SoftBlueBg,
                        contentColor = SoftBlueFg,
                        icon = Icons.Outlined.Info,
                    )
                }

                item {
                    InfoBanner(
                        text = "TripGuard checks that documents are present and readable. It does not verify " +
                            "authenticity and never decides visa eligibility — your agency and the issuing authority do.",
                        containerColor = SoftSlateBg,
                        contentColor = SoftSlateFg,
                        icon = Icons.Outlined.Info,
                    )
                }

                item {
                    PrimaryButton(
                        text = "Review items",
                        onClick = { onOpenComparison(state.applicationId) },
                        icon = Icons.AutoMirrored.Outlined.FactCheck,
                    )
                }
                item {
                    PrimaryButton(
                        text = "Contact an agency",
                        onClick = { onContactAgency(state.applicationId) },
                        icon = Icons.AutoMirrored.Outlined.Send,
                    )
                }
                val activeInquiry = state.agencyInquiry
                item {
                    if (activeInquiry != null) {
                        TripGuardCard {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        "Inquiry in progress — ${activeInquiry.statusLabel}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Navy,
                                    )
                                }
                                Text(
                                    "Reference ${activeInquiry.reference}. Track live status, offered times and your booking.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                                Spacer(Modifier.height(Spacing.xs))
                                SecondaryButton(
                                    text = "Track my inquiry & booking",
                                    onClick = { onContactAgency(state.applicationId) },
                                )
                            }
                        }
                    }
                }
                item {
                    InfoBanner(
                        text = "Contacting an agency is optional and needs an internet connection. Only the " +
                            "details you explicitly consent to share are sent — your documents stay on this device.",
                        containerColor = SoftBlueBg,
                        contentColor = SoftBlueFg,
                        icon = Icons.Outlined.Info,
                    )
                }
                item {
                    SecondaryButton(
                        text = "Back to documents",
                        onClick = { onBackToDocuments(state.applicationId) },
                        icon = Icons.AutoMirrored.Outlined.ListAlt,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreparationHero(
    state: ReadinessUiState,
    report: ReadinessReport,
    application: Application?,
) {
    TripGuardCard {
        Text(state.visaTypeName, style = MaterialTheme.typography.titleMedium, color = Navy)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Review your documents and outstanding items before contacting an agency.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )

        Spacer(Modifier.height(Spacing.md))
        val (bg, fg) = overallToneColors(state.overallTone)
        StatusChip(text = state.overallLabel, background = bg, foreground = fg)

        Spacer(Modifier.height(Spacing.md))
        LinearProgressIndicator(
            progress = { report.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(Modifier.height(Spacing.md))
        val documents = application?.documents?.values.orEmpty()
        val documentsAdded = documents.count { it.hasFile }
        val missingCount = report.missingItems.size
        val needsReviewCount = report.needsReviewItems.size + report.potentialIssueItems.size
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            StatTile("Documents added", documentsAdded.toString(), Modifier.weight(1f))
            StatTile("Still missing", missingCount.toString(), Modifier.weight(1f))
            StatTile("Need review", needsReviewCount.toString(), Modifier.weight(1f))
        }

        val failures = documents.count { it.state == DocumentState.UPLOAD_FAILED || it.lastError != null }
        if (failures > 0) {
            Spacer(Modifier.height(Spacing.sm))
            InfoBanner(
                text = "$failures document(s) could not be read. Re-upload a clearer copy or open them to review.",
                containerColor = SoftRedBg,
                contentColor = SoftRedFg,
                icon = Icons.Outlined.ErrorOutline,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(Spacing.sm),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = Navy,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun GroupHeader(text: String, icon: ImageVector, bg: Color, fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DocumentLine(item: ChecklistItem, summary: DocumentSummary, onClick: () -> Unit) {
    val (bg, fg) = documentToneColors(summary.tone)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = documentIcon(item.documentId),
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary.resultLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            StatusChip(text = summary.statusLabel, background = bg, foreground = fg)
        }
    }
}

private fun summaryFor(application: Application?, item: ChecklistItem): DocumentSummary {
    val upload = application?.documents?.get(item.documentId) ?: DocumentUpload(item.documentId, item.name)
    return DocumentSummaryFactory.summarize(upload)
}

private fun overallToneColors(tone: PreparationTone): Pair<Color, Color> = when (tone) {
    PreparationTone.SLATE -> SoftSlateBg to SoftSlateFg
    PreparationTone.BLUE -> SoftBlueBg to SoftBlueFg
    PreparationTone.AMBER -> SoftAmberBg to SoftAmberFg
    PreparationTone.RED -> SoftRedBg to SoftRedFg
}