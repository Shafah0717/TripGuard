package com.tripguard.app.ui.screens.upload

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ChecklistItem
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.DocumentStatusChip
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.InlineIconTextButton
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.RequirementChip
import com.tripguard.app.ui.components.SecondaryButton
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.SoftAmberBg
import com.tripguard.app.ui.theme.SoftAmberFg
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UploadUiState(
    val applicationId: String = "",
    val documentId: String = "",
    val isLoading: Boolean = true,
    val documentName: String = "",
    val item: ChecklistItem? = null,
    val upload: DocumentUpload = DocumentUpload("", ""),
    val policy: DocumentPolicy = DocumentPolicy(),
    val isStaging: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val stagedSuccessfully: Boolean = false,
)

class DocumentUploadViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Upload.ARG_APPLICATION_ID).orEmpty()
    private val documentId: String =
        savedStateHandle.get<String>(Screen.Upload.ARG_DOCUMENT_ID).orEmpty()

    private val _uiState = MutableStateFlow(
        UploadUiState(applicationId = applicationId, documentId = documentId),
    )
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

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
                    val upload = app?.documents?.get(documentId)
                        ?: DocumentUpload(documentId, item?.name ?: "Document")
                    _uiState.value = _uiState.value.copy(
                        isLoading = app == null || catalog == null,
                        documentName = item?.name ?: upload.documentName,
                        item = item,
                        upload = upload,
                        policy = policy,
                    )
                }
        }
    }

    fun chooseFile(uri: String?) {
        if (uri == null) return
        if (_uiState.value.isStaging) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isStaging = true,
                message = null,
                stagedSuccessfully = false,
            )
            val result = container.applications.stageDocument(
                applicationId = applicationId,
                documentId = documentId,
                documentName = _uiState.value.documentName,
                sourceUri = uri,
            )
            _uiState.value = when (result) {
                is AppResult.Success -> _uiState.value.copy(
                    isStaging = false,
                    message = "Document saved locally and processed on this device.",
                    messageIsError = false,
                    stagedSuccessfully = true,
                )

                is AppResult.Failure -> _uiState.value.copy(
                    isStaging = false,
                    message = result.error.message,
                    messageIsError = true,
                )
            }
        }
    }

    fun deleteDocument() {
        if (_uiState.value.isStaging) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isStaging = true, message = null)
            val result = container.applications.deleteDocument(applicationId, documentId)
            _uiState.value = when (result) {
                is AppResult.Success -> _uiState.value.copy(
                    isStaging = false,
                    message = "Document copy deleted from local storage.",
                    messageIsError = false,
                )

                is AppResult.Failure -> _uiState.value.copy(
                    isStaging = false,
                    message = result.error.message,
                    messageIsError = true,
                )
            }
        }
    }

    fun requestManualReview() {
        viewModelScope.launch {
            container.applications.requestManualReview(
                applicationId = applicationId,
                documentId = documentId,
                reason = "Manual review requested by the applicant.",
            )
            _uiState.value = _uiState.value.copy(
                message = "Manual review requested. Your agency will check this document.",
                messageIsError = false,
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

private fun policyMimeTypes(policy: DocumentPolicy): Array<String> {
    val types = mutableListOf<String>()
    if (policy.isAcceptedExtension("pdf")) types += "application/pdf"
    if (policy.isAcceptedExtension("jpg") || policy.isAcceptedExtension("jpeg") || policy.isAcceptedExtension("png")) {
        types += "image/*"
    }
    return if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray()
}

@Composable
fun DocumentUploadScreen(
    onBack: () -> Unit,
    onReviewExtraction: (applicationId: String, documentId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentUploadViewModel = viewModel(),
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
        TripGuardTopBar(title = state.documentName.ifBlank { "Upload document" }, onBack = onBack)

        if (state.isLoading) {
            LoadingState(message = "Loading document…")
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
                state.item?.let { item ->
                    Spacer(Modifier.height(Spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        RequirementChip(item.applicability)
                    }
                    item.reason?.let { reason ->
                        Spacer(Modifier.height(Spacing.sm))
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }

            val localPath = state.upload.localPath
            if (localPath != null && state.upload.state != DocumentState.UPLOAD_FAILED) {
                DocumentPreview(filePath = localPath)
                FileSummary(state.upload)
            }

            UploadProgress(state.upload)

            state.message?.let { message ->
                InfoBanner(
                    text = message,
                    containerColor = if (state.messageIsError) SoftRedBg else SoftBlueBg,
                    contentColor = if (state.messageIsError) SoftRedFg else SoftBlueFg,
                    icon = if (state.messageIsError) Icons.Outlined.Info else Icons.Outlined.Info,
                )
            }

            InfoBanner(
                text = "TripGuard runs OCR to read the details from your document. OCR can make mistakes, so always " +
                    "check the extracted information against the original.",
                containerColor = SoftAmberBg,
                contentColor = SoftAmberFg,
                icon = Icons.Outlined.Info,
            )

            InstructionsCard(policy = state.policy)

            val hasFile = state.upload.localPath != null
            if (!hasFile) {
                PrimaryButton(
                    text = "Choose file or image",
                    onClick = { picker.launch(policyMimeTypes(state.policy)) },
                    loading = state.isStaging,
                    icon = Icons.Outlined.CloudUpload,
                )
            } else {
                PrimaryButton(
                    text = "Replace document",
                    onClick = { picker.launch(policyMimeTypes(state.policy)) },
                    loading = state.isStaging,
                    icon = Icons.Outlined.SwapHoriz,
                )
                SecondaryButton(
                    text = "Delete local document",
                    onClick = viewModel::deleteDocument,
                    icon = Icons.Outlined.Delete,
                )
                SecondaryButton(
                    text = "Review extracted details",
                    onClick = { onReviewExtraction(state.applicationId, state.documentId) },
                    icon = Icons.Outlined.Description,
                )
                if (state.upload.state in setOf(
                        DocumentState.UPLOADED,
                        DocumentState.PROCESSING,
                        DocumentState.NEEDS_REVIEW,
                        DocumentState.POTENTIAL_ISSUE,
                    )
                ) {
                    InlineIconTextButton(
                        text = "Request manual review",
                        icon = Icons.Outlined.RateReview,
                        onClick = viewModel::requestManualReview,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadProgress(upload: DocumentUpload) {
    val progress = upload.uploadProgress
    if (upload.state == DocumentState.UPLOADING) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (progress != null) "Uploading ${(progress * 100).toInt()}%" else "Uploading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    } else if (upload.state == DocumentState.PROCESSING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = SoftAmberFg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("Processing document…", style = MaterialTheme.typography.bodySmall, color = SoftAmberFg)
        }
    }
}

@Composable
private fun FileSummary(upload: DocumentUpload) {
    TripGuardCard {
        Text("Saved file", style = MaterialTheme.typography.labelLarge, color = Navy)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = upload.fileName ?: "Document",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatSize(upload.fileSizeBytes) + (upload.mimeType?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        upload.lastError?.let { error ->
            Spacer(Modifier.height(Spacing.xs))
            Text(error, style = MaterialTheme.typography.bodySmall, color = SoftAmberFg)
        }
    }
}

@Composable
private fun InstructionsCard(policy: DocumentPolicy) {
    TripGuardCard {
        Text("Before you upload", style = MaterialTheme.typography.labelLarge, color = Navy)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Accepted formats: ${policy.acceptedFormatsLabel}\nMaximum size: ${policy.formattedMaxSize}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Make sure the whole document is visible, well lit and not blurred.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun DocumentPreview(filePath: String) {
    val isImage = filePath.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png")
    if (!isImage) return

    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 3 }
                BitmapFactory.decodeFile(filePath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }

    bitmap?.let {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(Spacing.md))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Image(
                bitmap = it,
                contentDescription = "Document preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } ?: Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(Spacing.md))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = TextSecondary)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "Unknown size"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
