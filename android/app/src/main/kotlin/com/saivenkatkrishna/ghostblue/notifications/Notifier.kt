package com.saivenkatkrishna.ghostblue.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.saivenkatkrishna.ghostblue.MainActivity
import com.saivenkatkrishna.ghostblue.R
import com.saivenkatkrishna.ghostblue.data.account.AccountAlertSink
import com.saivenkatkrishna.ghostblue.data.model.BBAccount
import com.saivenkatkrishna.ghostblue.data.vehicle.VehicleAlertSink
import com.saivenkatkrishna.ghostblue.settings.AppSettings
import java.time.Instant
import java.time.ZoneId

/** The notification channels, one per kind so the owner can tune each in system settings. */
enum class Channel(val id: String, val title: String, val description: String, val importance: Int) {
    ALERTS("vehicle_alerts", "Vehicle alerts", "Charging finished or interrupted, unlocked, open doors, low batteries, tyre pressure.", NotificationManager.IMPORTANCE_HIGH),
    REMINDERS("reminders", "Reminders", "Maintenance due and your precondition reminders.", NotificationManager.IMPORTANCE_DEFAULT),
    SMART_CHARGING("smart_charging", "Smart charging", "What each scheduled charging check did.", NotificationManager.IMPORTANCE_LOW),
    COMMANDS("remote_commands", "Remote commands", "Progress while a command from a widget or tile reaches the car.", NotificationManager.IMPORTANCE_LOW),
    CHARGING("charging_progress", "Charging progress", "The car's charge while it charges.", NotificationManager.IMPORTANCE_LOW),
}

/**
 * Posts GhostBlue's notifications, the counterpart of iOS's
 * `UNUserNotificationCenter` use. Everything respects the owner's switch
 * and Android's permission; quiet hours drop vehicle alerts (status rules,
 * automations, odometer maintenance) as on iOS, not reminders the owner
 * scheduled. Tapping one opens the car.
 */
class Notifier(private val context: Context, private val settings: AppSettings) : VehicleAlertSink, AccountAlertSink {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java)
        Channel.entries.forEach { channel ->
            system.createNotificationChannel(
                NotificationChannel(channel.id, channel.title, channel.importance).apply { description = channel.description },
            )
        }
    }

    /** Whether Android lets the app post at all (the permission on 13+, the app-wide block before). */
    val canPost: Boolean
        get() = manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Whether a notification posted now would be shown; [quiet] ones are also held back in quiet hours. */
    fun allows(quiet: Boolean = false, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
        settings.notificationsEnabled.value && canPost && !(quiet && settings.isQuietHour(now.atZone(zone).hour))

    /** The data layer's alerts: status rules, automations and odometer-due maintenance. */
    override fun post(vin: String, title: String, body: String) {
        val channel = if (title == "Maintenance due") Channel.REMINDERS else Channel.ALERTS
        postIfAllowed(channel, vin, title, body, tag = "$vin:$title", quiet = true)
    }

    /** Not held back in quiet hours: nothing refreshes until the owner acts. */
    override fun loginRejected(account: BBAccount) {
        val brand = account.brandEnum.displayName
        postIfAllowed(
            Channel.ALERTS,
            vin = null,
            title = "Sign in to $brand again",
            body = "$brand refused the saved password, so GhostBlue has stopped signing in. Update it in Settings › Accounts.",
            tag = "account:${account.id}",
            quiet = false,
        )
    }

    fun postReminder(vin: String, title: String, body: String, tag: String) =
        postIfAllowed(Channel.REMINDERS, vin, title, body, tag, quiet = false)

    /** New on Android: iOS shows smart charging's result only in the Shortcuts dialog. */
    fun postSmartCharging(vin: String, title: String, body: String) =
        postIfAllowed(Channel.SMART_CHARGING, vin, title, body, tag = "$vin:smart", quiet = true)

    private fun postIfAllowed(channel: Channel, vin: String?, title: String, body: String, tag: String, quiet: Boolean) {
        if (!allows(quiet)) return
        notify(tag, notification(channel, vin, title, body).build())
    }

    fun notification(channel: Channel, vin: String?, title: String, body: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, vin))
            .setCategory(if (channel == Channel.ALERTS) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)

    @SuppressLint("MissingPermission")
    fun notify(tag: String, notification: android.app.Notification, id: Int = ALERT_ID) {
        if (!canPost) return
        manager.notify(tag, id, notification)
    }

    fun cancel(tag: String, id: Int) = manager.cancel(tag, id)

    companion object {
        const val ALERT_ID = 1
        const val COMMAND_ID = 2
        const val CHARGING_ID = 3
        const val SMART_CHARGING_RUN_ID = 4

        /** Opens GhostBlue on [vin]'s Home. */
        fun openApp(context: Context, vin: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_VEHICLE)
                .putExtra(MainActivity.EXTRA_VIN, vin)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                vin?.hashCode() ?: 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
