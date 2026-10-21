package com.movieroulettetuesday.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.RouletteCategory
import com.movieroulettetuesday.app.catalog.displayHex
import com.movieroulettetuesday.app.episodes.PodcastEpisode
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pushes the latest episode and last spin to the widget (iOS `WidgetBridge`). Best-effort: a widget
 * problem never affects the app. [refresh] is replaceable in tests.
 */
class WidgetBridge(
    private val store: WidgetSnapshotStore,
    private val refresh: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun publishLatestEpisode(episodes: List<PodcastEpisode>) {
        val latest = WidgetSnapshot.latest(episodes) ?: return
        if (store.update(clock()) { it.copy(latestEpisode = WidgetSnapshot.EpisodeInfo.of(latest)) }) refresh()
    }

    fun publishSpin(movie: Movie, category: RouletteCategory, at: Long = clock()) {
        val info = WidgetSnapshot.SpinInfo(movie.id, movie.title, movie.year, category.displayName, category.displayHex, at)
        if (store.update(clock()) { it.copy(lastSpin = info) }) refresh()
    }

    /** A refreshed movie list was cached; let the widget re-read it. */
    fun catalogUpdated() = refresh()

    companion object {
        fun create(context: Context): WidgetBridge {
            val app = context.applicationContext
            return WidgetBridge(WidgetSnapshotStore(File(app.filesDir, WidgetSnapshotStore.FILE_NAME)), refresh = { SpinOfTheDayWidget.refreshAll(app) })
        }
    }
}

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Re-renders every placed widget (no-op when none are placed). */
fun SpinOfTheDayWidget.Companion.refreshAll(context: Context) {
    widgetScope.launch {
        runCatching {
            if (GlanceAppWidgetManager(context).getGlanceIds(SpinOfTheDayWidget::class.java).isNotEmpty()) SpinOfTheDayWidget().updateAll(context)
        }
    }
}

class SpinOfTheDayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SpinOfTheDayWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetMidnightRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetMidnightRefresh.cancel(context)
    }
}

/** Midnight brings a new Spin of the Day (iOS timeline policy `.after(nextRefresh)`). */
object WidgetMidnightRefresh {
    const val UNIQUE_NAME = "widget-midnight-refresh"

    fun schedule(context: Context, now: Long = System.currentTimeMillis()) {
        val delay = SpinOfTheDay.nextRefresh(now) - now + 5_000 // a little past midnight
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { SpinOfTheDayWidget().updateAll(applicationContext) }
        WidgetMidnightRefresh.schedule(applicationContext)
        return Result.success()
    }
}
