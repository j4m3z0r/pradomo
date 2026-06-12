package com.pradomo.control.maneuver

/**
 * How the mower reverses direction into the adjacent row.
 *
 * - [K_TURN]: back-up-first multi-point turn. Needs no space past the row end (it works
 *   the already-mowed row behind), but includes reversing legs.
 * - [U_TURN]: forward-only. A plain semicircle when the row pitch allows, otherwise a
 *   teardrop (counter-flick away, then one big loop). Faster and never reverses, but it
 *   sweeps past the row end and wide of the new row — needs clear headland.
 */
enum class TurnStyle { K_TURN, U_TURN }
