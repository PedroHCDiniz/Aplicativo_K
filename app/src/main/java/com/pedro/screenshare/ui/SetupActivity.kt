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

/**
 * SetupActivity
 * ---------------------------------------------------------------------------
 * Tela de PRIMEIRA CONFIGURACAO do aparelho. So aparece uma unica vez (na
 * primeira abertura do app), ou se o usuario clicar em "Redefinir
 * configuração" nas telas de transmissor/visualizador.
 *
 * O usuario digita um dos dois codigos fixos (ver Constants.kt):
 *   - PEDRO-SEND-2026 -> configura o aparelho como TRANSMISSOR
 *   - PEDRO-VIEW-2026 -> configura o aparelho como VISUALIZADOR
 *
 * Depois de salvo (ver LocalConfigManager.saveRole), o app NUNCA MAIS mostra
 * esta tela sozinho - ela so volta a aparecer se o usuario pedir para
 * "esquecer" a configuracao.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var localConfigManager: LocalConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        localConfigManager = LocalConfigManager(applicationContext)

        val editCode = findViewById<EditText>(R.id.editCode)
        val buttonConfirm = findViewById<Button>(R.id.buttonConfirm)
        val textError = findViewById<TextView>(R.id.textSetupError)

        buttonConfirm.setOnClickListener {
            val typedCode = editCode.text.toString().trim()
            val role = matchCodeToRole(typedCode)

            if (role == null) {
                textError.text = getString(R.string.setup_invalid_code)
                textError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            localConfigManager.saveRole(role)
            goToRoleScreen(role)
        }
    }

    /** Compara o texto digitado com os dois codigos fixos de configuracao. */
    private fun matchCodeToRole(typedCode: String): UserRole? {
        return when (typedCode) {
            com.pedro.screenshare.utils.Constants.TRANSMITTER_CODE -> UserRole.TRANSMITTER
            com.pedro.screenshare.utils.Constants.VIEWER_CODE -> UserRole.VIEWER
            else -> null
        }
    }

    /** Leva o usuario para a tela correspondente ao papel recem-configurado. */
    private fun goToRoleScreen(role: UserRole) {
        val nextActivityClass = when (role) {
            UserRole.TRANSMITTER -> TransmitterActivity::class.java
            UserRole.VIEWER -> ViewerActivity::class.java
        }
        startActivity(Intent(this, nextActivityClass))
        finish() // impede o usuario de "voltar" para a tela de configuracao com o botao Voltar
    }
}
