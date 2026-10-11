package com.tripguard.app.ui.screens.applicant

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ApplicantDetails
import com.tripguard.app.domain.validation.ApplicantValidator
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.FixedField
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LabeledTextField
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ApplicantUiState(
    val isLoading: Boolean = true,
    val applicationId: String,
    val visaTypeName: String = "",
    val fullName: String = "",
    val contactEmail: String = "",
    val contactPhone: String = "",
    val errors: Map<String, String> = emptyMap(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
)

class ApplicantDetailsViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.ApplicantDetails.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ApplicantUiState(applicationId = applicationId))
    val uiState: StateFlow<ApplicantUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val application = container.applications.observeApplication(applicationId)
                .filterNotNull()
                .first()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                visaTypeName = application.visaTypeName,
                fullName = application.applicant.fullName,
                contactEmail = application.applicant.contactEmail,
                contactPhone = application.applicant.contactPhone,
            )
        }
    }

    fun onFullNameChange(value: String) {
        _uiState.value = _uiState.value.copy(fullName = value, errors = _uiState.value.errors - "fullName")
    }

    fun onEmailChange(value: String) {
        _uiState.value = _uiState.value.copy(contactEmail = value, errors = _uiState.value.errors - "contactEmail")
    }

    fun onPhoneChange(value: String) {
        _uiState.value = _uiState.value.copy(contactPhone = value, errors = _uiState.value.errors - "contactPhone")
    }

    fun save() {
        val current = _uiState.value
        val details = ApplicantDetails(
            fullName = current.fullName,
            contactEmail = current.contactEmail,
            contactPhone = current.contactPhone,
        )
        val errors = ApplicantValidator.validate(details)
        if (errors.isNotEmpty()) {
            _uiState.value = current.copy(errors = errors)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, saveError = null)
            when (val result = container.applications.updateApplicant(applicationId, details)) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveError = result.error.message,
                )
            }
        }
    }
}

@Composable
fun ApplicantDetailsScreen(
    onBack: () -> Unit,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApplicantDetailsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onContinue(state.applicationId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Applicant details", onBack = onBack)

        if (state.isLoading) {
            LoadingState(message = "Loading application…")
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
        ) {
            InfoBanner(
                text = "Enter your name exactly as it appears on your passport. We only collect the details this application needs.",
                containerColor = SoftBlueBg,
                contentColor = SoftBlueFg,
                icon = Icons.Outlined.Badge,
            )

            Spacer(Modifier.height(Spacing.lg))

            LabeledTextField(
                label = "Full name (as on passport)",
                value = state.fullName,
                onValueChange = viewModel::onFullNameChange,
                placeholder = "e.g. Ananya Subramanian",
                error = state.errors["fullName"],
                keyboardType = KeyboardType.Text,
            )

            Spacer(Modifier.height(Spacing.md))

            FixedField(
                label = "Nationality",
                value = ApplicantDetails.SUPPORTED_NATIONALITY,
                note = "Fixed to the supported scope for this checklist.",
            )

            Spacer(Modifier.height(Spacing.md))

            FixedField(
                label = "Passport type",
                value = ApplicantDetails.SUPPORTED_PASSPORT_TYPE,
            )

            Spacer(Modifier.height(Spacing.md))

            FixedField(
                label = "Purpose of travel",
                value = ApplicantDetails.SUPPORTED_PURPOSE,
            )

            Spacer(Modifier.height(Spacing.md))

            LabeledTextField(
                label = "Email (optional)",
                value = state.contactEmail,
                onValueChange = viewModel::onEmailChange,
                placeholder = "name@example.com",
                error = state.errors["contactEmail"],
                supportingText = "Used only for application updates from your agency.",
                keyboardType = KeyboardType.Email,
            )

            Spacer(Modifier.height(Spacing.md))

            LabeledTextField(
                label = "Phone (optional)",
                value = state.contactPhone,
                onValueChange = viewModel::onPhoneChange,
                placeholder = "+91 …",
                error = state.errors["contactPhone"],
                keyboardType = KeyboardType.Phone,
            )

            state.saveError?.let {
                Spacer(Modifier.height(Spacing.md))
                InfoBanner(text = it, containerColor = SoftRedBg, contentColor = SoftRedFg)
            }

            Spacer(Modifier.height(Spacing.xl))

            PrimaryButton(
                text = "Save and build checklist",
                onClick = viewModel::save,
                loading = state.isSaving,
                icon = Icons.Outlined.PersonOutline,
            )
        }
    }
}
