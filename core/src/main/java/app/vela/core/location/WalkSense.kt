package app.vela.core.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Steps from the accelerometer ([StepDetector]) as a flow, for the dot's FreeFused ([DotFix]).
 * The sensor runs at game rate (about 50 Hz) only while the flow is collected, which the view
 * model does only while the map shows with no drive on. No permission: the plain accelerometer.
 * Times are the sensor's own, on the boot clock that GPS fixes carry (elapsedRealtimeNanos).
 */
object WalkSense {
    fun steps(context: Context): Flow<StepDetector.Step> = callbackFlow {
        val sm = context.applicationContext.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) { close(); return@callbackFlow }
        val detector = StepDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                detector.sample(e.timestamp / 1e9, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                    ?.let { trySend(it) }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}
