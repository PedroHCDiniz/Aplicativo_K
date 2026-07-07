package com.pedro.screenshare.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pedro.screenshare.data.LocalConfigManager
import com.pedro.screenshare.data.UserRole

/**
 * MainActivity
 * ---------------------------------------------------------------------------
 * Tela "invisivel" para o usuario: e a primeira Activity a abrir, mas so
 * decide para onde mandar o usuario e fecha em seguida (nunca mostra nada na
 * tela). E o unico lugar do app que le a configuracao salva para decidir a
 * rota inicial:
 *   - Aparelho ainda nao configurado -> SetupActivity (pedir codigo).
 *   - Configurado como TRANSMISSOR -> TransmitterActivity.
 *   - Configurado como VISUALIZADOR -> ViewerActivity.
 *
 * Isso implementa o requisito de "o usuario nao deve precisar digitar codigo
 * toda vez": a partir da segunda abertura do app, o papel salvo localmente
 * (ver LocalConfigManager) manda direto para a tela certa.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val localConfigManager = LocalConfigManager(applicationContext)

        val nextActivityClass = when (localConfigManager.getRole()) {
            UserRole.TRANSMITTER -> TransmitterActivity::class.java
            UserRole.VIEWER -> ViewerActivity::class.java
            null -> SetupActivity::class.java // aparelho ainda nao configurado
        }

        startActivity(Intent(this, nextActivityClass))
        finish() // MainActivity nunca deve aparecer na pilha de "voltar"
    }
}
