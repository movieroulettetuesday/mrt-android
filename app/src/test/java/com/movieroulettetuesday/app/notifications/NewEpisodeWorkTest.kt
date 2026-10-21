package com.movieroulettetuesday.app.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.movieroulettetuesday.app.MrtApplication
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NewEpisodeWorkTest {
    private val app get() = ApplicationProvider.getApplicationContext<MrtApplication>()

    @Test
    fun notificationOpensEpisode() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NewEpisodeNotifier(app).notify(NewEpisodeDiff.Text("New episode of Movie Roulette Tuesday", "Tremors"), "Buzzsprout-1")
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val n = nm.allNotifications.single()
        assertEquals("New episode of Movie Roulette Tuesday", n.extras.getString("android.title"))
        assertEquals("Tremors", n.extras.getString("android.text"))
        assertEquals("new-episodes", n.channelId)
        val intent: Intent = shadowOf(n.contentIntent).savedIntent
        assertEquals("Buzzsprout-1", intent.getStringExtra(NewEpisodeNotifier.EXTRA_GUID))
    }

    @Test
    fun noPermissionNoNotification() {
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NewEpisodeNotifier(app).notify(NewEpisodeDiff.Text("t", "b"), "g")
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun workerRunsChecker() = runTest {
        app.preferences.setNewEpisodeAlerts(true)
        var calls = 0
        app.newEpisodeChecker = NewEpisodeChecker(app.preferences, app.episodeWatermarks,
            { calls++; listOf(PodcastEpisode(guid = "a", title = "A", pubDate = 1)) }, { _, _ -> })
        val worker = TestListenableWorkerBuilder<NewEpisodeWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, calls)
        app.newEpisodeChecker = NewEpisodeChecker(app.preferences, app.episodeWatermarks, { error("offline") }, { _, _ -> })
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun scheduleIsUniqueSixHourlyAndCancels() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        NewEpisodeSchedule.schedule(app)
        NewEpisodeSchedule.schedule(app)
        val wm = WorkManager.getInstance(app)
        val infos = wm.getWorkInfosForUniqueWork(NewEpisodeSchedule.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertEquals(TimeUnit.HOURS.toMillis(6), infos[0].periodicityInfo!!.repeatIntervalMillis)
        NewEpisodeSchedule.cancel(app)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(NewEpisodeSchedule.UNIQUE_NAME).get()[0].state)
    }
}
