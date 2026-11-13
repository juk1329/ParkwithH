package app.parkrelay

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/**
 * Lets a screen ask the car itself whether input is allowed, without leaning on the parking service.
 * The service may not exist yet (before consent) or may be restarting, and a screen that assumed
 * "allowed" in those moments would show buttons to a driver on the move.
 */
class UxWatch(private val context: Context, private val onChange: (DrivingState) -> Unit) {
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    var state = DrivingState.UNKNOWN
        private set

    fun start() {
        Trace.guarded("ux watch start") {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) connect(connected) else update(null)
            }
        }
    }

    private fun connect(connected: Car) {
        Trace.guarded("ux watch connect") {
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = manager
            manager.registerListener { restrictions -> update(CarSignals.isRestricted(restrictions)) }
            update(CarSignals.isRestricted(manager.currentCarUxRestrictions))
        }
    }

    private fun update(restricted: Boolean?) {
        val next = when (restricted) {
            null -> DrivingState.UNKNOWN
            true -> DrivingState.RESTRICTED
            false -> DrivingState.UNRESTRICTED
        }
        if (next == state) return
        state = next
        onChange(next)
    }

    fun stop() {
        Trace.guarded("ux watch stop") {
            ux?.unregisterListener()
            car?.disconnect()
        }
        ux = null
        car = null
        state = DrivingState.UNKNOWN
    }
}
