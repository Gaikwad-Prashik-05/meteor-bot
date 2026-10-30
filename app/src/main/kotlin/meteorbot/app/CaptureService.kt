package meteorbot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.random.Random
import meteorbot.core.RunController
import meteorbot.core.rgbaToArgb

/** Captures the screen at 1/3 resolution and feeds every frame to a [RunController]. */
class CaptureService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile var running = false
        private set
    private val stopping = AtomicBoolean(false)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (running || data == null) return START_NOT_STICKY
        stopping.set(false)

        // Must be in the foreground with the mediaProjection type before getMediaProjection (API 34+).
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Meteor Bot", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Meteor Bot is playing")
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, note)

        val t = HandlerThread("capture").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        this.handler = handler
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (mp == null) { stopRun(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopRun()
            // "Share one app" capture: once that app is hidden the frames stop, so end the run here.
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) { if (!isVisible) stopRun() }
        }, handler)

        val (sw, sh, dpi) = screen()
        val w = sw / 3; val h = sh / 3
        val controller = RunController(Random.Default, { s -> Bot.swipe?.perform(s) }, ::stopRun, { Bot.swipe?.cancelAll() })
        Bot.swipe?.buttonBox()?.let { controller.masks = listOf(it) }
        Bot.controller = controller
        val pixels = IntArray(w * h)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                rgbaToArgb(plane.buffer, w, h, plane.rowStride, plane.pixelStride, pixels)
                val now = SystemClock.uptimeMillis()
                var frameMs = img.timestamp / 1_000_000
                // shortcut: if a device stamps frames on another clock, treat them as fresh; revisit if aim is off on such a phone
                if (abs(now - frameMs) > 10_000) frameMs = now
                controller.onFrame(pixels, w, h, frameMs, now)
            } finally {
                img.close()
            }
        }, handler)
        display = mp.createVirtualDisplay("meteorbot", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
        running = true
        Bot.capture = this
        Bot.swipe?.setRunning(true)
        return START_NOT_STICKY
    }

    private fun screen(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        val wm = getSystemService(android.view.WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    /** Ends the run from any thread: releases capture and resets the overlay to Start. */
    fun stopRun() {
        if (!stopping.compareAndSet(false, true)) return  // projection.stop() calls back into here
        running = false
        Bot.swipe?.cancelAll()
        Bot.controller = null
        Bot.capture = null
        // Tear down on the capture thread, after any frame it is reading; ImageReader is not thread-safe.
        val h = handler
        if (h == null || h.looper == android.os.Looper.myLooper()) teardown() else h.post(::teardown)
    }

    private fun teardown() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        thread?.quitSafely(); thread = null; handler = null
        Bot.swipe?.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopRun()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "run"
    }
}
