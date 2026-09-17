package com.evercare.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.evercare.app.domain.ScreenSnapshot
import com.evercare.app.domain.ScreenTextReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the text of whatever app is currently in front.
 *
 * This is the only Android API that can see another app's content, which is why
 * the user has to enable it by hand in system settings. The service is
 * deliberately read-only: it never performs gestures or clicks on the user's
 * behalf.
 */
class EverCareAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
    }

    /**
     * Nothing happens here on purpose.
     *
     * Text is pulled on demand, when the user taps a button, rather than being
     * collected as events arrive. Continuously harvesting the screen of every app
     * would be a privacy problem and would burn battery for no benefit.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance.compareAndSet(this, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun captureNow(): ScreenSnapshot {
        val root = rootInActiveWindow ?: return ScreenSnapshot.Empty
        val lines = mutableListOf<String>()
        try {
            collectText(root, lines, depth = 0)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }

        return ScreenSnapshot(
            appLabel = resolveAppLabel(root.packageName?.toString()),
            lines = lines.deduplicateAdjacent(),
        )
    }

    /**
     * Walks the node tree depth-first, which yields text in roughly the order a
     * sighted person reads it.
     *
     * [MAX_DEPTH] and [MAX_LINES] are guards, not tuning: a deeply nested or
     * infinite-scrolling view could otherwise stall the whole capture, and an
     * elderly user tapping "read this" must never be met with a frozen phone.
     */
    private fun collectText(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_LINES) return

        // Skip anything the user cannot see; invisible nodes are full of
        // off-screen list items and hidden menus.
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
        val description = node.contentDescription?.toString()?.trim()

        // Prefer the visible text; fall back to the content description, which is
        // what icon-only buttons carry.
        val value = when {
            !text.isNullOrEmpty() -> text
            !description.isNullOrEmpty() -> description
            else -> null
        }

        if (value != null && !TextUtils.isEmpty(value)) {
            out.add(value)
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            collectText(child, out, depth + 1)
            @Suppress("DEPRECATION")
            child?.recycle()
        }
    }

    private fun resolveAppLabel(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    companion object {
        private const val MAX_DEPTH = 60
        private const val MAX_LINES = 400

        /**
         * The running service, if any. Android constructs accessibility services
         * itself, so this is the only way for the rest of the app to reach it.
         */
        private val instance = AtomicReference<EverCareAccessibilityService?>(null)

        fun isRunning(): Boolean = instance.get() != null

        /**
         * Whether the user has enabled the service in system settings.
         *
         * Checked against settings rather than [isRunning] because the service
         * may be enabled but not yet bound when the setup screen is drawn.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val target = "${context.packageName}/${EverCareAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(target, ignoreCase = true) }
        }

        fun capture(): ScreenSnapshot = instance.get()?.captureNow() ?: ScreenSnapshot.Empty
    }
}

/**
 * Screen text reader backed by [EverCareAccessibilityService].
 */
class AndroidScreenTextReader(private val context: Context) : ScreenTextReader {

    override val availability: ScreenTextReader.Availability
        get() = when {
            EverCareAccessibilityService.isRunning() -> ScreenTextReader.Availability.Ready
            else -> ScreenTextReader.Availability.NeedsPermission
        }

    override suspend fun capture(): ScreenSnapshot = EverCareAccessibilityService.capture()

    /** Exposed so the setup screen can show whether the permission is granted. */
    fun isEnabledInSettings(): Boolean =
        EverCareAccessibilityService.isEnabledInSettings(context)
}

/**
 * Collapses repeated neighbouring lines.
 *
 * View hierarchies commonly repeat a label on a container and again on its
 * child, and hearing the same words twice is confusing when the screen is being
 * read aloud.
 */
private fun List<String>.deduplicateAdjacent(): List<String> {
    val result = mutableListOf<String>()
    for (line in this) {
        if (result.lastOrNull() != line) result.add(line)
    }
    return result
}
