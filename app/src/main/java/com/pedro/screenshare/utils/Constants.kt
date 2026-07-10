package com.pedro.screenshare.utils

import com.pedro.screenshare.BuildConfig
import org.webrtc.PeerConnection

/**
 * Constants
 * ---------------------------------------------------------------------------
 * Todos os valores fixos do app ficam centralizados aqui. Se algum dia for
 * preciso mudar o codigo de configuracao, o IP do servidor, etc, este e o
 * UNICO arquivo que precisa ser editado.
 */
object Constants {

    // -------------------------------------------------------------------
    // Sala fixa
    // -------------------------------------------------------------------
    // O app inteiro usa APENAS esta sala. Nao existe tela para criar ou
    // digitar um id de sala diferente - isso e proposital (ver pedido do
    // projeto: "sala fixa", sem gerar codigo novo a cada uso).
    const val ROOM_ID = "sala-pedro-principal"

    // -------------------------------------------------------------------
    // Codigos de primeira configuracao
    // -------------------------------------------------------------------
    // Digitados apenas UMA VEZ, na primeira abertura do app (SetupActivity).
    // Depois disso o papel do aparelho fica salvo localmente (ver
    // data/LocalConfigManager.kt) e o usuario nunca mais precisa digitar nada.
    const val TRANSMITTER_CODE = "Transmitir"
    const val VIEWER_CODE = "visualizar"
    const val SETUP_PASSWORD = "123"
    const val CURRENT_CONFIG_VERSION = 2

    // -------------------------------------------------------------------
    // Servidor de sinalizacao (WebSocket)
    // -------------------------------------------------------------------
    // Valor vem do Gradle/local.properties:
    //   - teste local: ws://192.168.18.12:3000
    //   - producao: wss://seu-dominio.com
    val SIGNALING_SERVER_URL: String = BuildConfig.SIGNALING_SERVER_URL

    // Token simples para proteger o servidor WebSocket publico. Para um app
    // comercial, isso deve evoluir para login/autenticacao de verdade.
    val SIGNALING_AUTH_TOKEN: String = BuildConfig.SIGNALING_AUTH_TOKEN

    // -------------------------------------------------------------------
    // Armazenamento local seguro (EncryptedSharedPreferences)
    // -------------------------------------------------------------------
    const val PREFS_FILE_NAME = "app_secure_prefs"
    const val KEY_USER_ROLE = "tipoUsuario" // guarda "TRANSMITTER" ou "VIEWER"
    const val KEY_ROOM_ID = "roomId"        // sempre ROOM_ID, salvo por conveniencia/clareza
    const val KEY_CONFIG_VERSION = "configVersion"

    // -------------------------------------------------------------------
    // Servidores ICE (STUN / TURN) usados pelo WebRTC
    // -------------------------------------------------------------------
    // O QUE E ISSO:
    // Para dois celulares conseguirem se conectar diretamente (peer-to-peer),
    // eles as vezes precisam de ajuda para descobrir seu proprio endereco de
    // rede publico (STUN) ou, em redes mais restritas (ex: 4G com CGNAT,
    // firewalls corporativos), precisam de um servidor que repasse o video
    // (TURN), porque a conexao direta nao e possivel.
    //
    // - STUN: so ajuda a "descobrir o endereco". Gratuito, publico, leve.
    // - TURN: repassa o trafego de video quando a conexao direta falha.
    //   Servidores TURN sao mais caros de manter (usam banda de verdade) e
    //   normalmente exigem usuario/senha.
    //
    // Em producao, configure tambem um TURN. Sem TURN, muitos cenarios entre
    // redes diferentes funcionam, mas outros falham por NAT/firewall.
    val ICE_SERVERS: List<PeerConnection.IceServer> = buildList {
        configuredUrls(BuildConfig.STUN_SERVER_URL).forEach { stunServerUrl ->
            add(PeerConnection.IceServer.builder(stunServerUrl).createIceServer())
        }

        configuredUrls(BuildConfig.TURN_SERVER_URL).forEach { turnServerUrl ->
            val turnBuilder = PeerConnection.IceServer.builder(turnServerUrl)
            val turnUsername = BuildConfig.TURN_USERNAME.trim()
            val turnPassword = BuildConfig.TURN_PASSWORD
            if (turnUsername.isNotEmpty() || turnPassword.isNotEmpty()) {
                turnBuilder.setUsername(turnUsername)
                turnBuilder.setPassword(turnPassword)
            }
            add(turnBuilder.createIceServer())
        }
    }

    private fun configuredUrls(rawValue: String): List<String> {
        return rawValue.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // -------------------------------------------------------------------
    // Gravacao local de tela (feature extra, alem do compartilhamento ao vivo)
    // -------------------------------------------------------------------
    // Pasta onde o video gravado e salvo: armazenamento PRIVADO do proprio
    // app (getExternalFilesDir), acessivel apenas por este app e, se o
    // usuario quiser, por um gerenciador de arquivos manualmente. Nao aparece
    // na Galeria e nao precisa de permissao de armazenamento no Android.
    const val RECORDINGS_FOLDER_NAME = "Movies"
}
