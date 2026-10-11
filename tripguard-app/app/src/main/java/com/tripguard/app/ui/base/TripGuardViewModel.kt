package com.tripguard.app.ui.base

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tripguard.app.TripGuardApplication
import com.tripguard.app.di.AppContainer

/** Gives every screen ViewModel typed access to the app's dependency graph. */
abstract class TripGuardViewModel(application: Application) : AndroidViewModel(application) {
    protected val container: AppContainer
        get() = getApplication<TripGuardApplication>().container
}
