package meteorbot.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.abs

class SwipeService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private lateinit var params: WindowManager.LayoutParams

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
            text = "Start"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Meteor Bot start or stop"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC2E7D32.toInt()) }
            setOnTouchListener(DragListener())
        }
        wm.addView(button, params)
    }

    private fun onButtonTap() {
        // Probe build: the button does nothing yet.
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : android.view.View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP ->
                    if (abs(e.rawX - downX) < 10 && abs(e.rawY - downY) < 10) { v.performClick(); onButtonTap() }
            }
            return true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        button?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
