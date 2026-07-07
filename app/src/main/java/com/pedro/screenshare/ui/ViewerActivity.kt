package com.pedro.screenshare.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pedro.screenshare.R
import com.pedro.screenshare.data.LocalConfigManager
import com.pedro.screenshare.signaling.SignalingClient
import com.pedro.screenshare.signaling.SignalingEvent
import com.pedro.screenshare.signaling.SignalingMessage
import com.pedro.screenshare.utils.Constants
import com.pedro.screenshare.webrtc.PeerConnectionFactoryProvider
import com.pedro.screenshare.webrtc.WebRtcClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

/**
 * ViewerActivity
 * ---------------------------------------------------------------------------
 * Tela do celular VISUALIZADOR. Assim como a TransmitterActivity, esta classe
 * e o "coordenador" entre SignalingClient (WebSocket) e WebRtcClient
 * (PeerConnection) - ela nao implementa a logica interna de nenhum dos dois,
 * so repassa mensagens entre eles e atualiza a interface.
 *
 * REGRA IMPORTANTE: o visualizador NUNCA obriga o transmissor a compartilhar.
 * O botao "Solicitar compartilhamento" so ENVIA UM PEDIDO (start-share-request)
 * - quem decide se compartilha ou nao e sempre o usuario do celular
 * transmissor, clicando no botao dele e aceitando a permissao do Android.
 */
class ViewerActivity : AppCompatActivity(), SignalingClient.Listener {

    companion object {
        private const val SHARE_REQUEST_COOLDOWN_MS = 30_000L
    }

    private lateinit var localConfigManager: LocalConfigManager

    private lateinit var textStatus: TextView
    private lateinit var surfaceRemoteVideo: SurfaceViewRenderer
    private lateinit var buttonWatchScreen: Button
    private lateinit var buttonRequestShare: Button
    private lateinit var buttonStopWatching: Button
    private lateinit var buttonResetConfig: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val enableShareRequestRunnable = Runnable {
        if (SignalingClient.isConnected()) {
            buttonRequestShare.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        localConfigManager = LocalConfigManager(applicationContext)

        textStatus = findViewById(R.id.textStatus)
        surfaceRemoteVideo = findViewById(R.id.surfaceRemoteVideo)
        buttonWatchScreen = findViewById(R.id.buttonWatchScreen)
        buttonRequestShare = findViewById(R.id.buttonRequestShare)
        buttonStopWatching = findViewById(R.id.buttonStopWatching)
        buttonResetConfig = findViewById(R.id.buttonResetConfig)

        // Prepara o componente que vai desenhar o video recebido. Precisa do
        // mesmo EglBase.Context usado pelo resto do WebRTC no app (ver
        // PeerConnectionFactoryProvider) para conseguir renderizar os frames.
        surfaceRemoteVideo.init(PeerConnectionFactoryProvider.eglBaseContext, null)
        surfaceRemoteVideo.setMirror(false)

        buttonWatchScreen.setOnClickListener { watchScreen() }
        buttonRequestShare.setOnClickListener { requestShare() }
        buttonStopWatching.setOnClickListener { stopWatching() }
        buttonResetConfig.setOnClickListener { resetConfig() }

        setupWebRtcCallbacks()
        updateStatus(getString(R.string.status_offline))
    }

    override fun onResume() {
        super.onResume()
        SignalingClient.listener = this
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(enableShareRequestRunnable)
        surfaceRemoteVideo.release()
    }

    // -------------------------------------------------------------------
    // Acoes dos botoes
    // -------------------------------------------------------------------

    /**
     * "Assistir tela": entra AUTOMATICAMENTE na sala fixa, sem pedir nenhum
     * codigo ao usuario. Se o transmissor ja estiver compartilhando, o video
     * comeca a aparecer sozinho assim que a oferta WebRTC chegar.
     */
    private fun watchScreen() {
        if (SignalingClient.isConnected()) return
        updateStatus(getString(R.string.status_connecting))
        SignalingClient.connect(Constants.SIGNALING_SERVER_URL)
    }

    private fun requestShare() {
        SignalingClient.send(SignalingMessage(type = SignalingEvent.START_SHARE_REQUEST, roomId = Constants.ROOM_ID))
        Toast.makeText(this, R.string.msg_share_request_sent, Toast.LENGTH_SHORT).show()
        buttonRequestShare.isEnabled = false
        mainHandler.removeCallbacks(enableShareRequestRunnable)
        mainHandler.postDelayed(enableShareRequestRunnable, SHARE_REQUEST_COOLDOWN_MS)
    }

    private fun stopWatching() {
        WebRtcClient.closePeerConnection(WebRtcClient.TRANSMITTER_PEER_ID)
        SignalingClient.disconnect()
        surfaceRemoteVideo.clearImage()

        mainHandler.removeCallbacks(enableShareRequestRunnable)
        buttonRequestShare.isEnabled = false
        buttonStopWatching.isEnabled = false
        updateStatus(getString(R.string.status_offline))
    }

    private fun resetConfig() {
        WebRtcClient.closePeerConnection(WebRtcClient.TRANSMITTER_PEER_ID)
        SignalingClient.disconnect()

        localConfigManager.resetConfig()
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    // -------------------------------------------------------------------
    // Callbacks do WebRtcClient (sinalizacao SDP/ICE do WebRTC)
    // -------------------------------------------------------------------

    private fun setupWebRtcCallbacks() {
        // ICE candidates gerados localmente precisam ser enviados ao
        // transmissor atraves do servidor de sinalizacao.
        WebRtcClient.onLocalIceCandidate = { _, candidate ->
            SignalingClient.send(
                SignalingMessage(
                    type = SignalingEvent.ICE_CANDIDATE,
                    roomId = Constants.ROOM_ID,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex
                )
            )
        }

        // A ANSWER criada em resposta a offer do transmissor precisa ser
        // enviada de volta atraves do servidor de sinalizacao.
        WebRtcClient.onLocalAnswer = { _, sdp ->
            SignalingClient.send(
                SignalingMessage(
                    type = SignalingEvent.ANSWER,
                    roomId = Constants.ROOM_ID,
                    sdp = sdp.description
                )
            )
        }

        // Video remoto chegou: anexa ao componente de tela (SurfaceViewRenderer).
        WebRtcClient.onRemoteVideoTrack = { _, videoTrack ->
            runOnUiThread {
                videoTrack.addSink(surfaceRemoteVideo)
                updateStatus(getString(R.string.status_receiving))
            }
        }
    }

    // -------------------------------------------------------------------
    // SignalingClient.Listener - mensagens recebidas do servidor
    // -------------------------------------------------------------------

    override fun onOpen() {
        // Entra na sala fixa como visualizador - nenhum codigo digitado pelo
        // usuario, tudo automatico.
        SignalingClient.send(SignalingMessage(type = SignalingEvent.REGISTER_VIEWER, roomId = Constants.ROOM_ID))
        buttonStopWatching.isEnabled = true
    }

    override fun onMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingEvent.ERROR -> {
                updateStatus(getString(R.string.status_error))
                Toast.makeText(this, message.message ?: "Erro desconhecido", Toast.LENGTH_LONG).show()
            }

            SignalingEvent.TRANSMITTER_ONLINE -> {
                // Cenario 2: transmissor online, mas ainda nao compartilhando.
                updateStatus(getString(R.string.msg_waiting_transmitter_start))
                buttonRequestShare.isEnabled = true
            }

            SignalingEvent.TRANSMITTER_OFFLINE -> {
                // Cenario 3: transmissor offline.
                updateStatus(getString(R.string.msg_transmitter_offline))
                mainHandler.removeCallbacks(enableShareRequestRunnable)
                buttonRequestShare.isEnabled = false
                WebRtcClient.closePeerConnection(WebRtcClient.TRANSMITTER_PEER_ID)
                surfaceRemoteVideo.clearImage()
            }

            SignalingEvent.SHARING_STARTED -> {
                // Cenario 1: o video comeca a aparecer sozinho assim que a
                // OFFER (mensagem separada, abaixo) chegar.
                updateStatus(getString(R.string.status_connecting))
            }

            SignalingEvent.SHARING_STOPPED -> {
                // Cenario 4: transmissor parou de compartilhar.
                updateStatus(getString(R.string.msg_sharing_ended))
                WebRtcClient.closePeerConnection(WebRtcClient.TRANSMITTER_PEER_ID)
                surfaceRemoteVideo.clearImage()
            }

            SignalingEvent.OFFER -> {
                val sdp = message.sdp ?: return
                WebRtcClient.createAnswerForOffer(
                    applicationContext,
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
            }

            SignalingEvent.ICE_CANDIDATE -> {
                val candidate = message.candidate ?: return
                WebRtcClient.addRemoteIceCandidate(
                    WebRtcClient.TRANSMITTER_PEER_ID,
                    IceCandidate(message.sdpMid ?: "", message.sdpMLineIndex ?: 0, candidate)
                )
            }

            else -> Unit
        }
    }

    override fun onClosed() {
        updateStatus(getString(R.string.status_offline))
        mainHandler.removeCallbacks(enableShareRequestRunnable)
        buttonRequestShare.isEnabled = false
        buttonStopWatching.isEnabled = false
    }

    override fun onError(description: String) {
        updateStatus(getString(R.string.status_error))
    }

    private fun updateStatus(text: String) {
        textStatus.text = text
    }
}
