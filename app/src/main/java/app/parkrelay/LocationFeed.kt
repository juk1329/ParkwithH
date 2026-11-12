package app.parkrelay

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.parkrelay.core.Fix
import app.parkrelay.core.Lot

/**
 * Vehicle position. FusedLocation is the main source, since it blends GPS with vehicle sensors.
 * Android's own location is the fallback for models where FusedLocation is unsupported.
 */
class LocationFeed(
    private val context: Context,
    private val onFix: (Fix) -> Unit,
    private val onExitedLot: (lotId: String) -> Unit,
    private val onArrivedAtLot: (lotId: String) -> Unit,
) {
    private var fused: FusedLocation? = null
    private var android: LocationManager? = null

    // One alert per purpose: the parked lot and the destination lot. Never one per catalog entry.
    private var exitAlert: Alert? = null
    private var arrivalAlert: Alert? = null

    private class Alert(val lotId: String) {
        var alertId: String? = null
    }

    private var lastLogged: Pair<Double, Double>? = null

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val place = location.latitude to location.longitude
            if (place != lastLogged) {
                lastLogged = place
                Trace.log("location", "fused lat=${location.latitude} lng=${location.longitude}")
            }
            onFix(Fix(location.latitude, location.longitude, System.currentTimeMillis()))
        }

        override fun onError(exception: Exception) = Trace.log("location", "fused error: $exception")
    }

    private val androidListener = LocationListener { location: Location ->
        Trace.log("location", "android lat=${location.latitude} lng=${location.longitude}")
        onFix(Fix(location.latitude, location.longitude, System.currentTimeMillis()))
    }

    fun start() {
        if (!startFused()) startAndroid()
    }

    private fun startFused(): Boolean {
        var started = false
        Trace.guarded("fused start") {
            val client = FusedLocation(context.applicationContext)
            val supported = client.isSupported()
            Trace.log("location", "fused supported=$supported")
            if (supported) {
                client.initialize()
                client.registerFusedLocationCallback(fusedListener)
                client.getLastKnownLocation(fusedListener)
                fused = client
                started = true
            }
        }
        return started
    }

    @SuppressLint("MissingPermission")
    private fun startAndroid() {
        Trace.guarded("android location start") {
            val manager = context.getSystemService(LocationManager::class.java)
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, FIX_INTERVAL_MILLIS, 0f, androidListener, Looper.getMainLooper(),
            )
            android = manager
            Trace.log("location", "using android location")
        }
    }

    fun watchExit(lot: Lot) {
        clear(exitAlert)
        exitAlert = addAlert(lot, ProximityTransition.EXIT) { onExitedLot(lot.id) }
    }

    fun stopWatchingExit() {
        clear(exitAlert)
        exitAlert = null
    }

    fun watchArrival(lot: Lot) {
        if (arrivalAlert?.lotId == lot.id) return
        clear(arrivalAlert)
        arrivalAlert = addAlert(lot, ProximityTransition.ENTER) { onArrivedAtLot(lot.id) }
    }

    fun stopWatchingArrival() {
        clear(arrivalAlert)
        arrivalAlert = null
    }

    private fun addAlert(lot: Lot, wanted: ProximityTransition, onTransition: () -> Unit): Alert? {
        val client = fused ?: return null
        val alert = Alert(lot.id)
        Trace.guarded("proximity add") {
            client.addProximityAlert(
                ProximityAlertRequest(
                    latitude = lot.lat,
                    longitude = lot.lng,
                    radiusMeters = lot.radiusM.toFloat(),
                    transitionTypes = setOf(wanted),
                ),
                object : ProximityAlertListener {
                    override fun onRegistered(alertId: String) {
                        Trace.log("proximity", "registered $wanted lot=${lot.id} id=$alertId")
                        alert.alertId = alertId
                    }

                    override fun onTransition(event: ProximityAlertEvent) {
                        Trace.log("proximity", "transition ${event.transition} lot=${lot.id}")
                        if (event.transition == wanted) onTransition()
                    }

                    override fun onError(exception: Exception) =
                        Trace.log("proximity", "error lot=${lot.id}: $exception")
                },
            )
        }
        return alert
    }

    private fun clear(alert: Alert?) {
        val id = alert?.alertId ?: return
        Trace.guarded("proximity remove") { fused?.removeProximityAlert(id) }
    }

    fun stop() {
        clear(exitAlert)
        clear(arrivalAlert)
        Trace.guarded("location stop") {
            fused?.unregisterFusedLocationCallback(fusedListener)
            fused?.release()
            android?.removeUpdates(androidListener)
        }
    }

    private companion object {
        const val FIX_INTERVAL_MILLIS = 1000L
    }
}
