package com.tripguard.app.ui.screens.applications

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.readiness.ReadinessCalculator
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ApplicationStatusChip
import com.tripguard.app.ui.components.EmptyStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ApplicationListItem(
    val id: String,
    val reference: String,
    val visaTypeName: String,
    val status: ApplicationStatus,
    val createdAt: Long,
    val requiredTotal: Int,
    val requiredUploaded: Int,
    val isActive: Boolean,
    val nextAction: String? = null,
)

data class MyApplicationsUiState(
    val isLoading: Boolean = true,
    val items: List<ApplicationListItem> = emptyList(),
    val syncMessage: String? = null,
)

class MyApplicationsViewModel(application: android.app.Application) : TripGuardViewModel(application) {

    private val _uiState = MutableStateFlow(MyApplicationsUiState())
    val uiState: StateFlow<MyApplicationsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            combine(
                container.applications.observeApplications(),
                container.catalog.catalog,
                container.applications.observeActiveApplicationId(),
            ) { apps, catalog, activeId -> Triple(apps, catalog, activeId) }
                .collect { (apps, catalog, activeId) ->
                    val items = apps
                        .sortedByDescending { it.updatedAt }
                        .map { app -> app.toListItem(catalog, activeId) }
                    _uiState.value = _uiState.value.copy(isLoading = false, items = items)
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncMessage = null)
            var failures = 0
            _uiState.value.items.forEach { item ->
                val result = container.applications.refresh(item.id)
                if (result is com.tripguard.app.domain.model.AppResult.Failure) failures++
            }
            _uiState.value = _uiState.value.copy(
                syncMessage = if (failures == 0) {
                    "Applications refreshed."
                } else {
                    "Some applications couldn't be refreshed — showing data saved on this device."
                },
            )
        }
    }
}

private fun Application.toListItem(catalog: com.tripguard.app.domain.model.VisaCatalog?, activeId: String?): ApplicationListItem {
    val visa = catalog?.visaTypes?.firstOrNull { it.id == visaTypeId }
    val report = visa?.let { ReadinessCalculator.calculate(it, this) }
    return ApplicationListItem(
        id = id,
        reference = reference,
        visaTypeName = visaTypeName,
        status = report?.overall ?: status,
        createdAt = createdAt,
        requiredTotal = report?.requiredTotal ?: 0,
        requiredUploaded = report?.requiredUploaded ?: 0,
        isActive = id == activeId,
        nextAction = nextActionText(this),
    )
}

private fun nextActionText(app: Application): String? = when {
    app.handoff?.awaitingConsent == true -> "Confirm document sharing"
    app.appointment?.needsResponse == true -> "Respond to appointment proposal"
    app.agencyComments.any { !it.resolved } -> "Fix the requested correction"
    else -> null
}

@Composable
fun MyApplicationsScreen(
    onOpenApplication: (String) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyApplicationsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(
            title = "My applications",
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            },
        )

        when {
            state.isLoading -> com.tripguard.app.ui.components.LoadingState(message = "Loading applications…")

            state.items.isEmpty() -> EmptyStateView(
                icon = Icons.Outlined.Description,
                title = "No applications yet",
                description = "Start a Dubai tourist visa application and TripGuard will build your checklist.",
                action = {
                    PrimaryButton(
                        text = "Start an application",
                        onClick = onApply,
                        icon = Icons.Outlined.Add,
                        modifier = Modifier.width(260.dp),
                    )
                },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.syncMessage?.let { message ->
                    item { InfoBanner(text = message, containerColor = SoftBlueBg, contentColor = SoftBlueFg) }
                }

                items(state.items, key = { it.id }) { item ->
                    ApplicationCard(item) { onOpenApplication(item.id) }
                }

                item {
                    Spacer(Modifier.height(Spacing.xs))
                    PrimaryButton(
                        text = "Start a new application",
                        onClick = onApply,
                        icon = Icons.Outlined.Add,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationCard(item: ApplicationListItem, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (item.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.visaTypeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Navy,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${item.reference} · ${formatDate(item.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                ApplicationStatusChip(item.status)
            }

            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "${item.requiredUploaded} of ${item.requiredTotal} required documents prepared",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = {
                    if (item.requiredTotal == 0) 0f else item.requiredUploaded.toFloat() / item.requiredTotal
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            item.nextAction?.let { action ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "Next: $action",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
