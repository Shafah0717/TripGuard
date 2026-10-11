package com.tripguard.app.ui.screens.extraction

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.ChecklistItem
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.ImageValidation
import com.tripguard.app.domain.model.OcrMethod
import com.tripguard.app.domain.model.RequirementEvaluation
import com.tripguard.app.domain.rules.EvidenceExplanation
import com.tripguard.app.domain.rules.RequirementEvaluator
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.DocumentStatusChip
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.InlineIconTextButton
import com.tripguard.app.ui.components.KeyValueRow
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SecondaryButton
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
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ExtractionUiState(
    val applicationId: String = "",
    val documentId: String = "",
    val isLoading: Boolean = true,
    val documentName: String = "",
    val item: ChecklistItem? = null,
    val upload: DocumentUpload = DocumentUpload("", ""),
    val ruleResult: RequirementEvaluation? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val removed: Boolean = false,
)

class ExtractionReviewViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Extraction.ARG_APPLICATION_ID).orEmpty()
    private val documentId: String =
        savedStateHandle.get<String>(Screen.Extraction.ARG_DOCUMENT_ID).orEmpty()

    private val _uiState = MutableStateFlow(
        ExtractionUiState(applicationId = applicationId, documentId = documentId),
    )
    val uiState: StateFlow<ExtractionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
            ) { app, catalog -> app to catalog }
                .collect { (app, catalog) ->
                    val visa = catalog?.visaTypes?.firstOrNull { it.id == app?.visaTypeId }
                    val item = visa?.checklist?.firstOrNull { it.documentId == documentId }
                    val upload = app?.documents?.get(documentId) ?: DocumentUpload(documentId, item?.name ?: "")
                    val ruleResult = if (app != null && visa != null && upload.extraction != null) {
                        RequirementEvaluator.evaluateForDocument(catalog, visa, app, documentId)
                    } else {
                        null
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = app == null || catalog == null,
                        documentName = item?.name ?: upload.documentName,
                        item = item,
                        upload = upload,
                        ruleResult = ruleResult,
                    )
                }
        }
    }

    fun flagField(field: ExtractedField) {
        viewModelScope.launch {
            container.applications.flagFieldIncorrect(
                applicationId = applicationId,
                documentId = documentId,
                fieldKey = field.key,
                incorrect = !field.flaggedIncorrect,
            )
            _uiState.value = _uiState.value.copy(
                message = if (!field.flaggedIncorrect) {
                    "Thanks — we marked “${field.label}” for manual review."
                } else {
                    "Removed the flag from “${field.label}”."
                },
                messageIsError = false,
            )
        }
    }

    fun requestManualReview() {
        viewModelScope.launch {
            container.applications.requestManualReview(
                applicationId = applicationId,
                documentId = documentId,
                reason = "Manual check requested from extraction review.",
            )
            _uiState.value = _uiState.value.copy(
                message = "Manual review requested. Your agency will verify this document.",
                messageIsError = false,
            )
        }
    }

    fun editField(fieldKey: String, newValue: String) {
        viewModelScope.launch {
            container.applications.updateExtractedField(
                applicationId = applicationId,
                documentId = documentId,
                fieldKey = fieldKey,
                newValue = newValue,
            )
            _uiState.value = _uiState.value.copy(
                message = "Saved your correction. Dependent checks were recalculated and now use the corrected value.",
                messageIsError = false,
            )
        }
    }

    fun confirmField(fieldKey: String, confirmed: Boolean) {
        viewModelScope.launch {
            container.applications.confirmField(
                applicationId = applicationId,
                documentId = documentId,
                fieldKey = fieldKey,
                confirmed = confirmed,
            )
            _uiState.value = _uiState.value.copy(
                message = if (confirmed) {
                    "Confirmed. Confirmation records your review only — it does not verify authenticity."
                } else {
                    "Confirmation removed."
                },
                messageIsError = false,
            )
        }
    }

    fun retryProcessing() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = null)
            val result = container.applications.reprocessDocument(applicationId, documentId)
            _uiState.value = _uiState.value.copy(
                message = when (result) {
                    is com.tripguard.app.domain.model.AppResult.Success ->
                        "Reprocessing finished on this device."

                    is com.tripguard.app.domain.model.AppResult.Failure ->
                        result.error.message
                },
                messageIsError = result is com.tripguard.app.domain.model.AppResult.Failure,
            )
        }
    }

    fun refresh() {
        viewModelScope.launch { container.applications.refresh(applicationId) }
    }

    fun removeDocument() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = null)
            when (val result = container.applications.deleteDocument(applicationId, documentId)) {
                is com.tripguard.app.domain.model.AppResult.Success ->
                    _uiState.value = _uiState.value.copy(removed = true)

                is com.tripguard.app.domain.model.AppResult.Failure ->
                    _uiState.value = _uiState.value.copy(
                        message = result.error.message,
                        messageIsError = true,
                    )
            }
        }
    }
}

@Composable
fun ExtractionReviewScreen(
    onBack: () -> Unit,
    onDone: (applicationId: String, documentId: String) -> Unit,
    onOpenUpload: (applicationId: String, documentId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExtractionReviewViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val extraction = state.upload.extraction
    val validation = state.upload.imageValidation
    var editingField by remember { mutableStateOf<ExtractedField?>(null) }
    var showRawText by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.removed) {
        if (state.removed) onDone(state.applicationId, state.documentId)
    }

    editingField?.let { field ->
        FieldEditDialog(
            field = field,
            onDismiss = { editingField = null },
            onSave = { newValue ->
                viewModel.editField(field.key, newValue)
                editingField = null
            },
        )
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove this document?") },
            text = {
                Text(
                    "This removes the stored copy from this device. It does not contact your agency " +
                        "and cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        viewModel.removeDocument()
                    },
                ) { Text("Remove", color = SoftRedFg) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Review extracted details", onBack = onBack)

        if (state.isLoading) {
            LoadingState(message = "Loading extracted details…")
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.md,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                TripGuardCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(state.documentName, style = MaterialTheme.typography.titleMedium, color = Navy)
                                state.item?.reason?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                            DocumentStatusChip(state.upload.state)
                        }
                        if (extraction != null || state.upload.imageValidation != null) {
                            Spacer(Modifier.height(Spacing.sm))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = SoftEmeraldFg,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    text = "Processed on this device",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SoftEmeraldFg,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            state.message?.let { message ->
                item {
                    InfoBanner(
                        text = message,
                        containerColor = if (state.messageIsError) SoftRedBg else SoftBlueBg,
                        contentColor = if (state.messageIsError) SoftRedFg else SoftBlueFg,
                    )
                }
            }

            when {
                extraction != null -> {
                    item {
                        InfoBanner(
                            text = "These values were read automatically and can contain mistakes. " +
                                "Check every field against your original document. A flag sends it for manual review.",
                            containerColor = SoftAmberBg,
                            contentColor = SoftAmberFg,
                            icon = Icons.Outlined.Info,
                        )
                    }

                    extraction.warnings.forEach { warning ->
                        item {
                            InfoBanner(
                                text = warning,
                                containerColor = SoftAmberBg,
                                contentColor = SoftAmberFg,
                                icon = Icons.Outlined.Info,
                            )
                        }
                    }

                    state.ruleResult?.let { result ->
                        item {
                            RuleResultCard(result = result)
                        }
                    }

                    if (extraction.fields.isEmpty()) {
                        item {
                            InfoBanner(
                                text = "Processing finished but no fields were returned for this document. " +
                                    "You can request a manual review.",
                                containerColor = SoftAmberBg,
                                contentColor = SoftAmberFg,
                            )
                        }
                    } else {
                        val primaryKeys = primaryKeysFor(state.documentId)
                        val primaryFields = extraction.fields
                            .filter { it.key in primaryKeys }
                            .sortedBy { primaryKeys.indexOf(it.key) }
                        val secondaryFields = extraction.fields.filter { it.key !in primaryKeys }
                        val showFields = primaryFields.ifEmpty { extraction.fields }
                        val moreFields = if (primaryFields.isEmpty()) emptyList() else secondaryFields

                        item {
                            Text(
                                text = "EXTRACTED FIELDS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(showFields, key = { it.key }) { field ->
                            ExtractedFieldRow(
                                field = field,
                                onToggleFlag = { viewModel.flagField(field) },
                                onEdit = { editingField = field },
                                onConfirm = { confirmed -> viewModel.confirmField(field.key, confirmed) },
                            )
                        }

                        if (moreFields.isNotEmpty()) {
                            item {
                                MoreDataCard(fields = moreFields) { field ->
                                    ExtractedFieldRow(
                                        field = field,
                                        onToggleFlag = { viewModel.flagField(field) },
                                        onEdit = { editingField = field },
                                        onConfirm = { confirmed -> viewModel.confirmField(field.key, confirmed) },
                                    )
                                }
                            }
                        }

                        item {
                            ExtractionMeta(
                                method = extraction.method,
                                processedAt = extraction.processedAt,
                                pageCount = extraction.pageCount,
                                provider = extraction.provider,
                                overallConfidence = extraction.overallConfidence,
                                fieldCount = extraction.fields.size,
                            )
                        }
                    }

                    extraction.rawText?.takeIf { it.isNotBlank() }?.let { raw ->
                        item {
                            RawTextCard(
                                rawText = raw,
                                expanded = showRawText,
                                onToggle = { showRawText = !showRawText },
                            )
                        }
                    }

                    item {
                        PrimaryButton(
                            text = "Details look correct",
                            onClick = { onDone(state.applicationId, state.documentId) },
                            icon = Icons.Outlined.CheckCircle,
                        )
                    }
                    item {
                        SecondaryButton(
                            text = "Something looks wrong",
                            onClick = viewModel::requestManualReview,
                            icon = Icons.Outlined.ReportProblem,
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            InlineIconTextButton(
                                text = "Replace",
                                icon = Icons.Outlined.CloudUpload,
                                onClick = { onOpenUpload(state.applicationId, state.documentId) },
                                modifier = Modifier.weight(1f),
                            )
                            InlineIconTextButton(
                                text = "Reprocess",
                                icon = Icons.Outlined.Refresh,
                                onClick = viewModel::retryProcessing,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    item {
                        InlineIconTextButton(
                            text = "Remove document",
                            icon = Icons.Outlined.Delete,
                            onClick = { showRemoveDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                validation != null -> {
                    item {
                        PhotoReviewCard(validation = validation)
                    }
                    item {
                        PrimaryButton(
                            text = "Details look correct",
                            onClick = { onDone(state.applicationId, state.documentId) },
                            icon = Icons.Outlined.CheckCircle,
                        )
                    }
                    item {
                        SecondaryButton(
                            text = "Something looks wrong",
                            onClick = viewModel::requestManualReview,
                            icon = Icons.Outlined.ReportProblem,
                        )
                    }
                    item {
                        InlineIconTextButton(
                            text = "Replace photograph",
                            icon = Icons.Outlined.CloudUpload,
                            onClick = { onOpenUpload(state.applicationId, state.documentId) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        InlineIconTextButton(
                            text = "Remove document",
                            icon = Icons.Outlined.Delete,
                            onClick = { showRemoveDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                state.upload.state == DocumentState.PROCESSING || state.upload.state == DocumentState.UPLOADING -> {
                    item { PendingCard(processing = true) }
                    item {
                        SecondaryButton(
                            text = "Check again",
                            onClick = viewModel::refresh,
                            icon = Icons.Outlined.Info,
                        )
                    }
                }

                state.upload.state == DocumentState.NOT_UPLOADED || state.upload.state == DocumentState.UPLOAD_FAILED -> {
                    item {
                        InfoBanner(
                            text = "This document has not been uploaded yet. Upload it to run detail extraction.",
                            containerColor = SoftAmberBg,
                            contentColor = SoftAmberFg,
                        )
                    }
                    item {
                        PrimaryButton(
                            text = "Upload document",
                            onClick = { onOpenUpload(state.applicationId, state.documentId) },
                            icon = Icons.Outlined.CloudUpload,
                        )
                    }
                }

                else -> {
                    state.upload.lastError?.let { error ->
                        item {
                            InfoBanner(
                                text = "Processing did not produce readable fields: $error",
                                containerColor = SoftRedBg,
                                contentColor = SoftRedFg,
                                icon = Icons.Outlined.ReportProblem,
                            )
                        }
                    }
                    item { PendingCard(processing = false) }
                    if (state.upload.hasFile) {
                        item {
                            PrimaryButton(
                                text = "Retry processing on this device",
                                onClick = viewModel::retryProcessing,
                                icon = Icons.Outlined.Refresh,
                            )
                        }
                    }
                    item {
                        SecondaryButton(
                            text = "Back to checklist",
                            onClick = { onDone(state.applicationId, state.documentId) },
                        )
                    }
                    item {
                        InlineIconTextButton(
                            text = "Request manual review",
                            icon = Icons.Outlined.ReportProblem,
                            onClick = viewModel::requestManualReview,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        InlineIconTextButton(
                            text = "Remove document",
                            icon = Icons.Outlined.Delete,
                            onClick = { showRemoveDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtractionMeta(
    method: OcrMethod,
    processedAt: Long,
    pageCount: Int?,
    provider: String?,
    overallConfidence: Float?,
    fieldCount: Int,
) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = SoftBlueFg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text("$fieldCount field(s) extracted", style = MaterialTheme.typography.bodyMedium, color = Navy)
                val parts = buildList {
                    add("Method: ${method.label}")
                    pageCount?.let { add("$it page(s)") }
                    add("Processed: ${formatTimestamp(processedAt)}")
                    provider?.let { add("Engine: $it") }
                    overallConfidence?.let { add("Overall confidence: ${(it * 100).toInt()}%") }
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ExtractedFieldRow(
    field: ExtractedField,
    onToggleFlag: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (field.flaggedIncorrect) SoftRedFg.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                ConfidencePill(field.confidence)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = field.value.ifBlank { "Not detected." },
                style = MaterialTheme.typography.bodyLarge,
                color = if (field.value.isBlank()) TextSecondary else Navy,
                fontWeight = FontWeight.Medium,
            )

            field.originalValue?.takeIf { it != field.value }?.let { original ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Corrected from: $original",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(Spacing.xs))
            val provenance = buildList {
                field.method?.let { add(it.label) }
                add(if (field.userReviewed) "you confirmed this value" else "not confirmed")
            }.joinToString(" · ")
            Text(
                text = provenance,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            field.warning?.let { warning ->
                Spacer(Modifier.height(Spacing.xs))
                InfoBanner(
                    text = warning,
                    containerColor = SoftAmberBg,
                    contentColor = SoftAmberFg,
                    icon = Icons.Outlined.Info,
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineIconTextButton(
                    text = "Correct this value",
                    icon = Icons.Outlined.Edit,
                    onClick = onEdit,
                )
                Spacer(Modifier.weight(1f))
                InlineIconTextButton(
                    text = if (field.userReviewed) "Confirmed" else "Confirm",
                    icon = if (field.userReviewed) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem,
                    onClick = { onConfirm(!field.userReviewed) },
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(onClick = onToggleFlag)
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    imageVector = if (field.flaggedIncorrect) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem,
                    contentDescription = null,
                    tint = if (field.flaggedIncorrect) SoftAmberFg else TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = if (field.flaggedIncorrect) "Flagged for manual review — tap to undo" else "This is incorrect",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (field.flaggedIncorrect) SoftAmberFg else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RuleResultCard(result: RequirementEvaluation) {
    val (bg, fg, icon) = when (result.outcome) {
        EvaluationOutcome.SATISFIED -> Triple(SoftEmeraldBg, SoftEmeraldFg, Icons.Outlined.CheckCircle)
        EvaluationOutcome.POTENTIAL_ISSUE,
        EvaluationOutcome.MISSING,
        -> Triple(SoftRedBg, SoftRedFg, Icons.Outlined.ReportProblem)

        else -> Triple(SoftAmberBg, SoftAmberFg, Icons.Outlined.Info)
    }
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Rule check result",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "The values read from this document were matched against the configured rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        KeyValueRow("Outcome", result.outcome.label)
        KeyValueRow("Rule status", EvidenceExplanation.ruleVerificationLabel(result.ruleVerification))
        result.extractedValue?.let {
            KeyValueRow("Extracted value", it)
        }
        Spacer(Modifier.height(Spacing.sm))
        InfoBanner(
            text = result.reason,
            containerColor = bg,
            contentColor = fg,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = result.nextAction,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun RawTextCard(rawText: String, expanded: Boolean, onToggle: () -> Unit) {
    TripGuardCard {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = Spacing.xs),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = SoftBlueFg,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "Raw text read on this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = rawText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun FieldEditDialog(
    field: ExtractedField,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(field.key) { mutableStateOf(field.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct “${field.label}”", color = Navy) },
        text = {
            Column {
                Text(
                    text = "Changing a value invalidates the automatic match and re-runs any dependent checks " +
                        "using your correction. Review against your original document.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save correction") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "unknown"
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun ConfidencePill(confidence: Float?) {
    if (confidence == null) return
    val (bg, fg) = when {
        confidence >= 0.85f -> SoftEmeraldBg to SoftEmeraldFg
        confidence >= 0.6f -> SoftAmberBg to SoftAmberFg
        else -> SoftRedBg to SoftRedFg
    }
    StatusChip(text = "${(confidence * 100).toInt()}%", background = bg, foreground = fg)
}

@Composable
private fun PendingCard(processing: Boolean) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (processing) Icons.Outlined.HourglassTop else Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = SoftAmberFg,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (processing) "Processing in progress" else "Waiting for processing",
                    style = MaterialTheme.typography.titleSmall,
                    color = Navy,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Detail extraction runs on-device without internet access. " +
                        "Your document is processed locally on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

/** Fields worth showing by default on the extraction screen, per document type. */
private val primaryFieldKeysByDocument = listOf(
    "passport" to listOf("full_name", "passport_number", "nationality", "date_of_birth", "expiry_date"),
    "ticket" to listOf("passenger_name", "booking_reference", "travel_date", "return_date"),
    "flight" to listOf("passenger_name", "booking_reference", "travel_date", "return_date"),
    "insurance" to listOf("insured_name", "policy_number", "coverage_start", "coverage_end", "insurer_name"),
    "bank" to listOf("account_holder_name", "bank_name", "closing_balance", "statement_period"),
    "accommodation" to listOf("guest_name", "hotel_name", "checkin_date", "checkout_date"),
    "residence" to listOf("guest_name", "hotel_name", "checkin_date", "checkout_date"),
)

private fun primaryKeysFor(documentId: String): List<String> =
    primaryFieldKeysByDocument.firstOrNull { (needle, _) ->
        documentId.contains(needle, ignoreCase = true)
    }?.second.orEmpty()

@Composable
private fun MoreDataCard(fields: List<ExtractedField>, fieldContent: @Composable (ExtractedField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TripGuardCard {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { expanded = !expanded })
                    .padding(vertical = Spacing.xs),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = SoftBlueFg,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "More extracted data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${fields.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.xs))
                fields.forEach { field -> fieldContent(field) }
            }
        }
    }
}

@Composable
private fun PhotoReviewCard(validation: ImageValidation) {
    val m = validation.measurements
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(SoftBlueBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = SoftBlueFg,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Image checked on this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "The photograph was measured locally; it never leaves this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        KeyValueRow("Width", "${m.widthPx} px")
        KeyValueRow("Height", "${m.heightPx} px")
        KeyValueRow("Aspect ratio", m.aspectRatioLabel)
        KeyValueRow("Decodable", if (m.decodable) "Yes" else "No")
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Configured rule result",
            style = MaterialTheme.typography.labelMedium,
            color = Navy,
        )
        Spacer(Modifier.height(Spacing.xs))
        val (bg, fg) = when (validation.summaryOutcome) {
            EvaluationOutcome.SATISFIED -> SoftEmeraldBg to SoftEmeraldFg
            EvaluationOutcome.MISSING,
            EvaluationOutcome.POTENTIAL_ISSUE,
            -> SoftRedBg to SoftRedFg

            else -> SoftAmberBg to SoftAmberFg
        }
        StatusChip(text = validation.summaryOutcome.label, background = bg, foreground = fg)
        Spacer(Modifier.height(Spacing.xs))
        InfoBanner(
            text = validation.reason,
            containerColor = SoftBlueBg,
            contentColor = SoftBlueFg,
        )
        if (validation.checks.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            validation.checks.forEach { check ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = check.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (check.configured) check.outcome.label else "Not configured",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (check.configured) MaterialTheme.colorScheme.onSurface else TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
