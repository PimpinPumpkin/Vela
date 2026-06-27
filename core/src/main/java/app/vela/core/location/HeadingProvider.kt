package app.vela.core.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device compass heading (degrees, 0 = N, clockwise) from the fused `TYPE_ROTATION_VECTOR`
 * sensor — the "which way am I facing" used to point the **browse-mode heading cone** when
 * you're standing still, where the GPS bearing is noise (a stopped phone has no course).
 * OsmAnd does exactly this (sensors for heading, low-pass/Kalman smoothed); navigation never
 * uses it — there the heading comes from the matched road. Degoogled: raw [SensorManager],
 * no Play Services, no fused provider.
 */
@Singleton
class HeadingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val sensorManager = context.getSystemService<SensorManager>()
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = rotationSensor != null

    /** Smoothed device azimuth in degrees `[0,360)`. Emits while collected; unregisters on close. */
    fun headings(): Flow<Float> = callbackFlow {
        val sm = sensorManager
        val sensor = rotationSensor
        if (sm == null || sensor == null) { close(); return@callbackFlow }
        @Suppress("DEPRECATION")
        val display = context.getSystemService<WindowManager>()?.defaultDisplay
        val rot = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                // Remap so the azimuth is relative to the SCREEN's "up", whatever way the device
                // is rotated (portrait/landscape), then read the azimuth out of the orientation.
                val (ax, ay) = when (display?.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
                SensorManager.getOrientation(remapped, orientation)
                var az = Math.toDegrees(orientation[0].toDouble()).toFloat()
                az = (az + 360f) % 360f
                // Low-pass along the SHORTEST arc (OsmAnd-style) so the cone glides, not jitters.
                smoothed = if (smoothed.isNaN()) {
                    az
                } else {
                    val d = ((az - smoothed + 540f) % 360f) - 180f
                    (smoothed + d * 0.12f + 360f) % 360f
                }
                trySend(smoothed)
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }
}
