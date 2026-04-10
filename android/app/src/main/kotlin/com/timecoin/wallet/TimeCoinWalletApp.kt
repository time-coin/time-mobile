package com.timecoin.wallet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TimeCoinWalletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSACTIONS,
                "TIME Received",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Sound alert when TIME coins are received"
            }
        )
    }

    companion object {
        const val CHANNEL_TRANSACTIONS = "transactions_v2"
    }
}
