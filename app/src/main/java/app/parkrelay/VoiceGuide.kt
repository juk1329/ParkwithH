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
 *
 * Focus decides everything: nothing is spoken without it, and losing it to a call or another
 * announcement stops the sentence and drops what was waiting. Parking news is only worth hearing
 * while it is fresh, so it is never replayed later.
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
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
        .build()

    /** [onDone] hears whether the sentence was spoken to its end. A question never heard must not open the microphone. */
    private class Utterance(val text: String, val queuedAt: Long, val onDone: ((spoken: Boolean) -> Unit)?)

    private val queue = ArrayDeque<Utterance>()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var speaking: Utterance? = null

    private val events = object : EventListener {
        override fun onReady() = Trace.log("voice", "ready")
        override fun onStart() = Trace.log("voice", "start")
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finished("done", spoken = true)
        override fun onStop() = finished("stop", spoken = false)
        override fun onError(errorType: ErrorType) = finished("error $errorType", spoken = false)
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

    fun say(text: String, onDone: ((spoken: Boolean) -> Unit)? = null) {
        handler.post {
            queue.addLast(Utterance(text, System.currentTimeMillis(), onDone))
            next()
        }
    }

    /** Ends the current sentence early. Anything queued after it still plays. */
    fun interrupt() {
        handler.post {
            if (speaking == null) return@post
            Trace.guarded("voice interrupt") { engine?.stop() }
            settle("interrupted", spoken = false)
        }
    }

    /** Consent withdrawn or the service stopping: nothing more is said. */
    fun silence() {
        handler.post {
            dropQueue("silenced")
            if (speaking != null) {
                Trace.guarded("voice silence") { engine?.stop() }
                settle("silenced", spoken = false)
            }
        }
    }

    private fun next() {
        if (!ready || speaking != null) return
        val utterance = queue.removeFirstOrNull() ?: return
        if (System.currentTimeMillis() - utterance.queuedAt > STALE_MILLIS) {
            Trace.log("voice", "dropped stale text=${Trace.detail(utterance.text)}")
            utterance.onDone?.invoke(false)
            next()
            return
        }
        speaking = utterance
        val result = audio.requestAudioFocus(focus)
        Trace.log("voice", "focus result=$result text=${Trace.detail(utterance.text)}")
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // A call or a higher-priority announcement holds the speakers. Speaking over it is not ours to decide.
            settle("focus refused", spoken = false)
            return
        }
        handler.postDelayed({
            // Focus can be lost during the silence; by then this sentence has been dropped.
            if (speaking === utterance) Trace.guarded("voice speak") { engine?.speak(utterance.text) }
        }, PRE_ROLL_SILENCE_MILLIS)
        handler.postDelayed({
            if (speaking === utterance) settle("timeout", spoken = false)
        }, MAX_UTTERANCE_MILLIS)
    }

    private fun onFocusChange(change: Int) {
        Trace.log("voice", "focus change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                dropQueue("focus lost")
                if (speaking != null) {
                    Trace.guarded("voice stop on focus loss") { engine?.stop() }
                    settle("focus lost", spoken = false)
                }
            }
            // Ducking is the system lowering our volume under something else: the sentence may finish.
            else -> Unit
        }
    }

    private fun dropQueue(why: String) {
        if (queue.isEmpty()) return
        Trace.log("voice", "dropped ${queue.size} queued: $why")
        val dropped = queue.toList()
        queue.clear()
        dropped.forEach { it.onDone?.invoke(false) }
    }

    /** Engine callbacks arrive on its own thread. */
    private fun finished(how: String, spoken: Boolean) {
        handler.post { settle(how, spoken) }
    }

    /** Main thread only. The engine may report a stop we already settled; the second report finds nothing. */
    private fun settle(how: String, spoken: Boolean) {
        val done = speaking ?: return
        Trace.log("voice", "finished $how")
        speaking = null
        audio.abandonAudioFocusRequest(focus)
        done.onDone?.invoke(spoken)
        next()
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

        /** A sentence that waited this long behind others is about a moment that has passed. */
        const val STALE_MILLIS = 20_000L
    }
}
