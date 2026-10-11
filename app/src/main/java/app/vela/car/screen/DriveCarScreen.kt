package app.vela.car.screen

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Drive mode: the map alone, with no route and no list (issue #749). The renderer follows the car
 * heading-up once it moves, as on the drive screen, and draws the speed badge. Search and the
 * saved places are a tap away; a drive started anywhere replaces this screen with the drive
 * screen (`VelaCarSession`).
 */
class DriveCarScreen(carContext: CarContext, private val deps: CarDeps) :
    Screen(carContext), DefaultLifecycleObserver {

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        val renderer = deps.mapRenderer(carContext)
        renderer.follow()
        renderer.setDriveScreen(true)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        deps.mapRenderer(carContext).setDriveScreen(false)
    }

    override fun onGetTemplate(): Template {
        val renderer = deps.mapRenderer(carContext)
        val strip = ActionStrip.Builder()
            .addAction(action(app.vela.R.drawable.ic_car_search) { screenManager.push(SearchCarScreen(carContext, deps)) })
            .addAction(action(app.vela.R.drawable.ic_car_saved) { screenManager.push(SavedCarScreen(carContext, deps)) })
            .addAction(action(app.vela.R.drawable.ic_car_list) { screenManager.pop() })
            .build()
        val mapStrip = ActionStrip.Builder()
            .addAction(action(app.vela.R.drawable.ic_car_recenter) { renderer.follow() })
            .addAction(action(app.vela.R.drawable.ic_car_zoom_in) { renderer.zoomBy(1.0) })
            .addAction(action(app.vela.R.drawable.ic_car_zoom_out) { renderer.zoomBy(-1.0) })
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .build()
    }

    private fun action(iconRes: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes)).build()).setOnClickListener(onClick).build()
}
