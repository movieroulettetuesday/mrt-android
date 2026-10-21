package com.movieroulettetuesday.app.notifications

import android.Manifest
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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movieroulettetuesday.app.MainActivity
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.R
import java.util.concurrent.TimeUnit

/** Local notifications for new episodes (no FCM, no server). */
class NewEpisodeNotifier(private val context: Context) : EpisodeNotifier {
    fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_new_episodes), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.channel_new_episodes_description)
        channel.setShowBadge(false) // iOS never sets a badge either
        nm.createNotificationChannel(channel)
    }

    override fun notify(text: NewEpisodeDiff.Text, guid: String) {
        if (!canPost()) return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_EPISODE)
            .putExtra(EXTRA_GUID, guid)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, guid.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_episode)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("episode-$guid", NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    companion object {
        const val CHANNEL_ID = "new-episodes"
        const val GROUP = "new-episodes"
        const val NOTIFICATION_ID = 1
        const val ACTION_OPEN_EPISODE = "com.movieroulettetuesday.app.OPEN_EPISODE"
        /** Same name as iOS `guidUserInfoKey`. */
        const val EXTRA_GUID = "episodeGUID"
    }
}

/** Periodic check (iOS BGAppRefreshTask). Android decides when it actually runs; network required. */
class NewEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MrtApplication
        return if (app.newEpisodeChecker.checkNow()) Result.success() else Result.retry()
    }
}

object NewEpisodeSchedule {
    const val UNIQUE_NAME = "new-episode-check"
    /** iOS refreshInterval: 6 h. */
    const val INTERVAL_HOURS = 6L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
