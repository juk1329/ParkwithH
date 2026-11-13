package app.parkrelay

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import app.parkrelay.core.Gear

/**
 * Gear, speed, and driving restrictions from the AAOS Car API.
 *
 * Pleos routes standard data such as gear and speed through this API rather than its own SDK.
 * Listeners only: the platform asks apps to avoid polling.
 */
class CarSignals(
    private val context: Context,
    private val onGear: (Gear) -> Unit,
    private val onSpeed: (Float) -> Unit,
    /** Null while the car has not said, or can no longer say, whether driving restrictions apply. */
    private val onDrivingRestricted: (Boolean?) -> Unit,
) {
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            Trace.log("gear", "raw=$raw")
            onGear(Gear.fromProperty(raw))
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = Trace.log("gear", "error area=$areaId")
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        private var lastLogged = -1f

        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSecond = value.value as? Float ?: return
            if (metersPerSecond != lastLogged) {
                lastLogged = metersPerSecond
                Trace.log("speed", "mps=$metersPerSecond")
            }
            onSpeed(metersPerSecond)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = Trace.log("speed", "error area=$areaId")
    }

    fun start() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { connected, ready ->
            if (ready) {
                connect(connected)
            } else {
                Trace.log("car", "service lost")
                properties = null
                ux = null
                // Not knowing is treated as restricted by everyone who asks.
                onDrivingRestricted(null)
            }
        }
    }

    private fun connect(connected: Car) {
        val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        properties = manager
        Trace.guarded("gear register") {
            val ok = manager.registerCallback(
                gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
            Trace.log("gear", "registered=$ok")
        }
        // CAR_SPEED is a runtime permission. Everything still works from gear timing without it.
        Trace.guarded("speed register") {
            val ok = manager.registerCallback(
                speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL,
            )
            Trace.log("speed", "registered=$ok")
        }
        Trace.guarded("ux register") {
            val restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = restrictions
            restrictions.registerListener { update -> onDrivingRestricted(isRestricted(update)) }
            onDrivingRestricted(isRestricted(restrictions.currentCarUxRestrictions))
        }
    }

    /** The current gear, for reconciling after anything the app may have missed. */
    fun currentGear(): Gear {
        val manager = properties ?: return Gear.UNKNOWN
        return try {
            Gear.fromProperty(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, GLOBAL_AREA))
        } catch (e: RuntimeException) {
            Trace.log("gear", "read failed: $e")
            Gear.UNKNOWN
        }
    }

    fun isConnected() = properties != null

    fun stop() {
        Trace.guarded("car stop") {
            properties?.unregisterCallback(gearCallback)
            properties?.unregisterCallback(speedCallback)
            ux?.unregisterListener()
            car?.disconnect()
        }
    }

    companion object {
        private const val GLOBAL_AREA = 0

        /**
         * Any active restriction counts, not only the general flag: this app's input screens are a
         * keypad and settings, and a region may restrict those specifically.
         */
        fun isRestricted(restrictions: CarUxRestrictions): Boolean =
            restrictions.isRequiresDistractionOptimization ||
                restrictions.activeRestrictions != CarUxRestrictions.UX_RESTRICTIONS_BASELINE
    }
}
