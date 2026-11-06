package app.vela.vehicle

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.car.app.SurfaceContainer
import app.vela.car.CarMapRenderer

/**
 * Shows Vela's real navigation map on a BYD instrument-cluster display (SPEC 10.9). The cluster's
 * projection area is a public presentation display owned by the container service, so this is the
 * ordinary [Presentation] API with no ADB, no overlay permission and no signature. The map is the
 * same one Android Auto draws: a [CarMapRenderer] reads the shared navigation session, so the
 * cluster, the phone and Android Auto all show one route. The renderer's speed badge is off here,
 * because the cluster has its own.
 *
 * The cluster only shows this display while its own projection mode is on, which the app cannot
 * switch; the driver turns it on. A `NOT_TOUCHABLE` window, because the cluster takes no input.
 */
internal class ClusterMapPresentation(
    outerContext: Context,
    display: Display,
    private val renderer: CarMapRenderer,
) : Presentation(outerContext, display) {
    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        }
        val view = SurfaceView(context)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val dpi = context.resources.displayMetrics.densityDpi
                runCatching { renderer.onSurfaceAvailable(SurfaceContainer(holder.surface, width, height, dpi)) }
                    .onFailure { Log.w(TAG, "cluster surface handoff failed", it) }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                runCatching { renderer.onSurfaceDestroyed(SurfaceContainer(holder.surface, 0, 0, 0)) }
            }
        })
        surfaceView = view
        setContentView(view)
    }

    private companion object { const val TAG = "VelaCluster" }
}
