package com.overlaypin.app

import android.app.Application

/** Runs once per process start — before any activity/service. Ensures
 *  Prefs are migrated to the current schema regardless of entry point. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.migrate(this)
    }
}
