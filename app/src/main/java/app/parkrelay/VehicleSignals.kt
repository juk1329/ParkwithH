package app.parkrelay

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Build

/**
 * Doors and seats from the Pleos Vehicle SDK. They separate real parking from a drop-off.
 *
 * Support varies by model, so each signal is used only if its capability check succeeds. The
 * app keeps working on gear and timing alone when a signal is missing.
 */
class VehicleSignals(
    private val context: Context,
    private val onDriverDoor: (open: Boolean) -> Unit,
    private val onPassengerDoor: (open: Boolean) -> Unit,
    private val onDriverSeat: (occupied: Boolean) -> Unit,
) {
    private var vehicle: Vehicle? = null
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val passengerDoor = DoorArea(DoorValue.ROW_1_RIGHT)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val driverDoorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            Trace.log("door", "driver=$pos")
            if (pos != null) onDriverDoor(pos > 0)
        }

        override fun onFailed(e: Exception) = Trace.log("door", "driver failed: $e")
    }

    private val passengerDoorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            Trace.log("door", "passenger=$pos")
            if (pos != null) onPassengerDoor(pos > 0)
        }

        override fun onFailed(e: Exception) = Trace.log("door", "passenger failed: $e")
    }

    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) {
            val corrected = occupied?.let { if (SEAT_VALUE_INVERTED) !it else it }
            Trace.log("seat", "driver raw=$occupied occupied=$corrected")
            if (corrected != null) onDriverSeat(corrected)
        }

        override fun onFailed(e: Exception) = Trace.log("seat", "failed: $e")
    }

    fun start() {
        Trace.guarded("vehicle start") {
            val connected = Vehicle(context.applicationContext)
            vehicle = connected
            connected.initialize()
            val door = connected.getDoor()
            door.checkDoorPositionCapabilities(
                onComplete = { config, _ ->
                    Trace.log("door", "capability access=${config.accessType}")
                    Trace.guarded("door register") {
                        door.registerDoorPosition(driverDoor, driverDoorListener)
                        door.registerDoorPosition(passengerDoor, passengerDoorListener)
                    }
                },
                onFailed = { e -> Trace.log("door", "capability failed: $e") },
            )
            val seat = connected.getSeat()
            seat.checkSeatOccupancyCapabilities(
                onComplete = { config, _ ->
                    Trace.log("seat", "capability access=${config.accessType}")
                    Trace.guarded("seat register") { seat.registerSeatOccupancy(driverSeat, seatListener) }
                },
                onFailed = { e -> Trace.log("seat", "capability failed: $e") },
            )
        }
    }

    fun stop() {
        Trace.guarded("vehicle stop") {
            vehicle?.let {
                it.getDoor().unregisterDoorPosition(driverDoor, driverDoorListener)
                it.getDoor().unregisterDoorPosition(passengerDoor, passengerDoorListener)
                it.getSeat().unregisterSeatOccupancy(driverSeat, seatListener)
                it.release()
            }
        }
    }

    private companion object {
        /**
         * Measured on Pleos Connect Emulator 2.1.0: setting the console to Occupied reports false.
         * Real cars are unverified, so only the emulator is corrected. A wrong reading stays on the
         * safe side either way: the driver door still commits a parking, and the seat alone cannot
         * publish anything.
         */
        val SEAT_VALUE_INVERTED = Build.HARDWARE == "ranchu"
    }
}
