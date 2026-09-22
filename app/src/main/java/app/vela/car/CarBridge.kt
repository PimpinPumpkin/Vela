package app.vela.car

import app.vela.core.data.TrafficControl
import app.vela.core.model.LatLng
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the phone-side nav controller knows and the car screens should show, without the car
 * reaching into the view model: spoken alerts as one-line toasts, and the corridor furniture
 * (lights, stop signs, speed cameras) the nav map already draws. A drive started from the car
 * with the phone UI closed has no controller and gets none of this, which is the same as before.
 */
object CarBridge {
    /** One-line messages for a [androidx.car.app.CarToast]: the camera, speeding and closing-soon
     *  alerts, which were spoken only (a muted car heard nothing). */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun toast(message: String) { toasts.tryEmit(message) }

    /** Lights and stop signs along the driven route. */
    val controls = MutableStateFlow<List<TrafficControl>>(emptyList())
    /** Fixed speed cameras along the driven route. */
    val speedCameras = MutableStateFlow<List<LatLng>>(emptyList())

    fun clear() { controls.value = emptyList(); speedCameras.value = emptyList() }
}
