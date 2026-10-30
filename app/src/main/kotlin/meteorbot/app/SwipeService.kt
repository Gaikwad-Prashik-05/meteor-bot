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
        Bot.swipe = this
        setRunning(Bot.capture?.running == true)
    }

    fun setRunning(running: Boolean) = main.post {
        button?.apply {
            text = if (running) "Stop" else "Start"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (running) 0xCCC62828.toInt() else 0xCC2E7D32.toInt())
            }
        }
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

    /** Runs [s] at its touch time; segments are chained into one continuous finger movement. */
    fun perform(s: Stroke) {
        val latency = Bot.controller?.planner?.latencyMs ?: 60L
        main.postAtTime({ dispatchSegment(s, 0, null) }, s.startMs - latency)
    }

    private fun dispatchSegment(s: Stroke, i: Int, prev: GestureDescription.StrokeDescription?) {
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
            override fun onCompleted(g: GestureDescription) { if (more) dispatchSegment(s, i + 1, desc) }
            override fun onCancelled(g: GestureDescription) { Bot.controller?.strokeBusyUntilMs = 0 }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        Bot.capture?.stopRun()
        Bot.swipe = null
        button?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
