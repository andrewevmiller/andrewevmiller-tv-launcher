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
        // Alarms do not survive a reboot or a force-stop. The launcher is Home, so it starts at boot; building the
        // sleep timer here re-arms a stored deadline that is still in the future (and drops a stale one).
        container.sleepTimer
    }
}
