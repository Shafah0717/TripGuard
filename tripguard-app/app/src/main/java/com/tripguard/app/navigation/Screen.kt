package com.tripguard.app.navigation

import android.net.Uri

/**
 * Every destination in the app. Top-level tabs are Home, My Applications and Profile; the full
 * visa application flow lives inside the app so it is never over-exposed in bottom navigation.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object MyApplications : Screen("applications")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")

    // Visa application flow
    data object VisaSelection : Screen("visa_selection")

    data object ApplicantDetails : Screen("applicant/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "applicant/${Uri.encode(applicationId)}"
    }

    data object Checklist : Screen("checklist/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "checklist/${Uri.encode(applicationId)}"
    }

    data object Upload : Screen("upload/{applicationId}/{documentId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        const val ARG_DOCUMENT_ID = "documentId"
        fun create(applicationId: String, documentId: String) =
            "upload/${Uri.encode(applicationId)}/${Uri.encode(documentId)}"
    }

    data object Extraction : Screen("extraction/{applicationId}/{documentId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        const val ARG_DOCUMENT_ID = "documentId"
        fun create(applicationId: String, documentId: String) =
            "extraction/${Uri.encode(applicationId)}/${Uri.encode(documentId)}"
    }

    data object Readiness : Screen("readiness/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "readiness/${Uri.encode(applicationId)}"
    }

    data object Comparison : Screen("comparison/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "comparison/${Uri.encode(applicationId)}"
    }

    data object ApplicationDetails : Screen("application/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "application/${Uri.encode(applicationId)}"
    }

    data object Correction : Screen("correction/{applicationId}/{documentId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        const val ARG_DOCUMENT_ID = "documentId"
        fun create(applicationId: String, documentId: String) =
            "correction/${Uri.encode(applicationId)}/${Uri.encode(documentId)}"
    }

    data object Sharing : Screen("sharing/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "sharing/${Uri.encode(applicationId)}"
    }

    data object Appointment : Screen("appointment/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "appointment/${Uri.encode(applicationId)}"
    }

    /** Agency-assistance flow: contact agency after local preparation. */
    data object ContactAgency : Screen("contact_agency/{applicationId}") {
        const val ARG_APPLICATION_ID = "applicationId"
        fun create(applicationId: String) = "contact_agency/${Uri.encode(applicationId)}"
    }

    /**
     * Tracker for an existing agency inquiry when there is no active
     * application to attach it to (e.g. after the application was removed).
     * Renders the same screen as [ContactAgency].
     */
    data object AgencyTracker : Screen("agency_tracker")
}
