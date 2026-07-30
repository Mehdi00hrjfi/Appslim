package com.appslim.analyzer

import android.app.Application
import com.appslim.analyzer.data.db.AppDatabase
import com.appslim.analyzer.util.CrashLogger

class AppSlimApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLogger.install(this)
    }

    companion object {
        lateinit var instance: AppSlimApp
            private set
    }
}
