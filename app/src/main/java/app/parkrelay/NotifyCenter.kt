package app.parkrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.parkrelay.core.Guidance
import app.parkrelay.core.Lot

/**
 * Notifications with action buttons, the second path to the driver. The app is normally off screen,
 * so a parking confirmation has to finish in one tap without opening it.
 *
 * Buttons that finish the job go straight to the service. A button that needs a screen goes straight
 * to the activity, because a service started from a notification may not open a screen itself.
 */
class NotifyCenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "동작 상태", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GUIDE, "주차 안내", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun foreground(): Notification =
        Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_parkrelay)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("주차와 출차를 감지하는 중")
            .setOngoing(true)
            .build()

    /**
     * Information only. While driving nothing here asks for input. A banner stays until removed and
     * covers the turn guidance, so it is removed after a short time. It has been spoken by then.
     */
    fun guide(text: String, id: Int = ID_GUIDE) {
        post(id, base(text).setTimeoutAfter(BANNER_MILLIS).build())
    }

    fun parkPrompt(lot: Lot, suggestedZone: String?) {
        val builder = base(Guidance.parkPromptTitle(suggestedZone)).setContentText(lot.name)
        if (suggestedZone != null) {
            builder.addAction(serviceAction("맞아요", ParkingService.ACTION_CONFIRM_ZONE, 1) { putExtra(ParkingService.EXTRA_ZONE, suggestedZone) })
            builder.addAction(keypadAction("다른 구역", 2))
            builder.addAction(serviceAction("공유 안 함", ParkingService.ACTION_DECLINE_SHARE, 3))
        } else {
            builder.addAction(keypadAction("층·구역 입력", 2))
            builder.addAction(serviceAction("나중에", ParkingService.ACTION_DISMISS, 4))
        }
        post(ID_PROMPT, builder.build())
    }

    fun lotChoice(candidates: List<Lot>) {
        val builder = base("어느 주차장인가요?").setContentText(candidates.joinToString(" · ") { it.name })
        candidates.take(2).forEachIndexed { index, lot ->
            builder.addAction(serviceAction(lot.name, ParkingService.ACTION_PICK_LOT, 10 + index) { putExtra(ParkingService.EXTRA_LOT, lot.id) })
        }
        builder.addAction(serviceAction("여기 아님", ParkingService.ACTION_DISMISS, 12))
        post(ID_PROMPT, builder.build())
    }

    fun shareQuestion(lot: Lot) {
        val builder = base("위치 저장됨. 이곳을 다른 차와 공유할까요?").setContentText(lot.name)
        builder.addAction(serviceAction("공유", ParkingService.ACTION_ALLOW_SHARE, 20))
        builder.addAction(serviceAction("안 함", ParkingService.ACTION_DECLINE_SHARE, 21))
        post(ID_SHARE, builder.build())
    }

    fun departurePrompt(lot: Lot) {
        val builder = base("지금 어느 구역에서 출발하세요?").setContentText(lot.name)
        builder.addAction(keypadAction("층·구역 입력", 2))
        builder.addAction(serviceAction("건너뛰기", ParkingService.ACTION_DISMISS, 4))
        post(ID_PROMPT, builder.build())
    }

    /** The route button appears only while parked, so nothing asks for a tap on the move. */
    fun lotAdvice(text: String, lot: Lot?) {
        val builder = base(text)
        if (lot != null) {
            builder.addAction(serviceAction("여기로 안내", ParkingService.ACTION_ROUTE_TO_LOT, 30) { putExtra(ParkingService.EXTRA_LOT, lot.id) })
            builder.addAction(serviceAction("괜찮아요", ParkingService.ACTION_DISMISS_ADVICE, 31))
        } else {
            builder.setTimeoutAfter(BANNER_MILLIS)
        }
        post(ID_ADVICE, builder.build())
    }

    /** Nothing that asks for a tap may stay in front of the driver once the car is moving. */
    fun cancelButtons() {
        manager.cancel(ID_PROMPT)
        manager.cancel(ID_SHARE)
        manager.cancel(ID_ADVICE)
    }

    fun cancelPrompt() = manager.cancel(ID_PROMPT)
    fun cancelShare() = manager.cancel(ID_SHARE)
    fun cancelAdvice() = manager.cancel(ID_ADVICE)

    private fun post(id: Int, notification: Notification) {
        Trace.guarded("notify") {
            Trace.log("notify", "post id=$id title=${notification.extras.getString(Notification.EXTRA_TITLE)}")
            manager.notify(id, notification)
        }
    }

    private fun base(title: String): Notification.Builder =
        Notification.Builder(context, CHANNEL_GUIDE)
            .setSmallIcon(R.drawable.ic_stat_parkrelay)
            .setContentTitle(title)
            .setAutoCancel(true)
            // Measured on Pleos Connect Emulator 2.1.0: of ten categories at high importance, only
            // navigation and msg drew a banner, in both P and D. This is parking guidance on the way
            // to a destination, so navigation is the honest one of the two.
            .setCategory(Notification.CATEGORY_NAVIGATION)

    private fun serviceAction(
        label: String,
        action: String,
        request: Int,
        configure: Intent.() -> Unit = {},
    ): Notification.Action {
        val intent = Intent(context, ParkingService::class.java).setAction(action).apply(configure)
        val pending = PendingIntent.getService(
            context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun keypadAction(label: String, request: Int): Notification.Action {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_KEYPAD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pending).build()
    }

    companion object {
        const val ID_FOREGROUND = 1
        const val ID_GUIDE = 2
        const val ID_PROMPT = 3
        const val ID_SHARE = 4
        const val ID_ADVICE = 5
        private const val BANNER_MILLIS = 10_000L
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_GUIDE = "guide"
    }
}
