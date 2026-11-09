package app.vela.vehicle

import android.content.Context
import app.vela.core.vehicle.ClusterTargets
import app.vela.core.vehicle.VehicleSupport

/**
 * One read of what the BYD vehicle integrations can do on this head unit, for Settings >
 * Diagnostics (SPEC 10.9). It reports each capability's state separately, so "not present" (no
 * such head unit), "available" (present, allowed, off or idle) and "active" never look the same.
 * Nothing here probes aggressively or caches: it reads the display list and the service presence
 * at the moment it is asked, plus the live state the controllers already hold.
 *
 * What Vela does not implement is reported as such, not hidden: there is no ADB cluster fallback.
 * See SPEC 10.9.
 */
object VehicleCapabilities {
    @Volatile var clusterController: ClusterMapController? = null
    @Volatile var fceHud: FceClusterHud? = null
    @Volatile var someIpHud: SomeIpHud? = null

    data class Line(val label: String, val state: VehicleSupport, val detail: String? = null)

    fun report(context: Context): List<Line> {
        val cluster = clusterController
        val fce = fceHud
        val targets = cluster?.targets() ?: ClusterTargets.candidates(presentationNames(context))
        val wheelOn = SteeringWheelService.isEnabled(context)
        return listOf(
            Line(
                "Cluster map",
                cluster?.support ?: if (targets.isEmpty()) VehicleSupport.NOT_PRESENT else VehicleSupport.AVAILABLE,
                cluster?.let { "route=${it.route}, target=${targets.firstOrNull() ?: "none"}" } ?: "targets=${targets.size}",
            ),
            Line("FCE cluster HUD", fce?.support ?: if (FceCarPropertyTransport.available()) VehicleSupport.AVAILABLE else VehicleSupport.NOT_PRESENT),
            Line("Windshield HUD (SOME/IP)", someIpHud?.support ?: if (SomeIpHudTransport.available(context)) VehicleSupport.AVAILABLE else VehicleSupport.NOT_PRESENT),
            Line("Steering voice key", if (wheelOn) VehicleSupport.ACTIVE else VehicleSupport.AVAILABLE, if (wheelOn) "accessibility service enabled" else "enable in Android Settings"),
            Line("ADB cluster fallback", VehicleSupport.NOT_IN_THIS_BUILD, "public display only"),
        )
    }

    /** One line for the diagnostics export. */
    fun exportLine(context: Context): String =
        report(context).joinToString("; ") { "${it.label}=${it.state}" } +
            (clusterController?.let { "; " + it.diagnostic() } ?: "")

    private fun presentationNames(context: Context): List<String> =
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.map { it.name } ?: emptyList()
}
