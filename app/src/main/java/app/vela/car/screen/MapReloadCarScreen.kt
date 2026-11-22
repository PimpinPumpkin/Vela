package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vela.car.CarMapRenderer

/**
 * A moment without a map, so the host builds a new map surface: the old one can stay connected
 * to a Vela process that died ([CarMapRenderer.onSurfaceStuck]), and no call on the surface
 * itself frees it. A list, not a message: the host keeps the map surface alive behind a message
 * screen and drops it for a list (Desktop Head Unit, 2026-11-20). Goes back [AFTER_GONE_MS] after
 * the surface is dropped, or after [MAX_MS] at the latest.
 */
class MapReloadCarScreen(carContext: CarContext, private val renderer: CarMapRenderer) :
    Screen(carContext), DefaultLifecycleObserver {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val back = Runnable { finish() }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        renderer.onSurfaceGone = {
            handler.removeCallbacks(back)
            handler.postDelayed(back, AFTER_GONE_MS)
        }
        handler.postDelayed(back, MAX_MS)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(back)
        renderer.onSurfaceGone = null
    }

    override fun onGetTemplate(): Template =
        ListTemplate.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_map_reloading))
            .setLoading(true)
            .build()

    private companion object {
        const val AFTER_GONE_MS = 300L
        const val MAX_MS = 3_000L
    }
}
