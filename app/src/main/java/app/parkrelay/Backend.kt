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

/**
 * What became of a batch. REJECTED means the server understood the request and refused it, so sending
 * the same bytes again cannot help. FAILED means it never got an answer, and a retry may.
 */
enum class Delivery { ACCEPTED, REJECTED, FAILED }

/** The server boundary. Demo mode swaps this out, so a reviewer can run the APK with no server. */
interface Backend {
    fun send(events: List<OutEvent>): Delivery

    fun subscribe(lotId: String, onSignal: (ZoneSignal) -> Unit): Subscription

    /** Blocks on the network, so call it off the main thread. Null when it cannot be reached. */
    fun board(lotId: String): LotBoard?

    fun interface Subscription {
        fun cancel()
    }
}

class HttpBackend(private val baseUrl: () -> String) : Backend {
    override fun send(events: List<OutEvent>): Delivery = try {
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
        when (code) {
            in 200..299 -> Delivery.ACCEPTED
            in 400..499 -> Delivery.REJECTED
            else -> Delivery.FAILED
        }
    } catch (e: Exception) {
        Trace.log("backend", "send failed: $e")
        Delivery.FAILED
    }

    override fun subscribe(lotId: String, onSignal: (ZoneSignal) -> Unit): Backend.Subscription {
        val cancelled = AtomicBoolean(false)
        val active = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val thread = Thread({
            while (!cancelled.get()) {
                try {
                    val connection = URL("${baseUrl()}/v1/lots/$lotId/stream").openConnection() as HttpURLConnection
                    active.set(connection)
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
            active.get()?.disconnect()
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
                    // Absent on a server from before claims existed.
                    claims = zone.optInt("claims", 0),
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
 *
 * The file is sent in batches the server accepts, and only what was answered leaves the file. An
 * event the server refuses is dropped rather than retried forever in front of everything behind it.
 */
class EventQueue(directory: File, private val backend: () -> Backend) {
    private val file = File(directory, FILE_NAME)
    private val temp = File(directory, "$FILE_NAME.tmp")
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var closed = false

    /** After a send that got no answer, new events only go to disk for a while: each try can block for seconds. */
    private var quietUntil = 0L

    fun add(event: OutEvent) {
        executor.execute {
            if (closed) return@execute
            Trace.guarded("queue add") { file.appendText(EventCodec.encode(event).toString() + "\n") }
            if (System.currentTimeMillis() >= quietUntil) drain()
        }
    }

    /** Service start, screen on, or the server choice changing: reasons to try again at once. */
    fun flush() = executor.execute {
        quietUntil = 0L
        drain()
    }

    /** Consent withdrawn: nothing queued may be sent later, and nothing new is accepted. */
    fun close() {
        closed = true
        executor.execute {
            Trace.guarded("queue close") {
                file.delete()
                temp.delete()
            }
        }
    }

    private fun drain() {
        Trace.guarded("queue drain") {
            while (!closed && file.exists()) {
                val lines = file.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@guarded
                val batch = lines.take(BATCH_SIZE)
                // A line this build cannot read will never become readable, so it goes with the batch.
                val events = batch.mapNotNull { line -> runCatching { decode(line) }.getOrNull() }
                val settled = events.isEmpty() || deliver(events)
                if (!settled) {
                    quietUntil = System.currentTimeMillis() + RETRY_PAUSE_MILLIS
                    return@guarded
                }
                keep(lines.drop(batch.size))
            }
        }
    }

    /** True when every event was answered, accepted or refused. False when the server could not be reached. */
    private fun deliver(events: List<OutEvent>): Boolean = when (backend().send(events)) {
        Delivery.ACCEPTED -> true
        Delivery.FAILED -> false
        Delivery.REJECTED ->
            // One bad event must not take its neighbours with it: each is offered on its own.
            if (events.size == 1) {
                Trace.log("queue", "dropped an event the server refused: ${events.first().type}")
                true
            } else {
                events.all { deliver(listOf(it)) }
            }
    }

    private fun keep(lines: List<String>) {
        temp.writeText(lines.joinToString("") { it + "\n" })
        if (!temp.renameTo(file)) file.writeText(temp.readText())
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

    companion object {
        const val FILE_NAME = "events.queue"

        /** The server takes at most this many events in one request. */
        const val BATCH_SIZE = 200
        private const val RETRY_PAUSE_MILLIS = 15_000L
    }
}
