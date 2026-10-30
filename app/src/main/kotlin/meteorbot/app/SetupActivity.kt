package meteorbot.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Launcher screen; also asks for screen-capture consent when the overlay's Start is tapped. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SetupActivity).apply { setText(R.string.setup_text) })
            addView(Button(this@SetupActivity).apply {
                setText(R.string.open_accessibility)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        })
        if (intent.getBooleanExtra(EXTRA_START, false)) start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) start()
    }

    private fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else askCapture()
    }

    // The capture notification still works without this permission, so continue either way.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_NOTIFY) askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Activity result API needs AndroidX; this app has no dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data))
        }
        moveTaskToBack(true)
    }

    companion object {
        const val EXTRA_START = "start"
        private const val REQ_NOTIFY = 1
        private const val REQ_CAPTURE = 2
    }
}
