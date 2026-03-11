package com.timecoin.wallet

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TimeCoinWalletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize app components
    }
}
