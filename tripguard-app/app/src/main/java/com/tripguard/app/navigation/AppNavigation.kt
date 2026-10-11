package com.tripguard.app.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tripguard.app.ui.components.TripGuardBottomNavigation
import com.tripguard.app.ui.screens.applicant.ApplicantDetailsScreen
import com.tripguard.app.ui.screens.application.ApplicationDetailsScreen
import com.tripguard.app.ui.screens.appointment.AppointmentScreen
import com.tripguard.app.ui.screens.applications.MyApplicationsScreen
import com.tripguard.app.ui.screens.agency.ContactAgencyScreen
import com.tripguard.app.ui.screens.checklist.DocumentChecklistScreen
import com.tripguard.app.ui.screens.comparison.ComparisonScreen
import com.tripguard.app.ui.screens.correction.CorrectionScreen
import com.tripguard.app.ui.screens.extraction.ExtractionReviewScreen
import com.tripguard.app.ui.screens.home.HomeScreen
import com.tripguard.app.ui.screens.profile.ProfileScreen
import com.tripguard.app.ui.screens.readiness.ReadinessScreen
import com.tripguard.app.ui.screens.settings.SettingsScreen
import com.tripguard.app.ui.screens.sharing.DocumentSharingScreen
import com.tripguard.app.ui.screens.upload.DocumentUploadScreen
import com.tripguard.app.ui.screens.visa.VisaSelectionScreen

private val TopLevelRoutes = setOf(
    Screen.Home.route,
    Screen.MyApplications.route,
    Screen.Profile.route,
)

/**
 * Root of the TripGuard UI. A single [NavHost] owns every destination. The bottom bar is shown
 * only for the three top-level tabs; the guided visa flow is presented full-screen.
 */
@Composable
fun TripGuardApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TopLevelRoutes

    val selectedTabRoute = when (currentRoute) {
        Screen.MyApplications.route -> Screen.MyApplications.route
        Screen.Profile.route -> Screen.Profile.route
        else -> Screen.Home.route
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                TripGuardBottomNavigation(
                    currentRoute = selectedTabRoute,
                    onTabSelected = { tab -> navController.navigateToTab(tab.route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onApply = { navController.navigateTo(Screen.VisaSelection.route) },
                    onContinueApplication = { id ->
                        navController.navigateTo(Screen.ApplicationDetails.create(id))
                    },
                    onProfileClick = { navController.navigateToTab(Screen.Profile.route) },
                    onOpenSharing = { id -> navController.navigateTo(Screen.Sharing.create(id)) },
                    onOpenAppointment = { id -> navController.navigateTo(Screen.Appointment.create(id)) },
                    onTrackAgency = { id ->
                        if (id.isNullOrBlank()) {
                            navController.navigateTo(Screen.AgencyTracker.route)
                        } else {
                            navController.navigateTo(Screen.ContactAgency.create(id))
                        }
                    },
                )
            }

            composable(Screen.MyApplications.route) {
                MyApplicationsScreen(
                    onOpenApplication = { id -> navController.navigateTo(Screen.ApplicationDetails.create(id)) },
                    onApply = { navController.navigateTo(Screen.VisaSelection.route) },
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(onOpenSettings = { navController.navigateTo(Screen.Settings.route) })
            }

            composable(Screen.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.VisaSelection.route) {
                VisaSelectionScreen(
                    onBack = { navController.popBackStack() },
                    onApplicationCreated = { id ->
                        navController.navigateTo(Screen.ApplicantDetails.create(id))
                    },
                )
            }

            composable(Screen.ApplicantDetails.route) {
                ApplicantDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { id -> navController.navigateTo(Screen.Checklist.create(id)) },
                )
            }

            composable(Screen.Checklist.route) {
                DocumentChecklistScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDocument = { appId, docId ->
                        navController.navigateTo(Screen.Upload.create(appId, docId))
                    },
                    onContinue = { id -> navController.navigateTo(Screen.Readiness.create(id)) },
                )
            }

            composable(Screen.Upload.route) {
                DocumentUploadScreen(
                    onBack = { navController.popBackStack() },
                    onReviewExtraction = { appId, docId ->
                        navController.navigateTo(Screen.Extraction.create(appId, docId))
                    },
                )
            }

            composable(Screen.Extraction.route) {
                ExtractionReviewScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { appId, _ -> navController.backToChecklist(appId) },
                    onOpenUpload = { appId, docId ->
                        navController.navigateTo(Screen.Upload.create(appId, docId))
                    },
                )
            }

            composable(Screen.Readiness.route) {
                ReadinessScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDocument = { appId, docId ->
                        navController.navigateTo(Screen.Upload.create(appId, docId))
                    },
                    onBackToDocuments = { id ->
                        navController.navigateTo(Screen.Checklist.create(id))
                    },
                    onOpenComparison = { id ->
                        navController.navigateTo(Screen.Comparison.create(id))
                    },
                    onContactAgency = { id ->
                        navController.navigateTo(Screen.ContactAgency.create(id))
                    },
                )
            }

            composable(Screen.Comparison.route) {
                ComparisonScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.ApplicationDetails.route) {
                ApplicationDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onEditApplicant = { id -> navController.navigateTo(Screen.ApplicantDetails.create(id)) },
                    onOpenChecklist = { id -> navController.navigateTo(Screen.Checklist.create(id)) },
                    onOpenDocument = { appId, docId ->
                        navController.navigateTo(Screen.Upload.create(appId, docId))
                    },
                    onOpenReadiness = { id -> navController.navigateTo(Screen.Readiness.create(id)) },
                    onOpenCorrection = { appId, docId ->
                        navController.navigateTo(Screen.Correction.create(appId, docId))
                    },
                    onOpenSharing = { id -> navController.navigateTo(Screen.Sharing.create(id)) },
                    onOpenAppointment = { id -> navController.navigateTo(Screen.Appointment.create(id)) },
                    onDeleted = { navController.navigateToTab(Screen.MyApplications.route) },
                )
            }

            composable(Screen.Sharing.route) {
                DocumentSharingScreen(
                    onBack = { navController.popBackStack() },
                    onViewApplication = { id ->
                        navController.navigateTo(Screen.ApplicationDetails.create(id))
                    },
                )
            }

            composable(Screen.Appointment.route) {
                AppointmentScreen(
                    onBack = { navController.popBackStack() },
                    onViewApplication = { id ->
                        navController.navigateTo(Screen.ApplicationDetails.create(id))
                    },
                )
            }

            composable(Screen.ContactAgency.route) {
                ContactAgencyScreen(
                    onBack = { navController.popBackStack() },
                    onBookingConfirmed = { /* dialog handled inside the screen */ },
                )
            }

            composable(Screen.AgencyTracker.route) {
                ContactAgencyScreen(
                    onBack = { navController.popBackStack() },
                    onBookingConfirmed = { /* dialog handled inside the screen */ },
                )
            }

            composable(Screen.Correction.route) {
                CorrectionScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { _ ->
                        if (!navController.popBackStack(Screen.ApplicationDetails.route, false)) {
                            navController.navigateToTab(Screen.MyApplications.route)
                        }
                    },
                )
            }
        }
    }
}

/** Navigates to a screen without stacking duplicates of the same destination. */
private fun NavHostController.navigateTo(route: String) {
    navigate(route) { launchSingleTop = true }
}

/** Returns to the checklist for [applicationId], navigating there if it is not on the stack. */
private fun NavHostController.backToChecklist(applicationId: String) {
    if (!popBackStack(Screen.Checklist.route, inclusive = false)) {
        navigate(Screen.Checklist.create(applicationId)) { launchSingleTop = true }
    }
}

/**
 * Standard bottom-navigation behaviour: a single instance of each tab, state preserved when
 * switching tabs, and no duplicate copies accumulating on the back stack.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
