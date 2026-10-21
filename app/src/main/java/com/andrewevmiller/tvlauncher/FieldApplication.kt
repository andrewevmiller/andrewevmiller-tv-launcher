package com.andrewevmiller.tvlauncher

import android.app.Application
import com.andrewevmiller.tvlauncher.safety.CrashGuard

/** Installs the crash-loop guard before any activity starts. Registered in the manifest. */
class FieldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
