package com.pedro.screenshare.webrtc

import android.content.Context
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * WebRtcClient
 * ---------------------------------------------------------------------------
 * Esta classe cuida SOMENTE da parte de WebRTC (PeerConnection, SDP, ICE).
 * Ela NAO sabe nada sobre WebSocket nem sobre a interface do app - toda
 * comunicacao com o "mundo de fora" acontece atraves dos callbacks
 * (`onLocalIceCandidate`, `onLocalSdp`, `onRemoteVideoTrack`, etc), que quem
 * usa esta classe (as Activities) preenche e repassa para o SignalingClient.
 * Essa separacao e proposital: assim, a logica de WebRTC pode ser testada e
 * entendida sem misturar com a logica de rede ou de tela.
 *
 * CONCEITO IMPORTANTE - PeerConnection:
 * E o objeto do WebRTC que representa UMA conexao de video com UM outro
 * participante. Como o transmissor pode ter varios visualizadores assistindo
 * ao mesmo tempo, mantemos um MAPA de PeerConnections, uma para cada
 * visualizador (chave = viewerId). No lado do visualizador so existe uma
 * conexao (com o transmissor), entao usamos sempre a mesma chave fixa
 * `TRANSMITTER_PEER_ID` para essa unica PeerConnection.
 *
 * CONCEITO IMPORTANTE - Offer/Answer (negociacao SDP):
 * SDP (Session Description Protocol) e um "documento" de texto que descreve
 * como o video vai ser enviado: codec (ex: VP8/H264), resolucao, etc.
 *   - Quem quer ENVIAR video (o transmissor) cria uma OFFER (createOffer) e
 *     manda para o outro lado.
 *   - Quem recebe a offer (o visualizador) usa ela como "descricao remota"
 *     (setRemoteDescription) e responde com uma ANSWER (createAnswer).
 *   - Cada lado tambem guarda a propria descricao como "descricao local"
 *     (setLocalDescription).
 * So depois que os dois lados tem a descricao local E remota configuradas e
 * que a conexao de video pode comecar a fluir.
 *
 * CONCEITO IMPORTANTE - ICE Candidate:
 * Enquanto a negociacao SDP acontece, cada lado tambem vai descobrindo
 * "caminhos de rede" possiveis para chegar nele (o proprio IP na rede local,
 * o IP publico descoberto via STUN, etc). Cada caminho descoberto e um "ICE
 * candidate", que precisa ser enviado para o outro lado assim que for
 * gerado. O WebRTC tenta, nos bastidores, todos os caminhos recebidos e fica
 * com o que funcionar melhor.
 */
object WebRtcClient {

    /** Id fixo usado pelo VISUALIZADOR para se referir a "sua unica conexao", com o transmissor. */
    const val TRANSMITTER_PEER_ID = "transmitter"

    // --- Callbacks que quem usa esta classe (Activities) deve preencher ---

    /** Chamado quando este lado gera um ICE candidate que precisa ser enviado pelo SignalingClient. */
    var onLocalIceCandidate: ((peerId: String, candidate: IceCandidate) -> Unit)? = null

    /** Chamado quando uma OFFER local fica pronta (lado transmissor), para ser enviada via SignalingClient. */
    var onLocalOffer: ((peerId: String, sdp: SessionDescription) -> Unit)? = null

    /** Chamado quando uma ANSWER local fica pronta (lado visualizador), para ser enviada via SignalingClient. */
    var onLocalAnswer: ((peerId: String, sdp: SessionDescription) -> Unit)? = null

    /** Chamado no lado VISUALIZADOR quando um VideoTrack remoto chega (a tela do transmissor). */
    var onRemoteVideoTrack: ((peerId: String, track: VideoTrack) -> Unit)? = null

    /** Chamado quando a conexao com um peer muda de estado (ex: conectado, falhou, desconectado). */
    var onPeerConnectionStateChanged: ((peerId: String, state: PeerConnection.PeerConnectionState) -> Unit)? = null

    // Mapa de conexoes ativas: viewerId (ou TRANSMITTER_PEER_ID) -> PeerConnection.
    private val activePeerConnections = mutableMapOf<String, PeerConnection>()

    /**
     * LADO TRANSMISSOR: cria uma nova PeerConnection para um visualizador
     * especifico, anexa o video local (a captura de tela) e gera uma OFFER.
     * O resultado (SDP da offer) chega pelo callback `onLocalOffer`.
     */
    fun createOfferForViewer(context: Context, viewerId: String, localVideoTrack: VideoTrack) {
        val peerConnection = createPeerConnection(context, viewerId) ?: return

        // Anexa a track de video local (a tela capturada) a esta conexao.
        // A partir daqui, o WebRTC sabe que deve enviar esse video pra frente.
        peerConnection.addTrack(localVideoTrack, listOf("screenShareStream"))

        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                // Guarda a offer criada como "descricao local" desta conexao.
                peerConnection.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                onLocalOffer?.invoke(viewerId, sessionDescription)
            }
        }, MediaConstraints())
    }

    /**
     * LADO TRANSMISSOR: aplica a ANSWER que um visualizador respondeu para a
     * nossa offer. Depois disso a conexao com aquele visualizador especifico
     * fica pronta para trocar video.
     */
    fun applyRemoteAnswer(viewerId: String, sdp: SessionDescription) {
        activePeerConnections[viewerId]?.setRemoteDescription(SimpleSdpObserver(), sdp)
    }

    /**
     * LADO VISUALIZADOR: recebe a OFFER do transmissor, cria a PeerConnection
     * (sem nenhuma track local - o visualizador so recebe video, nao envia),
     * aplica a offer como descricao remota e gera a ANSWER.
     * O resultado (SDP da answer) chega pelo callback `onLocalAnswer`.
     */
    fun createAnswerForOffer(context: Context, offerSdp: SessionDescription) {
        val peerConnection = createPeerConnection(context, TRANSMITTER_PEER_ID) ?: return

        peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peerConnection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
                        peerConnection.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                        onLocalAnswer?.invoke(TRANSMITTER_PEER_ID, sessionDescription)
                    }
                }, MediaConstraints())
            }
        }, offerSdp)
    }

    /** Adiciona um ICE candidate recebido do outro lado (via SignalingClient) a conexao correta. */
    fun addRemoteIceCandidate(peerId: String, candidate: IceCandidate) {
        activePeerConnections[peerId]?.addIceCandidate(candidate)
    }

    /** Fecha e libera a PeerConnection de um peer especifico (ex: visualizador saiu). */
    fun closePeerConnection(peerId: String) {
        activePeerConnections.remove(peerId)?.let { peerConnection ->
            peerConnection.close()
            peerConnection.dispose()
        }
    }

    /** Fecha todas as conexoes ativas (ex: transmissor clicou em "Parar compartilhamento"). */
    fun closeAllPeerConnections() {
        activePeerConnections.keys.toList().forEach { peerId -> closePeerConnection(peerId) }
    }

    /**
     * Cria uma PeerConnection nova, configurada com os servidores STUN/TURN
     * (ver Constants.ICE_SERVERS) e o "observer" que escuta os eventos dela
     * (ICE candidates gerados, video remoto chegando, mudanca de estado).
     */
    private fun createPeerConnection(context: Context, peerId: String): PeerConnection? {
        val factory = PeerConnectionFactoryProvider.getFactory(context)
        val rtcConfig = PeerConnection.RTCConfiguration(PeerConnectionFactoryProvider.iceServers()).apply {
            // Unified Plan e o padrao moderno de negociacao SDP do WebRTC
            // (cada track de midia tem seu proprio "transceiver"), recomendado
            // pela propria documentacao do WebRTC.
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onLocalIceCandidate?.invoke(peerId, candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onPeerConnectionStateChanged?.invoke(peerId, newState)
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                // E aqui que o VISUALIZADOR recebe o video vindo do transmissor.
                val track = receiver.track()
                if (track is VideoTrack) {
                    onRemoteVideoTrack?.invoke(peerId, track)
                }
            }

            // Os metodos abaixo nao sao usados neste app (nao usamos audio,
            // data channels nem troca manual de streams), mas a interface do
            // WebRTC exige que todos sejam implementados.
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })

        if (peerConnection != null) {
            activePeerConnections[peerId] = peerConnection
        }
        return peerConnection
    }

    /**
     * Implementacao "vazia" de SdpObserver, para so precisarmos sobrescrever
     * o metodo que realmente importa em cada chamada (evita repetir os 4
     * metodos vazios toda vez que precisamos de um SdpObserver).
     */
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
