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

/**
 * SetupActivity
 * ---------------------------------------------------------------------------
 * Tela de PRIMEIRA CONFIGURACAO do aparelho. So aparece uma unica vez (na
 * primeira abertura do app), ou se o usuario clicar em "Redefinir
 * configuração" nas telas de transmissor/visualizador.
 *
 * O usuario digita um dos dois codigos fixos (ver Constants.kt) e a senha:
 *   - Transmitir + senha correta -> configura o aparelho como TRANSMISSOR
 *   - visualizar + senha correta -> configura o aparelho como VISUALIZADOR
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
        val editPassword = findViewById<EditText>(R.id.editPassword)
        val buttonConfirm = findViewById<Button>(R.id.buttonConfirm)
        val textError = findViewById<TextView>(R.id.textSetupError)

        buttonConfirm.setOnClickListener {
            val typedCode = editCode.text.toString().trim()
            val typedPassword = editPassword.text.toString().trim()
            val role = matchCodeToRole(typedCode)

            if (role == null || typedPassword != Constants.SETUP_PASSWORD) {
                textError.text = getString(R.string.setup_invalid_credentials)
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
            Constants.TRANSMITTER_CODE -> UserRole.TRANSMITTER
            Constants.VIEWER_CODE -> UserRole.VIEWER
            else -> when {
                typedCode.equals(Constants.TRANSMITTER_CODE, ignoreCase = true) -> UserRole.TRANSMITTER
                typedCode.equals(Constants.VIEWER_CODE, ignoreCase = true) -> UserRole.VIEWER
                else -> null
            }
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
