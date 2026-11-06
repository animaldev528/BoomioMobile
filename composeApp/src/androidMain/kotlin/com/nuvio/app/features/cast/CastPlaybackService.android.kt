package com.nuvio.app.features.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nuvio.app.R
import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "CastPlaybackFg"
private const val CAST_CHANNEL_ID = "nuvio_casting"
private const val CAST_NOTIFICATION_ID = 0x4341
private const val ACTION_START = "com.nuvio.app.cast.START"
private const val ACTION_STOP = "com.nuvio.app.cast.STOP"
private const val EXTRA_DEVICE = "device"
private const val EXTRA_TITLE = "title"

/**
 * Foreground-service host for a cast in progress.
 *
 * ⛔ The AirPlay sender is a *paired socket held by this process*: when the app
 * leaves the foreground Android freezes the process, the socket stops being
 * serviced, and the receiver ends playback — measured 2026-11-03: switching to
 * another app stopped the Apple TV mid-film. A media-playback foreground
 * service is the platform's answer: the process keeps running (and keeps
 * servicing the session) while the viewer uses another app, and the
 * notification gives them the way back and a Stop that ends the cast.
 *
 * Mirrors [com.nuvio.app.features.boomio.PrivateListeningService]: only wired in
 * the `full`/boomio flavor, where
 * [AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled] is true and the
 * manifest declares the service and the FGS permission.
 */
class CastPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification(
                    context = this,
                    deviceName = intent.getStringExtra(EXTRA_DEVICE).orEmpty(),
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                )
                val started = runCatching {
                    startForeground(CAST_NOTIFICATION_ID, notification)
                }.onFailure { error ->
                    Log.w(TAG, "Unable to promote cast service", error)
                }.isSuccess
                if (!started) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                CastPlaybackForeground.markActive()
                return START_STICKY
            }
            ACTION_STOP -> {
                // Notification Stop action — end the cast, then drop the service.
                CastPlaybackForeground.stopFromNotification()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java)?.cancel(CAST_NOTIFICATION_ID)
        CastPlaybackForeground.markInactive()
        super.onDestroy()
    }
}

/**
 * Bridge between the cast session and the foreground service.
 *
 * `start` is called when a cast is accepted (the app is necessarily in the
 * foreground then — Android 12+ refuses a background FGS start), and `stop`
 * when playback ends or the viewer stops it. Both are safe to call repeatedly.
 */
internal object CastPlaybackForeground {

    @Volatile
    private var active = false

    fun start(context: Context, deviceName: String, title: String) {
        if (!AppFeaturePolicy.mediaPlaybackForegroundServiceEnabled) return
        val ctx = context.applicationContext
        createChannel(ctx)
        val intent = Intent(ctx, CastPlaybackService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_DEVICE, deviceName)
            .putExtra(EXTRA_TITLE, title)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to start cast service", error)
        }
    }

    /** Refresh the notification's device/title without restarting the service. */
    fun update(context: Context, deviceName: String, title: String) {
        if (!active) return
        start(context, deviceName, title)
    }

    fun stop(context: Context) {
        val ctx = context.applicationContext
        runCatching {
            ctx.stopService(Intent(ctx, CastPlaybackService::class.java))
        }.onFailure { error ->
            Log.w(TAG, "Unable to stop cast service", error)
        }
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(CAST_NOTIFICATION_ID)
        }.onFailure { /* manager gone with the process */ }
        active = false
    }

    fun markActive() {
        active = true
    }

    fun markInactive() {
        active = false
    }

    /**
     * The notification's Stop action. Runs off the main thread because ending a
     * cast is a protocol exchange with the receiver.
     */
    fun stopFromNotification() {
        val context = CastAndroidContext.context ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { CastRemote.stopPlayback() }
        }
        stop(context)
    }
}

private fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
        CAST_CHANNEL_ID,
        "Casting",
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = "A stream is playing on a television from this phone"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
}

private fun buildNotification(
    context: Context,
    deviceName: String,
    title: String,
): Notification {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val stopIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, CastPlaybackService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, CAST_CHANNEL_ID)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }
    return builder
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(if (title.isNotBlank()) title else "Casting")
        .setContentText(
            if (deviceName.isNotBlank()) "Playing on $deviceName" else "Playing on your TV",
        )
        .setContentIntent(
            launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            },
        )
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent,
            ).build(),
        )
        .build()
}
