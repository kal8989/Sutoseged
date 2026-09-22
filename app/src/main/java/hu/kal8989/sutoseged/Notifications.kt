package hu.kal8989.sutoseged

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifications {

    /** Új azonosító, mert a csatorna beállításai létrehozás után már nem módosíthatók. */
    const val CH_TIMER = "sutoseged_timer_v2"
    const val CH_PLAN = "sutoseged_plan"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val timer = NotificationChannel(
            CH_TIMER, "Sütési fázisok", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Hangjelzés, ha egy fázis ideje lejárt"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 700)
            setSound(alarmSound(), alarmAttributes())
            setBypassDnd(false)
        }

        val plan = NotificationChannel(
            CH_PLAN, "Elkészült tervek", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Értesítés, ha a sütési terv elkészült" }

        nm.createNotificationChannel(timer)
        nm.createNotificationChannel(plan)
    }

    private fun alarmSound(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

    fun show(context: Context, channel: String, title: String, text: String, id: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensure(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)

        if (channel == CH_TIMER) {
            // Android 7 és régebbi: a csatorna helyett itt kell megadni.
            b.setSound(alarmSound(), android.media.AudioManager.STREAM_ALARM)
            b.setVibrate(longArrayOf(0, 500, 250, 500, 250, 700))
        }

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
        }
    }
}

/** A lejáró fázisidőt a rendszer ébresztője hozza, így az app bezárva is jelez. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nev = intent.getStringExtra("nev") ?: "Sütési fázis"
        val reszlet = intent.getStringExtra("reszlet").orEmpty()
        Notifications.show(
            context,
            Notifications.CH_TIMER,
            "Lejárt: $nev",
            reszlet.ifBlank { "A fázis ideje letelt." },
            nev.hashCode()
        )
    }
}

object Alarms {

    fun schedule(context: Context, key: String, nev: String, reszlet: String, endAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, key, nev, reszlet)
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(endAt, pi), pi)
        }.onFailure {
            runCatching { am.setExact(AlarmManager.RTC_WAKEUP, endAt, pi) }
        }
    }

    fun cancel(context: Context, key: String, nev: String, reszlet: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pending(context, key, nev, reszlet)) }
    }

    private fun pending(context: Context, key: String, nev: String, reszlet: String): PendingIntent {
        val i = Intent(context, TimerReceiver::class.java).apply {
            data = Uri.parse("sutoseged://timer/$key")
            putExtra("nev", nev)
            putExtra("reszlet", reszlet)
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * A futó időzítők végideje. Azért itt, a képernyőn kívül, hogy fülváltás vagy
 * az app háttérbe kerülése ne állítsa meg a visszaszámlálást.
 */
object TimerStore {
    val endAt = mutableStateMapOf<String, Long>()
}
