package app.parkrelay

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Spoken guidance, the main path to the driver: it reaches them with the app off screen and
 * needs no glance at a display.
 *
 * Follows the Pleos voice guidance rule: take audio focus first, leave a short silence before
 * speaking, and give focus back when done. Utterances queue, because a new one would otherwise
 * cut off the current one without any callback.
 */
class VoiceGuide(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> Trace.log("voice", "focus change=$change") }
        .build()

    private class Utterance(val text: String, val onDone: (() -> Unit)?)

    private val queue = ArrayDeque<Utterance>()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var speaking: Utterance? = null

    private val events = object : EventListener {
        override fun onReady() = Trace.log("voice", "ready")
        override fun onStart() = Trace.log("voice", "start")
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finished("done")
        override fun onStop() = finished("stop")
        override fun onError(errorType: ErrorType) = finished("error $errorType")
    }

    fun start() {
        Trace.guarded("voice start") {
            val tts = TextToSpeech(appContext, Mode.ON_DEVICE)
            engine = tts
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    Trace.log("voice", "engine ready")
                    Trace.guarded("voice setup") {
                        tts.addEventListener(events)
                        tts.setLocale(Locale.KOREA)
                        tts.setAudioAttributes(attributes)
                    }
                    handler.post {
                        ready = true
                        next()
                    }
                }

                override fun onFailure(errorType: ErrorType) = Trace.log("voice", "engine failed $errorType")
            })
        }
    }

    fun say(text: String, onDone: (() -> Unit)? = null) {
        handler.post {
            queue.addLast(Utterance(text, onDone))
            next()
        }
    }

    /** Ends the current sentence early. Anything queued after it still plays. */
    fun interrupt() {
        handler.post {
            if (speaking == null) return@post
            Trace.guarded("voice interrupt") { engine?.stop() }
            finished("interrupted")
        }
    }

    private fun next() {
        if (!ready || speaking != null) return
        val utterance = queue.removeFirstOrNull() ?: return
        speaking = utterance
        val granted = audio.requestAudioFocus(focus)
        Trace.log("voice", "focus result=$granted text=${utterance.text}")
        handler.postDelayed({
            Trace.guarded("voice speak") { engine?.speak(utterance.text) }
        }, PRE_ROLL_SILENCE_MILLIS)
        handler.postDelayed({
            if (speaking === utterance) finished("timeout")
        }, MAX_UTTERANCE_MILLIS)
    }

    private fun finished(how: String) {
        handler.post {
            Trace.log("voice", "finished $how")
            val done = speaking ?: return@post
            speaking = null
            audio.abandonAudioFocusRequest(focus)
            done.onDone?.invoke()
            next()
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        Trace.guarded("voice stop") {
            engine?.stop()
            engine?.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val PRE_ROLL_SILENCE_MILLIS = 180L
        const val MAX_UTTERANCE_MILLIS = 15_000L
    }
}
