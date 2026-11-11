package com.hermes.studio

import android.app.Application
import com.hermes.studio.update.AppUpdateManager

class HermesStudioApp : Application(), AppUpdateManager.UpdateManagerProvider {
    lateinit var updateManager: AppUpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        updateManager = AppUpdateManager(applicationContext)
        updateManager.ensureBackgroundChecksConfigured()
    }

    override fun appUpdateManager(): AppUpdateManager = updateManager
}
