package com.pradomo.sim

import com.pradomo.control.maneuver.Pose
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Higher-fidelity differential-drive "truth" model for the simulator — the real mower the
 * controller can't see directly. From a normalised (drive, turn): per-tread commanded
 * speeds, independent per-side grip (slip), a systematic tread-speed imbalance, a global
 * turn-rate model error, slope-induced downhill drift + yaw bias, and process noise.
 */
class MowerPlant(
    private val env: Env,
    private val linearScale: Float,
    private val turnScale: Float,
    private val trackWidth: Float = 0.4f,
    seed: Long = 1L,
) {
    data class Env(
        val gripLeft: Float = 1f,     // 1 = full grip, <1 = that tread slips
        val gripRight: Float = 1f,
        val treadImbalance: Float = 0f, // systematic vL = ·(1-i), vR = ·(1+i)
        val turnRateError: Float = 1f,  // ω actually achieved vs commanded
        val slopeDriftX: Float = 0f,    // downhill world-frame slide (m/s)
        val slopeDriftY: Float = 0f,
        val slopeYaw: Float = 0f,       // slope-induced yaw bias (rad/s)
        val processNoise: Float = 0f,   // stddev of per-step pose noise (m, rad scaled)
        val actuationTauS: Float = 0.2f, // first-order lag: commanded v/ω aren't achieved instantly
    )

    private val rng = Random(seed)
    var x = 0f; private set
    var y = 0f; private set
    var heading = 0f; private set

    /** Last realised per-tread speeds (m/s) — used by the scrub/gentleness metric. */
    var lastVL = 0f; private set
    var lastVR = 0f; private set

    // Actuation lag state: the body velocities actually achieved so far.
    private var achV = 0f
    private var achW = 0f

    fun setPose(p: Pose) { x = p.x; y = p.y; heading = p.heading }
    fun pose() = Pose(x, y, heading)

    fun step(drive: Float, turn: Float, dt: Float) {
        // First-order actuation lag toward the commanded body velocities.
        val a = dt / (env.actuationTauS + dt)
        achV += a * (drive * linearScale - achV)
        achW += a * (turn * turnScale - achW)
        val v = achV
        val w = achW
        // Commanded per-tread, with systematic imbalance.
        val vLc = (v - w * trackWidth / 2f) * (1f - env.treadImbalance)
        val vRc = (v + w * trackWidth / 2f) * (1f + env.treadImbalance)
        // Grip/slip per side.
        val vL = vLc * env.gripLeft
        val vR = vRc * env.gripRight
        lastVL = vL; lastVR = vR
        // Reconstruct body motion.
        val vBody = (vL + vR) / 2f
        val wBody = (vR - vL) / trackWidth * env.turnRateError
        heading += wBody * dt + env.slopeYaw * dt + noise() * 0.3f
        x += vBody * cos(heading) * dt + env.slopeDriftX * dt + noise()
        y += vBody * sin(heading) * dt + env.slopeDriftY * dt + noise()
    }

    /** Magnitude of the larger tread speed at full commanded turn (for the scrub metric). */
    fun maxTreadSpeed() = linearScale + turnScale * trackWidth / 2f

    private fun noise() = if (env.processNoise > 0f) (rng.nextFloat() * 2f - 1f) * env.processNoise else 0f
}
