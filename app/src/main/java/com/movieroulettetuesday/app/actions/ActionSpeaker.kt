package com.movieroulettetuesday.app.actions

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.movieroulettetuesday.app.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaks action results inside the app after the destination is on screen. Skips TTS when TalkBack
 * is already reading, so the two voices do not overlap. Falls back to a local notification when TTS
 * is missing (or when we need to report something with no visible screen).
 */
class ActionSpeaker(
    private val talkBackOn: () -> Boolean,
    private val ttsReady: () -> Boolean,
    private val speak: (String) -> Unit,
    private val notify: (title: String, text: String) -> Unit,
    private val notificationTitle: String = "Movie Roulette",
) {
    /**
     * @param screenVisible true when the destination UI is on screen (TalkBack will read it).
     */
    fun deliver(text: String, screenVisible: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        when {
            talkBackOn() && screenVisible -> Unit
            ttsReady() && !talkBackOn() -> speak(trimmed)
            else -> notify(notificationTitle, trimmed)
        }
    }

    companion object {
        const val CHANNEL_ID = "action-results"
        const val NOTIFICATION_ID = 1101

        fun talkBackIsOn(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            if (!am.isEnabled) return false
            // Touch exploration ≈ TalkBack / Voice Assistant actually reading the screen.
            if (am.isTouchExplorationEnabled) return true
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
                && am.isTouchExplorationEnabled
        }

        fun create(context: Context): PlatformActionSpeaker = PlatformActionSpeaker(context.applicationContext)
    }
}

/** Process-lifetime TTS + notification implementation. Safe to construct on a background-free main thread. */
class PlatformActionSpeaker(private val context: Context) {
    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private val speaker: ActionSpeaker

    init {
        tts = TextToSpeech(context) { status ->
            ready.set(status == TextToSpeech.SUCCESS)
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
        speaker = ActionSpeaker(
            talkBackOn = { ActionSpeaker.talkBackIsOn(context) },
            ttsReady = { ready.get() },
            speak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mrt-action") },
            notify = { title, text -> post(title, text) },
        )
    }

    fun deliver(text: String, screenVisible: Boolean) = speaker.deliver(text, screenVisible)

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private fun post(title: String, text: String) {
        ensureChannel()
        val appContext = context.applicationContext
        val n = NotificationCompat.Builder(appContext, ActionSpeaker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_new_episode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(ActionSpeaker.NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: the on-screen UI is the remaining fallback.
        }
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(ActionSpeaker.CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                ActionSpeaker.CHANNEL_ID,
                context.getString(R.string.channel_action_results),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_action_results_description) },
        )
    }
}
