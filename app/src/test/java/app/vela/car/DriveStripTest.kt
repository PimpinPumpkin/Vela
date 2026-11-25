package app.vela.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.NavigationTemplate
import org.junit.Test

/** The drive screen's strip with a faster-route offer: the offer, the voice button and End, which
 *  the car library's navigation limits (four actions, one titled) take (#927). */
class DriveStripTest {
    @Test fun `an offer, the voice button and End fit the drive strip`() {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Faster 3 min").setOnClickListener {}.build())
            .addAction(Action.Builder().setIcon(CarIcon.ALERT).setOnClickListener {}.build())
            .addAction(
                Action.Builder().setIcon(CarIcon.ERROR).setFlags(Action.FLAG_PRIMARY)
                    .setBackgroundColor(CarColor.RED).setOnClickListener {}.build(),
            )
            .build()
        NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a fifth action is refused, so the limits are really checked here`() {
        val strip = ActionStrip.Builder().apply {
            repeat(5) { addAction(Action.Builder().setIcon(CarIcon.ALERT).setOnClickListener {}.build()) }
        }.build()
        NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
