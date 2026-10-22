package com.andrewevmiller.tvlauncher.home.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android only lets the launcher read other apps' media sessions (Now playing) and notifications
 * (the notifications panel) if it is a notification listener. Enable it once with
 * `tools/now-playing.ps1 enable` (the TV has no screen for it).
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        /** The live listener, for the notifications panel; null until access is granted and bound. */
        @Volatile
        var instance: NowPlayingListener? = null
    }
}

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

/** Sizing for album art. The band draws it at 300x108dp, which is 600x216 px on the TV, cropped to fill. */
internal object NowPlayingArt {
    const val BOX_W = 600
    const val BOX_H = 216

    /** The smallest size that still covers the box, never larger than the source (art is not upscaled). */
    fun coverSize(width: Int, height: Int, boxW: Int = BOX_W, boxH: Int = BOX_H): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val scale = maxOf(boxW.toFloat() / width, boxH.toFloat() / height)
        if (scale >= 1f) return width to height
        return maxOf(boxW, Math.round(width * scale)) to maxOf(boxH, Math.round(height * scale))
    }
}

/**
 * Follows the active media sessions and reports the one worth showing: something playing, else
 * something paused. Reports null when there is none, or when access has not been granted.
 *
 * All of the work (binder calls to the media session service, pulling album art out of the session,
 * looking up app labels) happens on its own background thread, so returning to Home never waits on
 * it. Art is extracted once per track and scaled to what the band can show, and a change is only
 * reported when something the band displays actually changed. [onChange] is called on the main thread.
 */
class NowPlayingMonitor(
    private val context: Context,
    private val onChange: (NowPlaying?) -> Unit,
) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, NowPlayingListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("now-playing", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false

    // Touched only on [thread].
    private var watched: List<MediaController> = emptyList()
    private var lastKey: Key? = null
    private var hasPublished = false
    private var artFor: Pair<ArtKey, ImageBitmap?>? = null
    private val labels = HashMap<String, String>()

    /** What the band shows apart from the art itself; equal keys mean there is nothing new to draw. */
    private data class Key(
        val packageName: String, val token: MediaSession.Token, val title: String,
        val subtitle: String?, val playing: Boolean, val hasArt: Boolean,
    )

    private data class ArtKey(val packageName: String, val title: String, val subtitle: String?, val hasArt: Boolean)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        guarded { update(sessions.orEmpty()) }
    }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = guarded { update(watched) }
        override fun onMetadataChanged(metadata: MediaMetadata?) = guarded { update(watched) }
        override fun onSessionDestroyed() = guarded { refresh() }
    }

    fun start() {
        running = true
        handler.post { guarded { startListening() } }
    }

    fun stop() {
        running = false
        handler.post { guarded { stopListening() } }
    }

    /** Stops for good and ends the background thread. */
    fun close() {
        running = false
        handler.post {
            guarded { stopListening() }
            thread.quitSafely()
        }
    }

    private fun startListening() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            refresh()
        } catch (e: SecurityException) {
            publish(null, null) // notification access not granted yet
        }
    }

    private fun stopListening() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched = emptyList()
        hasPublished = false // the next start reports afresh, even if nothing changed meanwhile
    }

    private fun refresh() {
        try {
            update(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            publish(null, null)
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
        val picked = pick(list)
        publish(picked?.first, picked?.second)
    }

    private fun publish(value: NowPlaying?, key: Key?) {
        if (hasPublished && key == lastKey) return
        hasPublished = true
        lastKey = key
        main.post { if (running) onChange(value) }
    }

    private fun pick(list: List<MediaController>): Pair<NowPlaying, Key>? {
        fun titled(c: MediaController) = title(c.metadata) != null
        val chosen = list.firstOrNull { titled(it) && isPlaying(it.playbackState) }
            ?: list.firstOrNull { titled(it) && it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: return null
        val meta = chosen.metadata ?: return null
        val title = title(meta) ?: return null
        val playing = isPlaying(chosen.playbackState)
        val subtitle = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val art = art(meta, chosen.packageName, title, subtitle)
        val key = Key(chosen.packageName, chosen.sessionToken, title, subtitle, playing, art != null)
        val now = NowPlaying(
            packageName = chosen.packageName,
            appLabel = label(chosen.packageName),
            title = title,
            subtitle = subtitle,
            art = art,
            playing = playing,
            onToggle = { if (playing) chosen.transportControls.pause() else chosen.transportControls.play() },
        )
        return now to key
    }

    /** Pulls the art out of the session once per track (it is a large copy), scaled to what the band shows. */
    private fun art(meta: MediaMetadata, pkg: String, title: String, subtitle: String?): ImageBitmap? {
        val has = ART_KEYS.any { meta.containsKey(it) }
        val key = ArtKey(pkg, title, subtitle, has)
        artFor?.let { if (it.first == key) return it.second }
        val bitmap = if (has) ART_KEYS.firstNotNullOfOrNull { meta.getBitmap(it) }?.let { fit(it) } else null
        artFor = key to bitmap
        return bitmap
    }

    private fun fit(source: Bitmap): ImageBitmap {
        val (w, h) = NowPlayingArt.coverSize(source.width, source.height)
        val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        // Hardware bitmaps live in GPU memory, so nothing is uploaded on the first frame that draws them.
        val gpu = runCatching { scaled.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
            ?.takeIf { it.config == Bitmap.Config.HARDWARE }
        return (gpu ?: scaled).asImageBitmap()
    }

    private fun isPlaying(s: PlaybackState?) =
        s?.state == PlaybackState.STATE_PLAYING || s?.state == PlaybackState.STATE_BUFFERING

    private fun title(m: MediaMetadata?): String? =
        (m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_TITLE))
            ?.takeIf { it.isNotBlank() }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /**
     * An exception on this thread would kill the whole app, and a dead or misbehaving media session
     * must never do that. Log and carry on.
     */
    private inline fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            Log.w(TAG, "now playing update failed", e)
        }
    }

    private companion object {
        const val TAG = "NowPlaying"
        val ART_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
    }
}
