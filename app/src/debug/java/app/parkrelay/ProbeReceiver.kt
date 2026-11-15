package app.parkrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets scripts exercise paths that have no vehicle signal behind them, such as speech or a notification
 * while the app is off screen. Exists only in debug builds, so release APKs cannot be driven this way.
 *
 * adb shell am broadcast --user 10 -n app.parkrelay/.ProbeReceiver --es cmd speak --es text "..."
 */
class ProbeReceiver : BroadcastReceiver() {
    private companion object {
        var stolen: android.media.AudioFocusRequest? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        Trace.log("probe", "cmd=$cmd")
        if (cmd == "consent") {
            Settings(context).consent = true
            Trace.log("probe", "consent set")
            return
        }
        // Lets a scripted run hear the lot's size again. A restart keeps this memory on purpose.
        if (cmd == "forget-advice") {
            Settings(context).advisedLot = null
            return
        }
        val service = ParkingService.instance
        if (service == null) {
            Trace.log("probe", "service is not running")
            return
        }
        val text = intent.getStringExtra("text").orEmpty()
        when (cmd) {
            "simulate" -> service.simulateDeparture()
            // The demo button "주차장 100m 앞 도착 (가상 위치)".
            "near" -> service.simulateNear()
            "demo" -> {
                service.settings.demoMode = text.toBoolean()
                service.backendChanged()
                Trace.log("probe", "demo=${service.settings.demoMode}")
            }
            "speak" -> service.voice.say(text) { Trace.log("probe", "speech finished") }
            "listen" -> service.voiceInput.listenOnce { heard -> Trace.log("probe", "heard=$heard") }
            "guide" -> service.notify.guide(text)
            // Tries each notification category, to learn which ones this system shows as heads-up.
            "category" -> {
                val manager = service.getSystemService(android.app.NotificationManager::class.java)
                val category = text.ifEmpty { null }
                manager.notify(
                    100 + (text.hashCode() and 0xff),
                    android.app.Notification.Builder(service, "guide")
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setContentTitle("시험 알림 $category")
                        .setCategory(category)
                        .setAutoCancel(true)
                        .build(),
                )
            }
            "clear" -> {
                val manager = service.getSystemService(android.app.NotificationManager::class.java)
                manager.activeNotifications.filter { it.id >= 100 }.forEach { manager.cancel(it.id) }
            }
            "prompt" -> service.catalog.byId(text)?.let { service.notify.parkPrompt(it, "B2-B") }
            // Answers the parking prompt for a car that only sets the scene in a scripted run.
            "zone" -> {
                service.notify.cancelPrompt()
                service.dispatch(app.parkrelay.core.Input.ZoneAnswered(text, System.currentTimeMillis()))
            }
            "pick" -> service.catalog.byId(text)?.let {
                service.dispatch(app.parkrelay.core.Input.LotPicked(it, System.currentTimeMillis()))
                service.notify.parkPrompt(it, null)
            }
            "server" -> service.settings.serverUrl = text
            "state" -> Trace.log("probe", "state=${service.state}")
            // The approach to the destination lot: phase, the zone this car said it is heading for, the list.
            "approach" -> Trace.log("probe", "approach=${service.approachDebug()}")
            // Names another zone, as the driver would by voice after the list.
            "claim" -> service.pickHeading(text)
            "unclaim" -> service.declineHeading()
            // "No space there", as the driver would say it; and the question that lets them say it.
            "missed" -> service.missedHeading()
            "check" -> service.checkSpace()
            "arrive" -> service.simulateArrive()
            // Queues this many harmless events (an EXIT of a session that never parked changes no board),
            // to check that a long time offline still drains in batches the server accepts.
            "flood" -> {
                val now = System.currentTimeMillis()
                repeat(text.toInt()) { index ->
                    service.enqueueForProbe(
                        app.parkrelay.core.OutEvent(
                            eventId = "flood-$now-$index",
                            sessionId = "flood-$now",
                            type = app.parkrelay.core.EventType.EXIT,
                            lotId = "59446-29391-00005-00-1",
                            zoneId = null,
                            occurredAt = now + index,
                        ),
                    )
                }
                Trace.log("probe", "flooded ${text.toInt()}")
            }
            "flush" -> service.flushForProbe()
            // Another client takes audio focus, as a phone call or a system announcement would.
            "focus-steal" -> {
                val audio = service.getSystemService(android.media.AudioManager::class.java)
                val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                stolen = request
                Trace.log("probe", "focus steal result=${audio.requestAudioFocus(request)}")
            }
            "focus-release" -> {
                val audio = service.getSystemService(android.media.AudioManager::class.java)
                stolen?.let { audio.abandonAudioFocusRequest(it) }
                stolen = null
            }
            "share" -> {
                // "lotId,true|false": the per-lot sharing switch on the settings screen.
                val (lotId, allow) = text.split(",")
                service.settings.rememberShare(lotId, allow.toBoolean())
                service.shareChanged(lotId, allow.toBoolean())
            }
            "withdraw" -> {
                // What the "데이터 삭제와 동의 철회" button does.
                service.eraseEverything()
                service.settings.clearAll()
                service.stopService(Intent(service, ParkingService::class.java))
                Trace.log("probe", "withdrawn")
            }
            "route" -> service.catalog.byId(text)?.let(service.navi::routeTo)
            // "lat,lng,name": a destination that is not a lot, as a driver would set by hand.
            "dest" -> text.split(",", limit = 3).let { service.navi.routeTo(it[0].toDouble(), it[1].toDouble(), it[2]) }
            // Android blocks background activity starts. This records whether that holds on this image.
            "launch" -> {
                val launch = Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Trace.guarded("launch") { service.startActivity(launch) }
            }
        }
    }
}
