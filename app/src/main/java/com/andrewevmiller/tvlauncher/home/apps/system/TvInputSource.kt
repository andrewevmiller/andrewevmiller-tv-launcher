package com.andrewevmiller.tvlauncher.home.apps.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Reads the TV's inputs from `TvInputManager` into pure [InputDescriptor]s and keeps them fresh.
 *
 * [inputs] / [tiles] emit once on collection and again whenever the framework reports an input added,
 * removed or updated. Collect them lifecycle-aware (the row uses `collectAsStateWithLifecycle`), so each
 * time the launcher comes back to the front the list, including custom labels the user changed in TV
 * settings meanwhile, is re-read.
 *
 * Several `TvInputInfo` getters are `@SystemApi` / `@hide` and so are missing from the public SDK that
 * this app compiles against: `getParentId()`, `getHdmiDeviceInfo()` (and its `getPortId()`), and
 * possibly `isHidden(Context)`. They are read by reflection and degrade to "unknown" when blocked, with
 * fallbacks in the pure helpers ([looksLikeCecChildId], [parseHdmiPort]). Every decision is logged under
 * [SYSTEM_TILES_LOG_TAG] so the owner can see what this TV actually reports.
 */
class TvInputSource(context: Context) {

    private val appContext: Context = context.applicationContext
    private val manager: TvInputManager? = appContext.getSystemService(TvInputManager::class.java)

    /** One snapshot of the inputs right now (no callbacks). Empty when the TV has no input framework. */
    fun readNow(): List<InputDescriptor> {
        val mgr = manager ?: return emptyList()
        return read(mgr)
    }

    /** The inputs now, then again after every add / remove / update. Distinct values only. */
    fun inputs(): Flow<List<InputDescriptor>> = callbackFlow<List<InputDescriptor>> {
        val mgr = manager
        if (mgr == null) {
            Log.w(SYSTEM_TILES_LOG_TAG, "TvInputManager unavailable; the row will show Wi-Fi, Search and Settings only")
            trySend(emptyList())
            awaitClose { }
        } else {
            val callback = object : TvInputManager.TvInputCallback() {
                override fun onInputAdded(inputId: String) {
                    Log.i(SYSTEM_TILES_LOG_TAG, "onInputAdded $inputId")
                    trySend(read(mgr))
                }

                override fun onInputRemoved(inputId: String) {
                    Log.i(SYSTEM_TILES_LOG_TAG, "onInputRemoved $inputId")
                    trySend(read(mgr))
                }

                override fun onInputUpdated(inputId: String) {
                    Log.i(SYSTEM_TILES_LOG_TAG, "onInputUpdated $inputId")
                    trySend(read(mgr))
                }

                override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) {
                    Log.i(SYSTEM_TILES_LOG_TAG, "onTvInputInfoUpdated ${inputInfo.id}")
                    trySend(read(mgr))
                }
            }
            mgr.registerCallback(callback, Handler(Looper.getMainLooper()))
            trySend(read(mgr))
            awaitClose { mgr.unregisterCallback(callback) }
        }
    }.distinctUntilChanged()

    /** The finished row, rebuilt whenever [inputs] changes; logs which inputs got a tile and why not. */
    fun tiles(): Flow<List<SystemTile>> = inputs()
        .onEach { list -> describeSelection(selectPhysicalInputs(list)).forEach { Log.i(SYSTEM_TILES_LOG_TAG, it) } }
        .map { buildSystemTiles(it) }
        .distinctUntilChanged()

    // ---- reading -----------------------------------------------------------------------------

    private fun read(mgr: TvInputManager): List<InputDescriptor> {
        val infos: List<TvInputInfo> = try {
            mgr.tvInputList
        } catch (e: Exception) {
            Log.w(SYSTEM_TILES_LOG_TAG, "getTvInputList failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        return infos.mapNotNull { info ->
            try {
                describe(info)
            } catch (e: Exception) {
                Log.w(SYSTEM_TILES_LOG_TAG, "could not read input ${info.id}: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun describe(info: TvInputInfo): InputDescriptor {
        val id = info.id
        val type = info.type
        val customLabel = info.loadCustomLabel(appContext)?.toString()
        val systemLabel = info.loadLabel(appContext)?.toString()

        val reflectedParent = callNoArg(info, "getParentId") as? String
        val reflectedHidden = callWithContext(info, "isHidden")
        val frameworkPort = if (type == TvInputType.HDMI) {
            callNoArg(callNoArg(info, "getHdmiDeviceInfo"), "getPortId") as? Int
        } else {
            null
        }

        val port = if (type == TvInputType.HDMI) resolveHdmiPort(frameworkPort, systemLabel) else null
        val hasParent = reflectedParent != null || looksLikeCecChildId(id)
        val flags = info.serviceInfo?.applicationInfo?.flags ?: 0
        val fromSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0

        Log.i(
            SYSTEM_TILES_LOG_TAG,
            "read input $id: framework parentId=$reflectedParent isHidden=$reflectedHidden hdmiPortId=$frameworkPort " +
                "-> port=$port hasParent=$hasParent",
        )
        return InputDescriptor(
            id = id,
            type = type,
            customLabel = customLabel,
            hdmiPort = port,
            hasParent = hasParent,
            hidden = reflectedHidden ?: false,
            fromSystemApp = fromSystemApp,
            systemLabel = systemLabel,
        )
    }

    /** Calls a public no-argument getter by name; null if absent, blocked, or it returns null. */
    private fun callNoArg(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            target.javaClass.getMethod(name).invoke(target)
        } catch (e: Exception) {
            null
        }
    }

    /** Calls `name(Context): Boolean` on [info] by reflection; null if absent or blocked. */
    private fun callWithContext(info: TvInputInfo, name: String): Boolean? = try {
        info.javaClass.getMethod(name, Context::class.java).invoke(info, appContext) as? Boolean
    } catch (e: Exception) {
        null
    }
}
