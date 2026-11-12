package app.parkrelay

import app.parkrelay.core.EventType
import app.parkrelay.core.LotBoard
import app.parkrelay.core.OutEvent
import app.parkrelay.core.ZoneRow
import app.parkrelay.core.ZoneSignal
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The server boundary. Demo mode swaps this out, so a reviewer can run the APK with no server. */
interface Backend {
    /** Returns true once the server has accepted the batch. */
    fun send(events: List<OutEvent>): Boolean

    fun subscribe(lotId: String, onSignal: (ZoneSignal) -> Unit): Subscription

    /** Blocks on the network, so call it off the main thread. Null when it cannot be reached. */
    fun board(lotId: String): LotBoard?

    fun interface Subscription {
        fun cancel()
    }
}

class HttpBackend(private val baseUrl: () -> String) : Backend {
    override fun send(events: List<OutEvent>): Boolean = try {
        val connection = URL("${baseUrl()}/v1/events").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("events", JSONArray(events.map(EventCodec::encode)))
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = connection.responseCode
        connection.disconnect()
        Trace.log("backend", "send count=${events.size} status=$code")
        code in 200..299
    } catch (e: Exception) {
        Trace.log("backend", "send failed: $e")
        false
    }

    override fun subscribe(lotId: String, onSignal: (ZoneSignal) -> Unit): Backend.Subscription {
        val cancelled = AtomicBoolean(false)
        var active: HttpURLConnection? = null
        val thread = Thread({
            while (!cancelled.get()) {
                try {
                    val connection = URL("${baseUrl()}/v1/lots/$lotId/stream").openConnection() as HttpURLConnection
                    active = connection
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 0
                    connection.setRequestProperty("Accept", "text/event-stream")
                    Trace.log("backend", "stream open lot=$lotId status=${connection.responseCode}")
                    connection.inputStream.bufferedReader().use { readStream(it, cancelled, onSignal) }
                } catch (e: Exception) {
                    if (!cancelled.get()) Trace.log("backend", "stream lost: $e")
                }
                if (!cancelled.get()) Thread.sleep(RECONNECT_MILLIS)
            }
        }, "lot-stream")
        thread.isDaemon = true
        thread.start()
        return Backend.Subscription {
            cancelled.set(true)
            active?.disconnect()
        }
    }

    override fun board(lotId: String): LotBoard? = try {
        val connection = URL("${baseUrl()}/v1/lots/$lotId/board").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        EventCodec.decodeBoard(text)
    } catch (e: Exception) {
        Trace.log("backend", "board failed: $e")
        null
    }

    private fun readStream(reader: BufferedReader, cancelled: AtomicBoolean, onSignal: (ZoneSignal) -> Unit) {
        while (!cancelled.get()) {
            val line = reader.readLine() ?: return
            if (!line.startsWith("data:")) continue
            val signal = EventCodec.decodeSignal(line.removePrefix("data:").trim()) ?: continue
            onSignal(signal)
        }
    }

    private companion object {
        const val RECONNECT_MILLIS = 3_000L
    }
}

object EventCodec {
    fun encode(event: OutEvent): JSONObject = JSONObject()
        .put("eventId", event.eventId)
        .put("sessionId", event.sessionId)
        .put("type", event.type.name)
        .put("lotId", event.lotId)
        .putOpt("zoneId", event.zoneId)
        .put("occurredAt", event.occurredAt)
        .putOpt("searchSec", event.searchSeconds)
        .putOpt("evidence", event.evidence?.name)
        .put("weak", event.weak)

    fun decodeSignal(text: String): ZoneSignal? = try {
        val json = JSONObject(text)
        ZoneSignal(
            type = EventType.valueOf(json.getString("type")),
            zoneId = json.optString("zoneId").takeIf { it.isNotEmpty() && it != "null" },
            weak = json.optBoolean("weak"),
            eventId = json.optString("eventId").takeIf { it.isNotEmpty() },
        )
    } catch (e: Exception) {
        Trace.log("backend", "bad signal: $e")
        null
    }

    fun decodeBoard(text: String): LotBoard? = try {
        val json = JSONObject(text)
        val zones = json.getJSONArray("zones")
        LotBoard(
            capacity = if (json.isNull("registeredCapacity")) null else json.getInt("registeredCapacity"),
            parkedAppCars = json.getInt("parkedAppCars"),
            zones = (0 until zones.length()).map { index ->
                val zone = zones.getJSONObject(index)
                val ages = zone.getJSONArray("vacancies")
                ZoneRow(
                    zoneId = zone.getString("zone_id"),
                    vacancyAges = (0 until ages.length()).map(ages::getLong),
                    leavingSoon = zone.getInt("leaving_soon"),
                    parkedAppCars = zone.getInt("parked_app_cars"),
                )
            },
        )
    } catch (e: Exception) {
        Trace.log("backend", "bad board: $e")
        null
    }
}

/**
 * Events go to disk first and to the network second. The ignition can go off right after P, and
 * underground lots often have no signal, so a send can fail at any point without losing anything.
 */
class EventQueue(directory: File, private val backend: () -> Backend) {
    private val file = File(directory, "events.queue")
    private val executor = Executors.newSingleThreadExecutor()

    fun add(event: OutEvent) {
        executor.execute {
            Trace.guarded("queue add") { file.appendText(EventCodec.encode(event).toString() + "\n") }
            drain()
        }
    }

    fun flush() = executor.execute { drain() }

    private fun drain() {
        Trace.guarded("queue drain") {
            if (!file.exists()) return@guarded
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@guarded
            if (backend().send(lines.map(::decode))) file.writeText("")
        }
    }

    private fun decode(line: String): OutEvent {
        val json = JSONObject(line)
        return OutEvent(
            eventId = json.getString("eventId"),
            sessionId = json.getString("sessionId"),
            type = EventType.valueOf(json.getString("type")),
            lotId = json.getString("lotId"),
            zoneId = json.optString("zoneId").takeIf { it.isNotEmpty() },
            occurredAt = json.getLong("occurredAt"),
            searchSeconds = if (json.has("searchSec")) json.getLong("searchSec") else null,
            evidence = json.optString("evidence").takeIf { it.isNotEmpty() }?.let { app.parkrelay.core.Evidence.valueOf(it) },
            weak = json.optBoolean("weak"),
        )
    }
}
