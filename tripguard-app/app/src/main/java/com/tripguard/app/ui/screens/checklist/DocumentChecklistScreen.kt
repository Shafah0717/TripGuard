package com.tripguard.app.ui.screens.checklist

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ChecklistItem
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.readiness.ReadinessCalculator
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ApplicationStatusChip
import com.tripguard.app.ui.components.DocumentStatusChip
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.RequirementChip
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftSlateBg
import com.tripguard.app.ui.theme.SoftSlateFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ChecklistEntry(
    val item: ChecklistItem,
    val upload: DocumentUpload,
)

data class ChecklistUiState(
    val applicationId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val visaTypeName: String = "",
    val requiredEntries: List<ChecklistEntry> = emptyList(),
    val conditionalEntries: List<ChecklistEntry> = emptyList(),
    val notApplicableEntries: List<ChecklistEntry> = emptyList(),
    val requiredTotal: Int = 0,
    val requiredUploaded: Int = 0,
    val overallStatus: com.tripguard.app.domain.model.ApplicationStatus =
        com.tripguard.app.domain.model.ApplicationStatus.DRAFT,
    val nextStep: String = "",
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
)

class DocumentChecklistViewModel(
    application: android.app.Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Checklist.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ChecklistUiState(applicationId = applicationId))
    val uiState: StateFlow<ChecklistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
            ) { app, catalog -> app to catalog }
                .collect { (app, catalog) ->
                    _uiState.value = buildState(_uiState.value, app, catalog)
                }
        }
    }

    private fun buildState(
        previous: ChecklistUiState,
        app: Application?,
        catalog: VisaCatalog?,
    ): ChecklistUiState {
        if (app == null || catalog == null) return previous.copy(isLoading = true, error = null)
        val visa = catalog.visaTypes.firstOrNull { it.id == app.visaTypeId }
            ?: return previous.copy(isLoading = false, error = "This visa category is no longer available.")
        val checklist = visa.checklist
        val entries = checklist.map { item ->
            ChecklistEntry(item, app.documents[item.documentId] ?: DocumentUpload(item.documentId, item.name))
        }
        val report = ReadinessCalculator.calculate(visa, app)
        return previous.copy(
            isLoading = false,
            error = null,
            visaTypeName = visa.displayName,
            requiredEntries = entries.filter { it.item.applicability == RequirementApplicability.REQUIRED },
            conditionalEntries = entries.filter { it.item.applicability == RequirementApplicability.CONDITIONAL },
            notApplicableEntries = entries.filter { it.item.applicability == RequirementApplicability.NOT_APPLICABLE },
            requiredTotal = report.requiredTotal,
            requiredUploaded = report.requiredUploaded,
            overallStatus = report.overall,
            nextStep = report.nextStep,
        )
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, refreshMessage = null)
            container.catalog.load(forceRefresh = true)
            val result = container.applications.refresh(applicationId)
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                refreshMessage = when (result) {
                    is com.tripguard.app.domain.model.AppResult.Success -> "Status refreshed."
                    is com.tripguard.app.domain.model.AppResult.Failure ->
                        "Couldn't reach the backend. Showing data saved on this device."
                },
            )
        }
    }
}

@Composable
fun DocumentChecklistScreen(
    onBack: () -> Unit,
    onOpenDocument: (applicationId: String, documentId: String) -> Unit,
    onContinue: (applicationId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentChecklistViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(
            title = "Document checklist",
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            },
        )

        if (state.isLoading) {
            LoadingState(message = "Building your checklist…")
        } else if (state.error != null) {
            com.tripguard.app.ui.components.ErrorStateView(
                message = state.error ?: "Checklist unavailable.",
                onRetry = { viewModel.refresh() },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    ChecklistHeader(
                        visaName = state.visaTypeName,
                        uploaded = state.requiredUploaded,
                        total = state.requiredTotal,
                        status = state.overallStatus,
                        nextStep = state.nextStep,
                    )
                }

                state.refreshMessage?.let { message ->
                    item { InfoBanner(text = message, containerColor = SoftBlueBg, contentColor = SoftBlueFg) }
                }

                item {
                    InfoBanner(
                        text = "A document marked “Extracted” or “Uploaded” still needs your agency's review. " +
                            "Processing is not the same as acceptance.",
                        containerColor = SoftSlateBg,
                        contentColor = SoftSlateFg,
                        icon = Icons.Outlined.Info,
                    )
                }

                if (state.requiredEntries.isNotEmpty()) {
                    item { SectionLabel("Required") }
                    items(state.requiredEntries, key = { it.item.documentId }) { entry ->
                        ChecklistRow(entry) { onOpenDocument(state.applicationId, entry.item.documentId) }
                    }
                }

                if (state.conditionalEntries.isNotEmpty()) {
                    item { SectionLabel("Conditional") }
                    items(state.conditionalEntries, key = { it.item.documentId }) { entry ->
                        ChecklistRow(entry) { onOpenDocument(state.applicationId, entry.item.documentId) }
                    }
                }

                if (state.notApplicableEntries.isNotEmpty()) {
                    item { SectionLabel("Not applicable to your scope") }
                    items(state.notApplicableEntries, key = { it.item.documentId }) { entry ->
                        ChecklistRow(entry, dimmed = true) { }
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.xs))
                    PrimaryButton(
                        text = "Review readiness",
                        onClick = { onContinue(state.applicationId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = Spacing.sm, bottom = 2.dp),
    )
}

@Composable
private fun ChecklistHeader(
    visaName: String,
    uploaded: Int,
    total: Int,
    status: com.tripguard.app.domain.model.ApplicationStatus,
    nextStep: String,
) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = visaName, style = MaterialTheme.typography.titleMedium, color = Navy, modifier = Modifier.weight(1f))
            ApplicationStatusChip(status)
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "$uploaded of $total required documents prepared",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else uploaded.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        InfoBanner(text = nextStep, containerColor = SoftBlueBg, contentColor = SoftBlueFg)
    }
}

@Composable
private fun ChecklistRow(
    entry: ChecklistEntry,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (dimmed) 0.6f else 1f
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (dimmed) Modifier else Modifier.clickable(onClick = onClick)),
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.UploadFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Navy,
                    fontWeight = FontWeight.SemiBold,
                )
                entry.item.reason?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    RequirementChip(entry.item.applicability)
                    if (entry.item.applicability != RequirementApplicability.NOT_APPLICABLE) {
                        DocumentStatusChip(entry.upload.state)
                    }
                }
            }
            if (!dimmed) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(20.dp),
                )
            }
        }
    }
}
