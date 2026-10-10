package app.vela.car.screen

import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import app.vela.core.data.RoutingPrefs

/**
 * The settings a driver reaches for in the car: spoken directions and the route avoids. They
 * write the same prefs the phone's toggles do, so the two always agree.
 */
class CarSettingsScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {

    private val prefs = carContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                toggleRow(app.vela.R.string.car_setting_voice, !deps.voiceGuide.muted) { on ->
                    app.vela.car.CarVoice.set(carContext, deps.voiceGuide, on)
                },
            )
            .addItem(
                toggleRow(app.vela.R.string.car_setting_avoid_tolls, RoutingPrefs.avoidTolls) { on ->
                    RoutingPrefs.avoidTolls = on
                    prefs.edit().putBoolean("avoid_tolls", on).apply()
                },
            )
            .addItem(
                toggleRow(app.vela.R.string.car_setting_avoid_highways, RoutingPrefs.avoidHighways) { on ->
                    RoutingPrefs.avoidHighways = on
                    prefs.edit().putBoolean("avoid_highways", on).apply()
                },
            )
            .addItem(
                toggleRow(app.vela.R.string.car_setting_avoid_ferries, RoutingPrefs.avoidFerries) { on ->
                    RoutingPrefs.avoidFerries = on
                    prefs.edit().putBoolean("avoid_ferries", on).apply()
                },
            )

        return ListTemplate.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_settings))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun toggleRow(title: Int, checked: Boolean, onChange: (Boolean) -> Unit): Row =
        Row.Builder()
            .setTitle(carContext.getString(title))
            .setToggle(Toggle.Builder { on -> onChange(on); invalidate() }.setChecked(checked).build())
            .build()
}
