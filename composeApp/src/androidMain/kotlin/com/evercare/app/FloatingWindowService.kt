package com.evercare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.evercare.app.ui.EverCareTheme
import com.evercare.app.ui.FloatingBubble
import com.evercare.app.ui.FloatingPanel
import kotlin.math.abs

/**
 * Hosts the floating helper.
 *
 * This is the product: a round button that stays on top of every other app, and
 * a panel of three large buttons behind it. The main activity exists only to set
 * this up.
 *
 * It runs as a foreground service because an overlay whose process can be killed
 * at any moment is worse than no overlay -- the user would find the helper simply
 * gone, with no way to understand why.
 */
class FloatingWindowService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var rootView: ComposeView? = null
    private var controller: AssistantController? = null

    // Compose refuses to attach to a view that has no lifecycle, view-model store
    // or saved-state registry owner. An Activity provides all three; a Service
    // does not, so the service plays that role itself.
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())

        val graph = EverCareApplication.graphOf(this)
        controller = AssistantController(
            scope = lifecycleScope,
            assistant = graph.assistant,
            speaker = graph.newSpeaker(),
            speechInput = graph.newSpeechInput(),
            screenReader = graph.screenTextReader,
            localeProvider = graph.localeProvider,
        )

        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Restarted if the system kills us: the helper is supposed to always be
        // there, and a user who cannot find it cannot bring it back themselves.
        return START_STICKY
    }

    override fun onDestroy() {
        controller?.dispose()
        controller = null
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
            view.disposeComposition()
        }
        rootView = null
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun addOverlay() {
        val assistantController = controller ?: return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // Not focusable, so typing in the app underneath still works; the
            // helper must never steal the keyboard.
            //
            // Deliberately without FLAG_LAYOUT_NO_LIMITS: that would let the
            // bubble be dragged under the status bar or a display cutout, where
            // an elderly user cannot get it back.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 320
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)

            setContent {
                var expanded by remember { mutableStateOf(false) }
                val state by assistantController.state.collectAsState()
                val strings = assistantController.strings

                EverCareTheme {
                    if (expanded) {
                        FloatingPanel(
                            state = state,
                            strings = strings,
                            onReadScreen = { assistantController.readScreenAloud() },
                            onExplainScreen = { assistantController.explainScreen() },
                            onAskQuestion = { assistantController.askByVoice() },
                            onStop = { assistantController.stop() },
                            onClose = {
                                assistantController.stop()
                                expanded = false
                            },
                        )
                    } else {
                        FloatingBubble(
                            strings = strings,
                            isBusy = state.phase != AssistantUiState.Phase.Idle,
                            onClick = {
                                assistantController.refreshCapabilities()
                                expanded = true
                            },
                        )
                    }
                }
            }
        }

        composeView.setOnTouchListener(DragToMoveListener(windowManager, layoutParams))

        windowManager.addView(composeView, layoutParams)
        rootView = composeView
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.helper_notification_channel),
                    // Low: the helper is always running, and a noisy permanent
                    // notification would be a daily irritation.
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.helper_notification_title))
            .setContentText(getString(R.string.helper_notification_body))
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "evercare-helper"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.evercare.app.STOP_HELPER"

        /** Whether the user has granted the "draw over other apps" permission. */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingWindowService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

private fun overlayWindowType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

/**
 * Lets the user drag the helper out of the way.
 *
 * A tap is distinguished from a drag by distance rather than time: older users
 * often hold a "tap" for a second or more, and a time-based test would read that
 * as a long press and swallow it.
 */
private class DragToMoveListener(
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                touchX = event.rawX
                touchY = event.rawY
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                if (!dragging && (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP)) {
                    dragging = true
                }
                if (dragging) {
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Returning true here would eat the click; returning `dragging`
                // consumes the gesture only when it really was a drag.
                return dragging
            }
        }
        return false
    }

    private companion object {
        const val TOUCH_SLOP = 16f
    }
}
