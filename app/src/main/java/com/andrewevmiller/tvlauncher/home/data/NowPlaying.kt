package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Exists only so Android will let the launcher read other apps' media sessions: that permission is
 * granted to notification listeners. It never reads a notification. Enable it once with
 * `tools/now-playing.ps1 enable` (the TV has no screen for it).
 */
class NowPlayingListener : NotificationListenerService()

/** What some app is playing right now, for the featured band. */
@Immutable
data class NowPlaying(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val subtitle: String?,
    val art: ImageBitmap?,
    val playing: Boolean,
    val onToggle: () -> Unit,
)

/**
 * Follows the active media sessions and reports the one worth showing: something playing, else
 * something paused. Reports null when there is none, or when access has not been granted.
 */
class NowPlayingMonitor(
    private val context: Context,
    private val onChange: (NowPlaying?) -> Unit,
) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, NowPlayingListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var watched: List<MediaController> = emptyList()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { update(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update(watched)
        override fun onMetadataChanged(metadata: MediaMetadata?) = update(watched)
        override fun onSessionDestroyed() = refresh()
    }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            refresh()
        } catch (e: SecurityException) {
            onChange(null) // notification access not granted yet
        }
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched = emptyList()
    }

    private fun refresh() {
        try {
            update(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            onChange(null)
        }
    }

    private fun update(sessions: List<MediaController>) {
        val own = context.packageName
        val list = sessions.filter { it.packageName != own }
        if (list.map { it.sessionToken } != watched.map { it.sessionToken }) {
            watched.forEach { runCatching { it.unregisterCallback(callback) } }
            list.forEach { it.registerCallback(callback, handler) }
        }
        watched = list
        onChange(pick(list))
    }

    private fun pick(list: List<MediaController>): NowPlaying? {
        fun titled(c: MediaController) = title(c.metadata) != null
        val chosen = list.firstOrNull { titled(it) && isPlaying(it.playbackState) }
            ?: list.firstOrNull { titled(it) && it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: return null
        val meta = chosen.metadata ?: return null
        val playing = isPlaying(chosen.playbackState)
        val art = (meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        return NowPlaying(
            packageName = chosen.packageName,
            appLabel = label(chosen.packageName),
            title = title(meta) ?: return null,
            subtitle = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = art?.asImageBitmap(),
            playing = playing,
            onToggle = { if (playing) chosen.transportControls.pause() else chosen.transportControls.play() },
        )
    }

    private fun isPlaying(s: PlaybackState?) =
        s?.state == PlaybackState.STATE_PLAYING || s?.state == PlaybackState.STATE_BUFFERING

    private fun title(m: MediaMetadata?): String? =
        (m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_TITLE))
            ?.takeIf { it.isNotBlank() }

    private fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
