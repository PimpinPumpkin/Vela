package app.vela.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Location permission as the car sees it. A car can connect before Vela was ever opened on the
 * phone, so onboarding never asked: the location feeds then deliver nothing and the car map had
 * nothing to center on, which left it black. The landing screen asks through the car instead,
 * and [granted] tells the feeds to start once the answer comes back.
 */
object CarLocationAccess {
    val PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val _granted = MutableStateFlow(false)

    /** Whether location may be read. Refresh with [check] after asking. */
    val granted: StateFlow<Boolean> = _granted

    fun check(context: Context): Boolean {
        val ok = PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        _granted.value = ok
        return ok
    }
}
