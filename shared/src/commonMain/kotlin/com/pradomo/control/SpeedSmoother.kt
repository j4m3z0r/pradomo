package com.pradomo.control

/**
 * Controlled deceleration/acceleration. Holds the last-sent drive/turn and, each
 * tick, steps them toward the commanded target by at most [ratePerSec] · dt — so
 * the mower eases into motion and coasts to a stop instead of starting/stopping
 * abruptly (gentler on the turf).
 *
 * Pure and frame-rate independent (driven by the caller's dt). E-STOP / disconnect
 * / backgrounding must call [reset] to bypass the ramp and stop instantly.
 */
class SpeedSmoother(var ratePerSec: Float) {
    var linear = 0f
        private set
    var angular = 0f
        private set

    /** Advance toward the target by ratePerSec·dt and return the new (linear, angular). */
    fun step(targetLinear: Float, targetAngular: Float, dtSeconds: Float): Pair<Float, Float> {
        val maxDelta = ratePerSec * dtSeconds
        linear = approach(linear, targetLinear, maxDelta)
        angular = approach(angular, targetAngular, maxDelta)
        return linear to angular
    }

    /** Jump immediately to the given values (default: full stop). Bypasses the ramp. */
    fun reset(linear: Float = 0f, angular: Float = 0f) {
        this.linear = linear
        this.angular = angular
    }

    companion object {
        fun approach(current: Float, target: Float, maxDelta: Float): Float {
            if (maxDelta <= 0f) return target
            val d = target - current
            return when {
                d > maxDelta -> current + maxDelta
                d < -maxDelta -> current - maxDelta
                else -> target
            }
        }
    }
}
