package com.andrewevmiller.tvlauncher.sleep

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.andrewevmiller.tvlauncher.ambient.ScreensaverController
import com.andrewevmiller.tvlauncher.ambient.SleepDimRequest
import com.andrewevmiller.tvlauncher.ambient.SystemScreensaverController
import com.andrewevmiller.tvlauncher.settings.Screensaver
import com.andrewevmiller.tvlauncher.sound.FieldSound

/**
 * The platform side of the end sequence: the thin glue under [SleepEndRunner]. Every decision (order, when to stop, what
 * a failure means) is made in the pure files; this class only knows how to send each named rung and strategy.
 *
 * Every method is called on the receiver's worker thread, one at a time. Nothing here is verified without the TV: the
 * media key from a background receiver, audio focus from the background and the background activity start are exactly
 * what spike E3 is for, which is why each one logs what it did.
 *
 * [sound] and [screensaver] are providers, because the sound engine is expensive to build and is only wanted when the
 * timer really fires.
 */
class AndroidSleepEndActions(
    private val context: Context,
    private val sound: () -> FieldSound,
    private val screensaver: () -> ScreensaverController,
) : SleepEndActions {
    private val audio: AudioManager by lazy { context.getSystemService(AudioManager::class.java) }

    /** The focus request [perform] is holding, until [release]. */
    private var heldFocus: AudioFocusRequest? = null

    // ---- sequence ------------------------------------------------------------------------------------------

    override fun warmUp() {
        // Building the engine starts rendering the sound set in the background. In a process that was started for the
        // alarm that takes a while, so it begins before the pause ladder rather than at the closing sound.
        sound()
    }

    override fun playClosingSound() {
        sound().windDown()
    }

    override fun sleep(millis: Long) {
        Thread.sleep(millis)
    }

    // ---- pause ---------------------------------------------------------------------------------------------

    override fun isPlaying(): Boolean = audio.isMusicActive

    override fun perform(rung: PauseRung) {
        when (rung.name) {
            PauseRungs.MEDIA_KEY, PauseRungs.MEDIA_KEY_RETRY -> sendPauseKey()
            PauseRungs.AUDIO_FOCUS -> holdAudioFocus()
            else -> throw IllegalArgumentException("unknown pause rung '${rung.name}'")
        }
    }

    override fun release() {
        val request = heldFocus ?: return
        heldFocus = null
        val result = audio.abandonAudioFocusRequest(request)
        Log.i(SleepConfig.TAG, "audio focus abandoned (result $result)")
    }

    /** KEYCODE_MEDIA_PAUSE, down then up, the way a remote's pause button arrives. */
    private fun sendPauseKey() {
        val down = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        audio.dispatchMediaKeyEvent(
            KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
        )
    }

    /**
     * Takes permanent audio focus so a well-behaved player pauses on AUDIOFOCUS_LOSS, and keeps it for
     * [SleepConfig.FOCUS_EXTRA_HOLD_MILLIS] before returning. The ladder then settles for its own 1.5 s and checks while
     * the focus is still held; [release] gives it back afterwards.
     */
    private fun holdAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> Log.i(SleepConfig.TAG, "our audio focus changed: $change") }
            .build()
        val result = audio.requestAudioFocus(request)
        Log.i(SleepConfig.TAG, "requestAudioFocus -> ${focusResultName(result)}")
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        heldFocus = request
        Thread.sleep(SleepConfig.FOCUS_EXTRA_HOLD_MILLIS)
    }

    private fun focusResultName(code: Int): String = when (code) {
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "AUDIOFOCUS_REQUEST_FAILED"
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "AUDIOFOCUS_REQUEST_GRANTED"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "AUDIOFOCUS_REQUEST_DELAYED"
        else -> "code $code"
    }

    // ---- dim: own activity ---------------------------------------------------------------------------------

    override fun startDimActivity() {
        SleepDimHandshake.request(SystemClock.elapsedRealtime())
        context.startActivity(
            Intent(context, SleepDimActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Log.i(SleepConfig.TAG, "startActivity(SleepDimActivity, NEW_TASK) returned without an exception")
    }

    override fun dimActivityShown(): Boolean = SleepDimHandshake.isShown()

    override fun abandonDimActivity() {
        SleepDimHandshake.abandon()
    }

    // ---- dim: screensaver ----------------------------------------------------------------------------------

    override fun oxyfieldIsActiveScreensaver(): Boolean {
        val controller = screensaver() as? SystemScreensaverController ?: return false
        return controller.current() == Screensaver.OXYFIELD
    }

    override fun requestDreamDim() {
        SleepDimRequest.request()
    }

    override fun startScreensaver(): Boolean = screensaver().startNow()

    override fun dreamDimConsumed(): Boolean = !SleepDimRequest.isPending()

    override fun clearDreamDim() {
        SleepDimRequest.clear()
    }
}
