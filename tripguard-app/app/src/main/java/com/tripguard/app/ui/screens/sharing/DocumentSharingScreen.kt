package com.tripguard.app.ui.screens.sharing

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.AgencyHandoff
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.DocumentStatusChip
import com.tripguard.app.ui.components.EmptyStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SectionTitle
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

data class ShareDocument(
    val documentId: String,
    val name: String,
    val state: DocumentState,
    val selected: Boolean,
)

data class SharingUiState(
    val applicationId: String = "",
    val isLoading: Boolean = true,
    val handoff: AgencyHandoff? = null,
    val documents: List<ShareDocument> = emptyList(),
    val consentChecked: Boolean = false,
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val shared: Boolean = false,
)

class DocumentSharingViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Sharing.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(SharingUiState(applicationId = applicationId))
    val uiState: StateFlow<SharingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
            ) { app, catalog -> app to catalog }
                .collect { (app, catalog) ->
                    val handoff = app?.handoff
                    val visa = catalog?.visaTypes?.firstOrNull { it.id == app?.visaTypeId }
                    val selectedByUser = _uiState.value.documents.associate { it.documentId to it.selected }
                    val documents = handoff?.requestedDocumentIds?.map { id ->
                        val name = visa?.checklist?.firstOrNull { it.documentId == id }?.name ?: id
                        val state = app.documents[id]?.state ?: DocumentState.NOT_UPLOADED
                        ShareDocument(
                            documentId = id,
                            name = name,
                            state = state,
                            selected = selectedByUser[id] ?: (state == DocumentState.ACCEPTED || state == DocumentState.EXTRACTED),
                        )
                    }.orEmpty()
                    _uiState.value = _uiState.value.copy(
                        isLoading = app == null || catalog == null,
                        handoff = handoff,
                        documents = documents,
                        shared = handoff?.status == com.tripguard.app.domain.model.HandoffStatus.CONFIRMED,
                    )
                }
        }
    }

    fun toggleDocument(documentId: String) {
        _uiState.value = _uiState.value.copy(
            documents = _uiState.value.documents.map {
                if (it.documentId == documentId) it.copy(selected = !it.selected) else it
            },
            message = null,
        )
    }

    fun setConsent(checked: Boolean) {
        _uiState.value = _uiState.value.copy(consentChecked = checked, message = null)
    }

    fun confirm() {
        val current = _uiState.value
        val selected = current.documents.filter { it.selected }.map { it.documentId }
        if (selected.isEmpty()) {
            _uiState.value = current.copy(
                message = "Select at least one document to share.",
                messageIsError = true,
            )
            return
        }
        if (!current.consentChecked) {
            _uiState.value = current.copy(
                message = "Tick the consent box to confirm you agree to share the selected documents.",
                messageIsError = true,
            )
            return
        }
        if (current.isSubmitting) return
        viewModelScope.launch {
            _uiState.value = current.copy(isSubmitting = true, message = null)
            when (val result = container.applications.confirmDocumentSharing(applicationId, selected)) {
                is AppResult.Success -> {
                    val confirmed = result.data.handoff?.status ==
                        com.tripguard.app.domain.model.HandoffStatus.CONFIRMED
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        shared = confirmed,
                        message = if (confirmed) {
                            "Your selection was recorded and shared with the agency."
                        } else {
                            "The backend has not confirmed the share yet."
                        },
                        messageIsError = !confirmed,
                    )
                }

                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    message = "Sharing was not recorded: ${result.error.message}",
                    messageIsError = true,
                )
            }
        }
    }
}

@Composable
fun DocumentSharingScreen(
    onBack: () -> Unit,
    onViewApplication: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentSharingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Share documents", onBack = onBack)

        val handoff = state.handoff
        when {
            state.isLoading -> LoadingState(message = "Loading handoff details…")

            handoff == null -> EmptyStateView(
                icon = Icons.Outlined.Share,
                title = "Nothing to share yet",
                description = "Your agency has not asked you to share documents for this application yet. " +
                    "You'll be notified here when they do.",
                action = { PrimaryButton(text = "Back to application", onClick = { onViewApplication(state.applicationId) }) },
            )

            else -> {
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
                    if (state.shared) {
                        item {
                            InfoBanner(
                                text = "You've confirmed sharing. The agency can now process your application.",
                                containerColor = SoftEmeraldBg,
                                contentColor = SoftEmeraldFg,
                                icon = Icons.Outlined.CheckCircle,
                            )
                        }
                    } else if (handoff.awaitingConsent) {
                        item {
                            InfoBanner(
                                text = "${handoff.agencyName} is ready to process your application and has asked " +
                                    "you to share the documents below. Nothing is shared until you confirm.",
                                containerColor = SoftAmberBg,
                                contentColor = SoftAmberFg,
                                icon = Icons.Outlined.Info,
                            )
                        }
                    }

                    item {
                        TripGuardCard {
                            SectionTitle(text = "Receiving agency")
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = handoff.agencyName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Navy,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = "Purpose: ${handoff.purpose}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                            handoff.note?.let {
                                Spacer(Modifier.height(Spacing.xs))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                    }

                    item {
                        SectionTitle(
                            text = "Documents requested",
                            subtitle = "Choose exactly what you want to share. You can deselect any document.",
                        )
                    }

                    if (state.documents.isEmpty()) {
                        item {
                            InfoBanner(
                                text = "The agency did not list specific documents. Open the application to review " +
                                    "uploaded documents.",
                                containerColor = SoftSlateBg,
                                contentColor = SoftSlateFg,
                            )
                        }
                    } else {
                        items(state.documents, key = { it.documentId }) { doc ->
                            ShareDocumentRow(
                                document = doc,
                                enabled = !state.shared && handoff.awaitingConsent,
                                onToggle = { viewModel.toggleDocument(doc.documentId) },
                            )
                        }
                    }

                    if (!state.shared && handoff.awaitingConsent) {
                        item {
                            ConsentRow(
                                checked = state.consentChecked,
                                onCheckedChange = viewModel::setConsent,
                                agencyName = handoff.agencyName,
                            )
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

                    if (!state.shared && handoff.awaitingConsent) {
                        item {
                            Button(
                                onClick = viewModel::confirm,
                                enabled = !state.isSubmitting && state.consentChecked,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = if (state.isSubmitting) "Sending…" else "Confirm and share",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    } else {
                        item {
                            PrimaryButton(
                                text = "Back to application",
                                onClick = { onViewApplication(state.applicationId) },
                            )
                        }
                    }

                    item {
                        InfoBanner(
                            text = "Sharing only sends the documents you select to the named agency for visa " +
                                "processing. TripGuard does not submit anything to the UAE government and does " +
                                "not guarantee approval.",
                            containerColor = SoftSlateBg,
                            contentColor = SoftSlateFg,
                            icon = Icons.Outlined.Info,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareDocumentRow(document: ShareDocument, enabled: Boolean, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = document.selected,
                onCheckedChange = { if (enabled) onToggle() },
                enabled = enabled,
            )
            Spacer(Modifier.width(Spacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(document.name, style = MaterialTheme.typography.bodyLarge, color = Navy, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                DocumentStatusChip(document.state)
            }
            Icon(Icons.Outlined.Description, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, agencyName: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SoftBlueBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "I confirm I want to share the selected documents with $agencyName for visa processing.",
                style = MaterialTheme.typography.bodySmall,
                color = Navy,
            )
        }
    }
}
