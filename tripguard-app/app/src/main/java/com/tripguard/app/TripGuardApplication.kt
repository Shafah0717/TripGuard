package com.tripguard.app

import android.app.Application
import com.tripguard.app.di.AppContainer

class TripGuardApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
