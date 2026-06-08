package com.pradomo.map

/**
 * One sampled point of the mower's track (map frame). [cmdLinear]/[cmdAngular] are
 * what we were commanding at the time — kept for the future traction map (commanded
 * vs actual motion). [tMillis] is stamped on receipt (the mower sends no timestamp).
 */
data class MapSample(
    val tMillis: Long,
    val x: Float,
    val y: Float,
    val heading: Float,
    val bladeOn: Boolean,
    val cmdLinear: Float,
    val cmdAngular: Float,
)
