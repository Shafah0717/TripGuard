package com.tripguard.app.ui.screens.profile

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.BuildConfig
import com.tripguard.app.domain.model.SourceRef
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.KeyValueRow
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.SoftSlateBg
import com.tripguard.app.ui.theme.SoftSlateFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val datasetVersion: String = "",
    val datasetId: String = "",
    val destination: String = "",
    val scope: String = "",
    val lastVerifiedAt: String? = null,
    val sources: List<SourceRef> = emptyList(),
    val limitations: List<String> = emptyList(),
)

class ProfileViewModel(application: Application) : TripGuardViewModel(application) {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            container.catalog.catalog.collect { catalog ->
                catalog ?: return@collect
                _uiState.value = ProfileUiState(
                    datasetVersion = catalog.schemaVersion,
                    datasetId = catalog.datasetId,
                    destination = "${catalog.destinationEmirate}, ${catalog.destinationCountry}",
                    scope = "${catalog.applicantScope.nationality} · ${catalog.applicantScope.passportType} · ${catalog.applicantScope.purpose}",
                    lastVerifiedAt = catalog.lastVerifiedAt,
                    sources = catalog.sources.values.toList(),
                    limitations = catalog.limitations,
                )
            }
        }
    }
}

@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Profile")

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Guest traveler",
                                style = MaterialTheme.typography.titleMedium,
                                color = Navy,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Your data stays on this device until your agency reviews it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }

            item {
                InfoBanner(
                    text = "TripGuard helps you organise and check Dubai tourist-visa documents for Indian " +
                        "ordinary passports. It is an assistant, not a government or embassy service.",
                    containerColor = SoftSlateBg,
                    contentColor = SoftSlateFg,
                    icon = Icons.Outlined.Info,
                )
            }

            item {
                TripGuardCard {
                    SectionHeader("Checklist source", Icons.Outlined.Public)
                    Spacer(Modifier.height(Spacing.xs))
                    KeyValueRow("Dataset version", state.datasetVersion.ifBlank { "—" })
                    KeyValueRow("Destination", state.destination.ifBlank { "—" })
                    KeyValueRow("Applicant scope", state.scope.ifBlank { "—" })
                    KeyValueRow("Last verified", state.lastVerifiedAt ?: "—")
                }
            }

            if (state.sources.isNotEmpty()) {
                item {
                    TripGuardCard {
                        SectionHeader("Official sources", Icons.Outlined.Public)
                        Spacer(Modifier.height(Spacing.xs))
                        state.sources.forEach { source ->
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Navy,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = source.organization,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                }
            }

            if (state.limitations.isNotEmpty()) {
                item {
                    TripGuardCard {
                        SectionHeader("Important limitations", Icons.Outlined.Info)
                        Spacer(Modifier.height(Spacing.xs))
                        state.limitations.forEach { limitation ->
                            Text(
                                text = "•  $limitation",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = Spacing.xs),
                            )
                        }
                    }
                }
            }

            item {
                TripGuardCard {
                    KeyValueRow("App version", BuildConfig.VERSION_NAME)
                    KeyValueRow("Dataset id", state.datasetId.ifBlank { "—" })
                }
            }

            item {
                PrimaryButton(
                    text = "Settings",
                    onClick = onOpenSettings,
                    icon = Icons.Outlined.Settings,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = Navy)
    }
}
