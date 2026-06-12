package com.pradomo.control

/**
 * What the controller's two buttons do right now. The user cycles between groups by
 * holding both buttons and pushing the stick left/right.
 *
 * - [SPEED]: the buttons are momentary speed overrides (Slow / Turbo) — the original
 *   behavior; the on-screen chips still pick the base speed.
 * - [AUTO]: the big button triggers semi-autonomous maneuvers — stick left/right starts
 *   a K-turn into the adjacent row, stick forward/back starts cruise control.
 */
enum class ControllerModeGroup(val label: String) {
    SPEED("Speed"),
    AUTO("Auto");

    fun next(): ControllerModeGroup = entries[(ordinal + 1) % entries.size]
    fun prev(): ControllerModeGroup = entries[(ordinal - 1 + entries.size) % entries.size]
}
