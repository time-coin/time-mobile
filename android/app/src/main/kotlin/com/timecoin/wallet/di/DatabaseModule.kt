package com.timecoin.wallet.di

import android.content.Context
import androidx.room.Room
import com.timecoin.wallet.db.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WalletDatabase {
        return Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideContactDao(db: WalletDatabase): ContactDao = db.contactDao()
    @Provides fun provideTransactionDao(db: WalletDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideSettingDao(db: WalletDatabase): SettingDao = db.settingDao()
}
