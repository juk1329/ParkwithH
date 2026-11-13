package app.parkrelay

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import app.parkrelay.core.Destination
import app.parkrelay.core.Lot

/**
 * The built-in navigation, reached through NaviHelper. It gives the app the driver's destination
 * without asking, and lets the app send a lot back to it as the new destination.
 */
class NaviSource(
    private val context: Context,
    private val onDestination: (Destination?, remainSeconds: Int?) -> Unit,
    private val onArrived: () -> Unit,
) {
    private var helper: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() {
            Trace.log("navi", "destination changed")
            Trace.guarded("navi destination") { helper?.getDestinationInfo() }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Trace.log("navi", "route started ${Trace.detail(routeStartInfo)}")
            Trace.guarded("navi destination") { helper?.getDestinationInfo() }
        }

        override fun onRoutingCompleted() {
            Trace.log("navi", "routing completed")
            Trace.guarded("navi destination") { helper?.getDestinationInfo() }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            Trace.log("navi", "route state ${Trace.detail(routeStateInfo)}")
        }

        override fun onError(errorCode: NaviErrorCode) {
            Trace.log("navi", "error $errorCode")
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Trace.log("navi", "destination ${Trace.detail(destinationInfo)}")
            // With no route set, the navigation reports an empty destination at 0, 0.
            val none = destinationInfo.latitude == 0.0 && destinationInfo.longitude == 0.0
            onDestination(
                if (none) null else Destination(destinationInfo.latitude, destinationInfo.longitude, destinationInfo.name),
                destinationInfo.remainTime,
            )
        }

        override fun onRouteCancelled() {
            Trace.log("navi", "route cancelled")
            onDestination(null, null)
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Trace.log("navi", "arrived ${Trace.detail(destinationArrivedInfo)}")
            onArrived()
        }
    }

    fun start() {
        Trace.guarded("navi start") {
            val connected = NaviHelper(context.applicationContext)
            helper = connected
            connected.initialize(
                {
                    Trace.log("navi", "connected")
                    Trace.guarded("navi listen") {
                        connected.addListener(listener)
                        connected.getDestinationInfo()
                    }
                },
                { error -> Trace.log("navi", "connect failed: $error") },
            )
        }
    }

    /** Sends a lot to the navigation as the destination. The driver asked for this by tapping or saying yes. */
    fun routeTo(lot: Lot) = routeTo(lot.lat, lot.lng, lot.name)

    internal fun routeTo(lat: Double, lng: Double, name: String) {
        Trace.guarded("navi route") {
            helper?.requestRoute(
                RouteInfo(
                    latitude = lat,
                    longitude = lng,
                    poiId = "",
                    poiName = name,
                    poiSubId = "0",
                    address = "",
                ),
            )
            Trace.log("navi", "route requested to ${Trace.detail(name)}")
        }
    }

    fun stop() {
        Trace.guarded("navi stop") {
            helper?.removeListener(listener)
            helper?.release()
        }
    }
}
