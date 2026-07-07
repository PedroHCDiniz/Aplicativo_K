package com.pedro.screenshare.webrtc

import android.content.Context
import com.pedro.screenshare.utils.Constants
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * PeerConnectionFactoryProvider
 * ---------------------------------------------------------------------------
 * O WebRTC exige uma serie de passos de inicializacao (nativo/C++ por baixo
 * dos panos) antes de conseguirmos criar qualquer PeerConnection ou capturar
 * video. Este objeto isola essa inicializacao "chata" em um unico lugar, para
 * o resto do app (WebRtcClient, ScreenShareManager) so precisar chamar
 * `PeerConnectionFactoryProvider.factory` e `PeerConnectionFactoryProvider.eglBaseContext`.
 *
 * O QUE E O "EglBase"?
 * E um contexto OpenGL ES compartilhado, usado internamente pelo WebRTC para
 * capturar/renderizar frames de video com aceleracao de hardware (GPU). Ele
 * precisa ser o MESMO objeto tanto na hora de capturar a tela (transmissor)
 * quanto na hora de desenhar o video recebido na tela (visualizador,
 * SurfaceViewRenderer) - por isso ele fica centralizado aqui como singleton.
 */
object PeerConnectionFactoryProvider {

    // EglBase compartilhado por toda a captura/renderizacao de video do app.
    val eglBase: EglBase by lazy { EglBase.create() }
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null

    /**
     * Cria (uma unica vez) a PeerConnectionFactory, que e a "fabrica" usada
     * para criar PeerConnections, VideoSources, VideoTracks, etc.
     * Precisa ser chamada uma vez com o Application Context antes de usar
     * qualquer outra coisa do WebRTC.
     */
    fun getFactory(context: Context): PeerConnectionFactory {
        peerConnectionFactory?.let { return it }

        // Passo 1: inicializa as bibliotecas nativas do WebRTC.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )

        // Passo 2: cria os codificadores/decodificadores de video usando
        // aceleracao de hardware quando disponivel (mais eficiente e usa
        // menos bateria do que codificar via software).
        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        // Passo 3: monta a factory final.
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()

        peerConnectionFactory = factory
        return factory
    }

    /** Lista de servidores STUN/TURN usada em toda PeerConnection criada pelo app. */
    fun iceServers() = Constants.ICE_SERVERS
}
