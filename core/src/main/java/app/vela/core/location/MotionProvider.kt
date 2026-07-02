package app.vela.core.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * World-frame horizontal acceleration — the accelerometer half of the nav puck's speed fusion
 * ([SpeedKalman]): between GPS fixes the puck's modelled speed follows the *measured* forward
 * acceleration, so it brakes and launches WITH the car instead of gliding at the last fix's
 * speed. Fuses `TYPE_LINEAR_ACCELERATION` (gravity already removed — an AOSP virtual sensor,
 * no GMS) with `TYPE_ROTATION_VECTOR` to rotate the device-frame reading into the world frame
 * (x = East, y = North), emitting low-passed `[east, north]` m/s². The projection onto the
 * travel bearing happens at the consumer ([forwardAccel]) where the bearing is known.
 *
 * Degoogled: raw [SensorManager] only, same as [HeadingProvider]. Missing either sensor →
 * [available] false and the flow closes immediately; the Kalman then predicts with `a = 0`,
 * which is exactly the old constant-speed behaviour.
 */
@Singleton
class MotionProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sensorManager = context.getSystemService<SensorManager>()
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = accelSensor != null && rotationSensor != null

    /** Low-passed world-frame horizontal acceleration `[east, north]` (m/s²). Emits while
     *  collected; unregisters on close. The emitted array is REUSED — read, don't retain. */
    fun worldAccel(): Flow<FloatArray> = callbackFlow {
        val sm = sensorManager
        val accel = accelSensor
        val rotation = rotationSensor
        if (sm == null || accel == null || rotation == null) { close(); return@callbackFlow }
        val rot = FloatArray(9)
        var haveRot = false
        val out = FloatArray(2)
        var east = 0f
        var north = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rot, e.values)
                        haveRot = true
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        if (!haveRot) return
                        // Device → world (rows of the rotation matrix are the world axes):
                        // x = East, y = North. The vertical component is dropped — road speed
                        // only cares about the horizontal plane.
                        val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
                        val e0 = rot[0] * ax + rot[1] * ay + rot[2] * az
                        val n0 = rot[3] * ax + rot[4] * ay + rot[5] * az
                        // Low-pass (~0.2 s at the UI sensor rate): braking is a sustained signal,
                        // engine/road vibration is not.
                        east += (e0 - east) * 0.25f
                        north += (n0 - north) * 0.25f
                        out[0] = east
                        out[1] = north
                        trySend(out)
                    }
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }
}
