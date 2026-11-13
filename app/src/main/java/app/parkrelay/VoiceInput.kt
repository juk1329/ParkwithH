package app.parkrelay

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Listens for one short answer. Recognition may never end on silence, so a timer closes the
 * microphone. The notification stays up, so a missed answer costs the driver nothing.
 */
class VoiceInput(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var engine: SpeechToText? = null
    private var ready = false
    private var onResult: ((String?) -> Unit)? = null
    private var rmsCount = 0
    private var loudestRms = Double.NEGATIVE_INFINITY

    private val timeout = Runnable {
        Trace.log("stt", "silence timeout rmsUpdates=$rmsCount loudest=$loudestRms")
        Trace.guarded("stt stop") { engine?.stop() }
        deliver(null)
    }

    private val results = object : ResultListener {
        override fun onReady() {
            Trace.log("stt", "ready")
            // The microphone can take seconds to open. The driver's time to answer starts now.
            if (onResult != null) armTimeout(SILENCE_TIMEOUT_MILLIS)
        }

        override fun onStartedRecognition() = Trace.log("stt", "started")
        override fun onUpdatedEpdData(on: Long, off: Long) = Trace.log("stt", "epd on=$on off=$off")

        override fun onUpdatedRms(rms: Double) {
            rmsCount++
            if (rms > loudestRms) loudestRms = rms
        }

        override fun onEndedRecognition() = Trace.log("stt", "ended")

        override fun onUpdated(stt: String, completed: Boolean) {
            Trace.log("stt", "text='${Trace.detail(stt)}' completed=$completed")
            if (completed) deliver(stt)
        }

        override fun onError(errorType: ErrorType) {
            Trace.log("stt", "error $errorType")
            deliver(null)
        }
    }

    fun start() {
        Trace.guarded("stt start") {
            val stt = SpeechToText(appContext, Mode.ON_DEVICE)
            engine = stt
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    Trace.log("stt", "engine ready")
                    Trace.guarded("stt setup") {
                        stt.addListener(results)
                        stt.setLocale(Locale.KOREA)
                    }
                    ready = true
                }

                override fun onFailure(errorType: ErrorType) = Trace.log("stt", "engine failed $errorType")
            })
        }
    }

    /** Call only after any spoken question has finished, so the car does not hear itself. */
    fun listenOnce(result: (String?) -> Unit) {
        if (!ready) {
            result(null)
            return
        }
        onResult = result
        rmsCount = 0
        loudestRms = Double.NEGATIVE_INFINITY
        Trace.guarded("stt request") { engine?.request() }
        armTimeout(OPEN_TIMEOUT_MILLIS + SILENCE_TIMEOUT_MILLIS)
    }

    /** The answer came another way. The microphone closes and nothing is delivered. */
    fun cancel() {
        handler.post {
            if (onResult == null) return@post
            onResult = null
            handler.removeCallbacks(timeout)
            Trace.log("stt", "cancelled")
            Trace.guarded("stt stop") { engine?.stop() }
        }
    }

    private fun armTimeout(millis: Long) {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, millis)
    }

    private fun deliver(text: String?) {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            val waiting = onResult ?: return@post
            onResult = null
            waiting(text)
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        Trace.guarded("stt release") {
            engine?.stop()
            engine?.release()
        }
    }

    private companion object {
        const val SILENCE_TIMEOUT_MILLIS = 8_000L
        const val OPEN_TIMEOUT_MILLIS = 5_000L
    }
}
