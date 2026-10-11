package com.tripguard.app.ui.screens.correction

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
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
import com.tripguard.app.domain.model.AgencyComment
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ChecklistItem
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.DocumentStatusChip
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
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

data class CorrectionUiState(
    val applicationId: String = "",
    val documentId: String = "",
    val isLoading: Boolean = true,
    val documentName: String = "",
    val item: ChecklistItem? = null,
    val upload: DocumentUpload = DocumentUpload("", ""),
    val policy: DocumentPolicy = DocumentPolicy(),
    val comments: List<AgencyComment> = emptyList(),
    val isStaging: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val resolved: Boolean = false,
)

class CorrectionViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Correction.ARG_APPLICATION_ID).orEmpty()
    private val documentId: String =
        savedStateHandle.get<String>(Screen.Correction.ARG_DOCUMENT_ID).orEmpty()

    private val _uiState = MutableStateFlow(
        CorrectionUiState(applicationId = applicationId, documentId = documentId),
    )
    val uiState: StateFlow<CorrectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
                container.applications.documentPolicy,
            ) { app, catalog, policy -> Triple(app, catalog, policy) }
                .collect { (app, catalog, policy) ->
                    val visa = catalog?.visaTypes?.firstOrNull { it.id == app?.visaTypeId }
                    val item = visa?.checklist?.firstOrNull { it.documentId == documentId }
                    val upload = app?.documents?.get(documentId) ?: DocumentUpload(documentId, item?.name ?: "")
                    val comments = app?.agencyComments?.filter { it.documentId == documentId && !it.resolved }.orEmpty()
                    _uiState.value = _uiState.value.copy(
                        isLoading = app == null || catalog == null,
                        documentName = item?.name ?: upload.documentName,
                        item = item,
                        upload = upload,
                        policy = policy,
                        comments = comments,
                    )
                }
        }
    }

    fun chooseFile(uri: String?) {
        if (uri == null || _uiState.value.isStaging) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isStaging = true, message = null, messageIsError = false)
            when (val result = container.applications.stageDocument(
                applicationId = applicationId,
                documentId = documentId,
                documentName = _uiState.value.documentName,
                sourceUri = uri,
            )) {
                is AppResult.Success -> {
                    _uiState.value.comments.forEach { comment ->
                        container.applications.resolveAgencyComment(applicationId, comment.id)
                    }
                    _uiState.value = _uiState.value.copy(
                        isStaging = false,
                        resolved = true,
                        message = "Replacement saved. Your agency will review the corrected document.",
                        messageIsError = false,
                    )
                }

                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isStaging = false,
                    message = result.error.message,
                    messageIsError = true,
                )
            }
        }
    }
}

private fun correctionMimeTypes(policy: DocumentPolicy): Array<String> {
    val types = mutableListOf<String>()
    if (policy.isAcceptedExtension("pdf")) types += "application/pdf"
    if (policy.isAcceptedExtension("jpg") || policy.isAcceptedExtension("jpeg") || policy.isAcceptedExtension("png")) {
        types += "image/*"
    }
    return if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray()
}

@Composable
fun CorrectionScreen(
    onBack: () -> Unit,
    onDone: (applicationId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CorrectionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.chooseFile(uri?.toString()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Resolve correction", onBack = onBack)

        if (state.isLoading) {
            LoadingState(message = "Loading correction request…")
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.resolved) {
                TripGuardCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = SoftEmeraldFg)
                        Spacer(Modifier.padding(horizontal = Spacing.xs))
                        Column {
                            Text(
                                text = "Correction submitted",
                                style = MaterialTheme.typography.titleMedium,
                                color = Navy,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Your replacement document has been saved and the request marked as addressed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(text = "Back to application", onClick = { onDone(state.applicationId) })
                }
                return@Column
            }

            state.comments.forEach { comment ->
                InfoBanner(
                    text = comment.message,
                    containerColor = SoftRedBg,
                    contentColor = SoftRedFg,
                    icon = Icons.Outlined.ErrorOutline,
                )
            }

            TripGuardCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.documentName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Navy,
                        modifier = Modifier.weight(1f),
                    )
                    DocumentStatusChip(state.upload.state)
                }
                state.upload.lastError?.let {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }

            state.message?.let { message ->
                InfoBanner(
                    text = message,
                    containerColor = if (state.messageIsError) SoftRedBg else SoftBlueBg,
                    contentColor = if (state.messageIsError) SoftRedFg else SoftBlueFg,
                )
            }

            InfoBanner(
                text = "Upload a clear replacement. Accepted formats: ${state.policy.acceptedFormatsLabel}. " +
                    "Maximum size: ${state.policy.formattedMaxSize}.",
                containerColor = SoftAmberBg,
                contentColor = SoftAmberFg,
                icon = Icons.Outlined.Info,
            )

            PrimaryButton(
                text = "Upload replacement",
                onClick = { picker.launch(correctionMimeTypes(state.policy)) },
                loading = state.isStaging,
                icon = Icons.Outlined.CloudUpload,
            )
        }
    }
}
