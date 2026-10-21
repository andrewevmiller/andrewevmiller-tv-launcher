package com.andrewevmiller.tvlauncher.home.apps

/** What an OK/Enter key event means once the press is interpreted. */
enum class PressAction { NONE, TAP, LONG_PRESS }

/**
 * Turns the raw DPAD_CENTER / ENTER key events of one press into a single tap or a single long press.
 * No Android or Compose types: the Compose layer passes in `repeatCount` and `isLongPress` from the key event.
 *
 * The press is tracked from its first key-down (repeat count 0). Android starts auto-repeat after the key
 * repeat timeout (about 500 ms) and marks the first repeat as a long press, so:
 * - down(0) then up, no repeat in between: [PressAction.TAP] on release;
 * - down(0), then a repeat (count > 0, or flagged long press): [PressAction.LONG_PRESS] once, and the release
 *   that follows is [PressAction.NONE], so holding OK never also launches the app;
 * - repeats whose first key-down was not seen (focus arrived mid-press) are ignored.
 */
class PressTracker {
    private var down = false
    private var fired = false

    fun onDown(repeatCount: Int, isLongPress: Boolean): PressAction {
        if (repeatCount == 0) {
            down = true
            fired = false
        }
        if (!down || fired) return PressAction.NONE
        if (repeatCount > 0 || isLongPress) {
            fired = true
            return PressAction.LONG_PRESS
        }
        return PressAction.NONE
    }

    fun onUp(): PressAction {
        val tap = down && !fired
        down = false
        fired = false
        return if (tap) PressAction.TAP else PressAction.NONE
    }

    /** Forget the press in progress (an arrow key or a focus change interrupted it). */
    fun cancel() {
        down = false
        fired = false
    }
}
