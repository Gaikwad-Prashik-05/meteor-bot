package meteorbot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.abs
import meteorbot.core.Box
import meteorbot.core.Stroke

/** Hosts the floating Start/Stop button and performs the planner's strokes as real swipes. */
class SwipeService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()
    // Main thread only. Bumped on every new stroke and cancel, so stale swipes and callbacks do nothing.
    private var generation = 0
    // App in front, from window-change events (no screen content is read). The run is locked to the
    // app in front at its first swipe; any other app or the notification shade stops it.
    @Volatile private var foreground: CharSequence? = null
    private var runPackage: CharSequence? = null
    private var stats: TextView? = null
    private var done = 0
    private var cancelled = 0
    private var lastFrames = 0

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (56 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = size * 4 }
        button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Meteor Bot start or stop"
            setOnTouchListener(DragListener())
        }
        wm.addView(button, params)
        stats = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
        }
        wm.addView(stats, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = 0; y = 0 })
        Bot.swipe = this
        setRunning(Bot.capture?.running == true)
    }

    fun setRunning(running: Boolean) = main.post {
        if (running) { runPackage = null; done = 0; cancelled = 0; lastFrames = 0; main.post(::showStats) }
        button?.apply {
            text = if (running) "Stop" else "Start"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (running) 0xCCC62828.toInt() else 0xCC2E7D32.toInt())
            }
        }
    }

    /** One line at the bottom-left while running, e.g. "fps 28 lag 41 det 9 | sent 30 ok 27 x 1 abort 0 | grey 0.04". */
    private fun showStats() {
        val c = Bot.controller; val cap = Bot.capture
        val v = stats ?: return
        if (c == null || cap == null || !cap.running) return  // keep the last run's numbers on screen
        v.visibility = View.VISIBLE
        v.text = "fps %d lag %d det %d stale %d | sent %d ok %d x %d abort %d miss %d | grey %.2f".format(
            (c.frames - lastFrames) * 2, cap.lagMs, cap.detectMs, c.stale, c.strokes, done, cancelled, c.aborts, c.tracker.missed, c.lastGrey)
        lastFrames = c.frames
        main.postDelayed(::showStats, 500)
    }

    private fun onButtonTap() {
        val capture = Bot.capture
        if (capture != null && capture.running) capture.stopRun()
        else startActivity(Intent(this, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SetupActivity.EXTRA_START, true))
    }

    /** The button's screen area in width units, so detection and strokes ignore it. */
    fun buttonBox(): Box? {
        val b = button ?: return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        val w = screenWidth().toFloat()
        val pad = b.width * 0.25f
        return Box((loc[0] - pad) / w, (loc[1] - pad) / w, (loc[0] + b.width + pad) / w, (loc[1] + b.height + pad) / w)
    }

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.width()
        else android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }.widthPixels

    /** Runs [s] at its touch time, replacing anything queued; segments chain into one finger movement. */
    fun perform(s: Stroke) {
        val latency = Bot.controller?.planner?.latencyMs ?: 60L
        main.post {
            val gen = ++generation
            main.removeCallbacksAndMessages(token)
            main.postAtTime({ dispatchSegment(s, 0, null, gen) }, token, s.startMs - latency)
        }
    }

    /** Drops queued swipes and stops chaining the current one. */
    fun cancelAll() {
        main.post { generation++; main.removeCallbacksAndMessages(token) }
    }

    private fun dispatchSegment(s: Stroke, i: Int, prev: GestureDescription.StrokeDescription?, gen: Int) {
        if (gen != generation) return
        val fg = foreground
        if (runPackage == null && fg != null && fg !in SYSTEM_UI) runPackage = fg
        if (runPackage != null && fg != runPackage) { generation++; Bot.capture?.stopRun(); return }
        val seg = s.segments[i]
        val k = screenWidth().toFloat()
        val path = Path().apply {
            moveTo(seg.a.x * k, seg.a.y * k)
            if (seg.a != seg.b) quadTo(seg.mid.x * k, seg.mid.y * k, seg.b.x * k, seg.b.y * k)
        }
        val more = i < s.segments.size - 1
        val d = seg.durationMs.coerceAtLeast(1)
        val desc = prev?.continueStroke(path, 0, d, more) ?: GestureDescription.StrokeDescription(path, 0, d, more)
        dispatchGesture(GestureDescription.Builder().addStroke(desc).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { if (more) dispatchSegment(s, i + 1, desc, gen) else done++ }
            override fun onCancelled(g: GestureDescription) {
                cancelled++
                if (gen == generation) Bot.controller?.strokeBusyUntilMs = 0
            }
        }, main)
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < 10 && abs(e.rawY - downY) < 10) { v.performClick(); onButtonTap() }
                    else v.post { buttonBox()?.let { box -> Bot.controller?.masks = listOf(box) } }
                }
            }
            return true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName ?: return
        if (pkg == packageName) return
        foreground = pkg
        val run = runPackage
        if (run != null && pkg != run && Bot.capture?.running == true) { cancelAll(); Bot.capture?.stopRun() }
    }
    override fun onInterrupt() {}

    companion object {
        // Packages that own the capture-consent dialog; never lock a run to these.
        private val SYSTEM_UI = setOf("com.android.systemui", "android")
    }

    override fun onDestroy() {
        Bot.capture?.stopRun()
        Bot.swipe = null
        button?.let { wm.removeView(it) }
        stats?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
