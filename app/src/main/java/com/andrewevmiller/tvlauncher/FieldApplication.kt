package com.andrewevmiller.tvlauncher

import android.app.Application
import com.andrewevmiller.tvlauncher.safety.CrashGuard

/** Installs the crash-loop guard before any activity starts and owns the singletons. Registered in the manifest. */
class FieldApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        container.installDreamHooks()
    }
}
