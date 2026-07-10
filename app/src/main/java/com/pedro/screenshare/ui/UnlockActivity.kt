package com.pedro.screenshare.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pedro.screenshare.R
import com.pedro.screenshare.data.LocalConfigManager
import com.pedro.screenshare.data.UserRole
import com.pedro.screenshare.utils.Constants

class UnlockActivity : AppCompatActivity() {

    private lateinit var localConfigManager: LocalConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        localConfigManager = LocalConfigManager(applicationContext)

        val editPassword = findViewById<EditText>(R.id.editUnlockPassword)
        val buttonUnlock = findViewById<Button>(R.id.buttonUnlock)
        val textError = findViewById<TextView>(R.id.textUnlockError)

        buttonUnlock.setOnClickListener {
            if (editPassword.text.toString().trim() != Constants.SETUP_PASSWORD) {
                textError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val role = localConfigManager.getRole()
            if (role == null) {
                startActivity(Intent(this, SetupActivity::class.java))
            } else {
                startActivity(Intent(this, nextActivityClass(role)))
            }
            finish()
        }
    }

    private fun nextActivityClass(role: UserRole): Class<out AppCompatActivity> {
        return when (role) {
            UserRole.TRANSMITTER -> TransmitterActivity::class.java
            UserRole.VIEWER -> ViewerActivity::class.java
        }
    }
}
