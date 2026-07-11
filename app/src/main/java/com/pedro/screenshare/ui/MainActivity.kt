package com.pedro.screenshare.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.pedro.screenshare.R
import com.pedro.screenshare.data.LocalConfigManager
import com.pedro.screenshare.data.UserRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela fake inicial. Ela mostra informacoes simples do aparelho (bateria,
 * data e hora) e so libera a entrada real do app depois de 4 toques rapidos
 * na palavra "Android".
 *
 * Importante: a transmissao em segundo plano nao depende desta Activity; ela
 * continua sendo mantida por TransmitterSession + ScreenCaptureService.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUIRED_TAPS = 4
        private const val TAP_WINDOW_MS = 1_800L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateDeviceInfo()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private lateinit var textFakeDate: TextView
    private lateinit var textFakeTime: TextView
    private lateinit var textFakeBattery: TextView

    private var tapCount = 0
    private var lastTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textFakeDate = findViewById(R.id.textFakeDate)
        textFakeTime = findViewById(R.id.textFakeTime)
        textFakeBattery = findViewById(R.id.textFakeBattery)

        findViewById<View>(R.id.textFakeAndroidWord).setOnClickListener {
            handleHiddenTap()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        mainHandler.post(clockRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    private fun handleHiddenTap() {
        val now = SystemClock.elapsedRealtime()
        tapCount = if (now - lastTapAt <= TAP_WINDOW_MS) tapCount + 1 else 1
        lastTapAt = now

        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            openSavedRole()
        }
    }

    private fun updateDeviceInfo() {
        val now = Date()
        textFakeDate.text = dateFormatter.format(now)
        textFakeTime.text = timeFormatter.format(now)
        textFakeBattery.text = readBatteryLevel()
    }

    private fun readBatteryLevel(): String {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "--%"

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "--%"

        val percent = (level * 100) / scale
        return "$percent%"
    }

    private fun openSavedRole() {
        val localConfigManager = LocalConfigManager(applicationContext)
        val nextActivityClass = when (localConfigManager.getRole()) {
            UserRole.TRANSMITTER -> TransmitterActivity::class.java
            UserRole.VIEWER -> ViewerActivity::class.java
            null -> SetupActivity::class.java
        }
        startActivity(Intent(this, nextActivityClass))
        finish()
    }
}
