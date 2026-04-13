package com.papi.nova.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.preference.PreferenceManager

/**
 * Gyroscope-based aiming — maps device angular velocity to mouse movement.
 * Inspired by Nintendo Switch gyro aiming and Moonlight V+'s implementation.
 *
 * When enabled, tilting the device sends relative mouse deltas to the host,
 * allowing gyro-assisted camera control in FPS games.
 */
class GyroAimController(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var gyroscope: Sensor? = null
    private var enabled = false
    private var sensitivity = 1.0f
    private var invertY = false

    // Callback to send mouse deltas
    var onMouseDelta: ((dx: Int, dy: Int) -> Unit)? = null

    // Dead zone to filter noise (rad/s)
    private val DEAD_ZONE = 0.02f

    fun start() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        enabled = prefs.getBoolean("nova_gyro_aim", false)
        if (!enabled) return

        sensitivity = prefs.getInt("nova_gyro_sensitivity", 100) / 100.0f
        invertY = prefs.getBoolean("nova_gyro_invert_y", false)

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroscope != null) {
            sensorManager?.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        gyroscope = null
    }

    fun toggle() {
        if (enabled) {
            stop()
            enabled = false
        } else {
            enabled = true
            start()
        }
    }

    val isActive get() = enabled && gyroscope != null

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled || event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        // Gyroscope values: angular speed in rad/s around x, y, z axes
        val yaw = event.values[2]    // rotation around Z → horizontal mouse movement
        val pitch = event.values[0]  // rotation around X → vertical mouse movement

        // Apply dead zone
        val adjYaw = if (kotlin.math.abs(yaw) > DEAD_ZONE) yaw else 0f
        val adjPitch = if (kotlin.math.abs(pitch) > DEAD_ZONE) pitch else 0f

        if (adjYaw == 0f && adjPitch == 0f) return

        // Convert angular velocity to pixel delta
        // Scale: 1 rad/s ≈ 400 pixels at sensitivity 1.0
        val scale = 400.0f * sensitivity
        val dx = (adjYaw * scale).toInt()
        val dy = ((if (invertY) adjPitch else -adjPitch) * scale).toInt()

        if (dx != 0 || dy != 0) {
            onMouseDelta?.invoke(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
