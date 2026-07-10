package com.pedro.screenshare.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pedro.screenshare.R
import com.pedro.screenshare.data.LocalConfigManager
import com.pedro.screenshare.data.UserRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity
 * ---------------------------------------------------------------------------
 * Tela inicial do app. Ela mostra informacoes simples do aparelho (data,
 * hora e bateria). Depois de 3 toques seguidos na tela, libera a rota interna:
 *   - Aparelho ainda nao configurado -> SetupActivity (pedir codigo).
 *   - Configurado como TRANSMISSOR -> TransmitterActivity.
 *   - Configurado como VISUALIZADOR -> ViewerActivity.
 *
 * O papel salvo localmente continua sendo respeitado; a tela inicial so atua
 * como uma entrada discreta antes dos menus do app.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUIRED_TAPS = 3
        private const val TAP_WINDOW_MS = 1_500L
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

    private lateinit var textHomeDate: TextView
    private lateinit var textHomeTime: TextView
    private lateinit var textHomeBattery: TextView

    private var tapCount = 0
    private var lastTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textHomeDate = findViewById(R.id.textHomeDate)
        textHomeTime = findViewById(R.id.textHomeTime)
        textHomeBattery = findViewById(R.id.textHomeBattery)

        findViewById<android.view.View>(R.id.mainRoot).setOnClickListener {
            handleHiddenTap()
        }
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
        textHomeDate.text = dateFormatter.format(now)
        textHomeTime.text = timeFormatter.format(now)
        textHomeBattery.text = readBatteryLevel()
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
