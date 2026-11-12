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
