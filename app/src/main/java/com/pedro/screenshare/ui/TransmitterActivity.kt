package com.pedro.screenshare.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pedro.screenshare.R
import com.pedro.screenshare.data.LocalConfigManager
import com.pedro.screenshare.service.ScreenCaptureService
import com.pedro.screenshare.session.TransmitterSession
import com.pedro.screenshare.signaling.SignalingClient
import com.pedro.screenshare.signaling.SignalingEvent
import com.pedro.screenshare.signaling.SignalingMessage
import com.pedro.screenshare.utils.Constants
import com.pedro.screenshare.utils.PermissionUtils
import com.pedro.screenshare.webrtc.WebRtcClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * TransmitterActivity
 * ---------------------------------------------------------------------------
 * Tela do celular TRANSMISSOR. Esta classe e o "coordenador": ela conecta os
 * pontos entre SignalingClient (WebSocket), WebRtcClient (PeerConnections) e
 * ScreenCaptureService (captura/gravacao de tela), mas NAO IMPLEMENTA a
 * logica interna de nenhum deles - so repassa dados entre um e outro e
 * atualiza a interface (status, botoes).
 *
 * REGRA DE PRIVACIDADE IMPORTANTE: o compartilhamento e a gravacao SO
 * comecam depois que o USUARIO clica no botao correspondente E aceita o
 * dialogo oficial de permissao do Android (MediaProjection). Nenhum evento
 * vindo da rede (nem "start-share-request" do visualizador) pode iniciar a
 * captura sozinho - o maximo que fazemos e mostrar um aviso pedindo para o
 * usuario clicar em "Iniciar compartilhamento" manualmente.
 */
class TransmitterActivity : AppCompatActivity(), SignalingClient.Listener {

    private enum class CaptureAction {
        SHARE,
        RECORD
    }

    companion object {
        private const val UX_PREFS_NAME = "transmitter_ux_prefs"
        private const val KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN =
            "notification_permission_prompt_shown"
        private const val SHARE_REQUEST_NOTICE_COOLDOWN_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 5_000L
    }

    private lateinit var localConfigManager: LocalConfigManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private lateinit var textStatus: TextView
    private lateinit var buttonGoOnline: Button
    private lateinit var buttonStartSharing: Button
    private lateinit var buttonStopSharing: Button
    private lateinit var buttonToggleRecording: Button
    private lateinit var buttonResetConfig: Button

    private var isSharing = false
    private var isRecording = false
    private var localVideoTrack: VideoTrack? = null
    private var pendingCaptureAction: CaptureAction? = null
    private var lastShareRequestNoticeAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        goOnline()
    }
    private val networkReconnectRunnable = Runnable {
        reconnectAfterNetworkChange()
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var ignoreInitialNetworkAvailable = false
    private val transmitterSessionUiListener = object : TransmitterSession.UiListener {
        override fun onStatusChanged(text: String) {
            runOnUiThread {
                updateStatus(text)
            }
        }

        override fun onSharingStateChanged(isSharing: Boolean) {
            runOnUiThread {
                this@TransmitterActivity.isSharing = isSharing
                buttonGoOnline.isEnabled = !SignalingClient.isConnected() && !SignalingClient.isConnecting()
                buttonStartSharing.isEnabled = !isSharing && SignalingClient.isConnected()
                buttonStopSharing.isEnabled = isSharing
            }
        }

        override fun onUserMessage(text: String) {
            runOnUiThread {
                Toast.makeText(this@TransmitterActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Launchers para a tela de permissao do Android (MediaProjection) ---
    // Sao dois launchers SEPARADOS porque compartilhamento e gravacao sao
    // funcionalidades independentes: cada uma pede sua propria autorizacao.

    private val shareCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val permissionData = result.data
            if (result.resultCode == Activity.RESULT_OK && permissionData != null) {
                TransmitterSession.startSharing(applicationContext, permissionData)
            } else {
                Toast.makeText(this, "Permissão de compartilhamento negada", Toast.LENGTH_SHORT).show()
            }
        }

    private val recordCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val permissionData = result.data
            if (result.resultCode == Activity.RESULT_OK && permissionData != null) {
                ScreenCaptureService.startRecording(applicationContext, result.resultCode, permissionData)
                isRecording = true
                buttonToggleRecording.text = getString(R.string.btn_stop_recording)
            } else {
                Toast.makeText(this, "Permissão de gravação negada", Toast.LENGTH_SHORT).show()
            }
        }

    // Permissao de notificacao (Android 13+) - pedida antes de iniciar a
    // captura, no maximo uma vez, so para a notificacao fixa conseguir
    // aparecer. Nao bloqueia o fluxo se o usuario negar.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            consumePendingCaptureAction()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transmitter)

        localConfigManager = LocalConfigManager(applicationContext)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        textStatus = findViewById(R.id.textStatus)
        buttonGoOnline = findViewById(R.id.buttonGoOnline)
        buttonStartSharing = findViewById(R.id.buttonStartSharing)
        buttonStopSharing = findViewById(R.id.buttonStopSharing)
        buttonToggleRecording = findViewById(R.id.buttonToggleRecording)
        buttonResetConfig = findViewById(R.id.buttonResetConfig)

        buttonGoOnline.setOnClickListener { goOnline() }
        buttonStartSharing.setOnClickListener { requestStartSharing() }
        buttonStopSharing.setOnClickListener { stopSharing() }
        buttonToggleRecording.setOnClickListener { toggleRecording() }
        buttonResetConfig.setOnClickListener { resetConfig() }

        TransmitterSession.bindUi(applicationContext, transmitterSessionUiListener)
        setupRecordingCallbacks()
        keepConnectionWhenBackIsPressed()

        updateStatus(getString(R.string.status_offline))
    }

    override fun onResume() {
        super.onResume()
        // Esta tela passa a "escutar" o SignalingClient enquanto estiver
        // visivel. Nao removemos o listener no onPause de proposito: se o
        // usuario minimizar o app enquanto compartilha, o Foreground Service
        // continua rodando e as mensagens (ex: novo visualizador entrando)
        // ainda precisam ser processadas.
        TransmitterSession.bindUi(applicationContext, transmitterSessionUiListener)
        TransmitterSession.goOnline(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        TransmitterSession.unbindUi(transmitterSessionUiListener)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(networkReconnectRunnable)
        unregisterNetworkReconnect()
    }

    // -------------------------------------------------------------------
    // Acoes dos botoes
    // -------------------------------------------------------------------

    private fun goOnline() {
        TransmitterSession.goOnline(applicationContext)
    }

    private fun requestStartSharing() {
        requestCaptureAfterPoliteNotificationCheck(CaptureAction.SHARE)
    }

    private fun stopSharing() {
        TransmitterSession.stopSharing(applicationContext)
        localVideoTrack = null
    }

    private fun toggleRecording() {
        if (isRecording) {
            ScreenCaptureService.stopRecording(applicationContext)
            isRecording = false
            buttonToggleRecording.text = getString(R.string.btn_start_recording)
        } else {
            requestCaptureAfterPoliteNotificationCheck(CaptureAction.RECORD)
        }
    }

    private fun resetConfig() {
        TransmitterSession.reset(applicationContext)
        WebRtcClient.closeAllPeerConnections()
        ScreenCaptureService.stopSharing(applicationContext)
        ScreenCaptureService.stopRecording(applicationContext)

        localConfigManager.resetConfig()
        mainHandler.removeCallbacks(reconnectRunnable)
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    // -------------------------------------------------------------------
    // Callbacks do ScreenCaptureService (captura/gravacao de tela)
    // -------------------------------------------------------------------

    private fun setupRecordingCallbacks() {
        ScreenCaptureService.onRecordingStoppedBySystem = {
            runOnUiThread {
                isRecording = false
                buttonToggleRecording.text = getString(R.string.btn_start_recording)
                Toast.makeText(this, "Gravação interrompida pelo sistema", Toast.LENGTH_LONG).show()
            }
        }

        ScreenCaptureService.onRecordingSaved = { savedFilePath ->
            runOnUiThread {
                Toast.makeText(this, getString(R.string.msg_recording_saved, savedFilePath), Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------
    // Callbacks do WebRtcClient (sinalizacao SDP/ICE do WebRTC)
    // -------------------------------------------------------------------

    private fun setupWebRtcCallbacks() {
        // Cada ICE candidate gerado localmente precisa ser enviado ao
        // visualizador correspondente atraves do servidor de sinalizacao.
        WebRtcClient.onLocalIceCandidate = { viewerId, candidate ->
            SignalingClient.send(
                SignalingMessage(
                    type = SignalingEvent.ICE_CANDIDATE,
                    roomId = Constants.ROOM_ID,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    viewerId = viewerId
                )
            )
        }

        // Cada OFFER criada para um visualizador precisa ser enviada a ele
        // atraves do servidor de sinalizacao.
        WebRtcClient.onLocalOffer = { viewerId, sdp ->
            SignalingClient.send(
                SignalingMessage(
                    type = SignalingEvent.OFFER,
                    roomId = Constants.ROOM_ID,
                    sdp = sdp.description,
                    viewerId = viewerId
                )
            )
        }
    }

    // -------------------------------------------------------------------
    // SignalingClient.Listener - mensagens recebidas do servidor
    // -------------------------------------------------------------------

    override fun onOpen() {
        SignalingClient.send(SignalingMessage(type = SignalingEvent.REGISTER_TRANSMITTER, roomId = Constants.ROOM_ID))
        buttonGoOnline.isEnabled = false
        if (isSharing && localVideoTrack != null) {
            updateStatus(getString(R.string.status_sharing))
            buttonStartSharing.isEnabled = false
            buttonStopSharing.isEnabled = true
            SignalingClient.send(SignalingMessage(type = SignalingEvent.SHARING_STARTED, roomId = Constants.ROOM_ID))
        } else {
            updateStatus(getString(R.string.status_ready))
            buttonStartSharing.isEnabled = true
        }
    }

    override fun onMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingEvent.ERROR -> {
                updateStatus(getString(R.string.status_error))
                Toast.makeText(this, message.message ?: "Erro desconhecido", Toast.LENGTH_LONG).show()
            }

            SignalingEvent.VIEWER_ONLINE -> {
                // Um novo visualizador entrou na sala. Se ja estamos
                // compartilhando, criamos uma oferta so para ele agora.
                val viewerId = message.viewerId ?: return
                val track = localVideoTrack
                if (isSharing && track != null) {
                    WebRtcClient.createOfferForViewer(applicationContext, viewerId, track)
                }
            }

            SignalingEvent.VIEWER_OFFLINE -> {
                // Visualizador saiu - liberamos a PeerConnection dele para
                // nao vazar memoria/recursos.
                message.viewerId?.let { WebRtcClient.closePeerConnection(it) }
            }

            SignalingEvent.START_SHARE_REQUEST -> {
                // O visualizador PEDIU para compartilhar, mas quem decide e
                // sempre o usuario deste celular. Tambem limitamos avisos
                // repetidos para nao transformar o pedido em insistencia.
                val now = SystemClock.elapsedRealtime()
                if (now - lastShareRequestNoticeAt >= SHARE_REQUEST_NOTICE_COOLDOWN_MS) {
                    lastShareRequestNoticeAt = now
                    Toast.makeText(this, R.string.msg_viewer_requested_share, Toast.LENGTH_LONG).show()
                }
            }

            SignalingEvent.SHARING_STARTED -> {
                // Echo do proprio servidor, contendo a lista de visualizadores
                // que ja estavam na sala quando comecamos a compartilhar.
                val track = localVideoTrack ?: return
                message.viewerIds?.forEach { viewerId ->
                    WebRtcClient.createOfferForViewer(applicationContext, viewerId, track)
                }
            }

            SignalingEvent.ANSWER -> {
                val viewerId = message.viewerId ?: return
                val sdp = message.sdp ?: return
                WebRtcClient.applyRemoteAnswer(viewerId, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            SignalingEvent.ICE_CANDIDATE -> {
                val viewerId = message.viewerId ?: return
                val candidate = message.candidate ?: return
                WebRtcClient.addRemoteIceCandidate(
                    viewerId,
                    IceCandidate(message.sdpMid ?: "", message.sdpMLineIndex ?: 0, candidate)
                )
            }

            else -> Unit
        }
    }

    override fun onClosed() {
        updateStatus(getString(R.string.status_offline))
        buttonGoOnline.isEnabled = true
        buttonStartSharing.isEnabled = false
        buttonStopSharing.isEnabled = false
        scheduleReconnect()
    }

    override fun onError(description: String) {
        updateStatus(getString(R.string.status_error))
        scheduleReconnect()
    }

    private fun updateStatus(text: String) {
        textStatus.text = text
    }

    private fun requestCaptureAfterPoliteNotificationCheck(action: CaptureAction) {
        if (PermissionUtils.isNotificationPermissionGranted(this) ||
            hasShownNotificationPermissionPrompt()
        ) {
            launchMediaProjection(action)
            return
        }

        pendingCaptureAction = action
        markNotificationPermissionPromptShown()
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.notification_permission_positive) { _, _ ->
                notificationPermissionLauncher.launch(PermissionUtils.NOTIFICATION_PERMISSION)
            }
            .setNegativeButton(R.string.notification_permission_negative) { _, _ ->
                consumePendingCaptureAction()
            }
            .setOnCancelListener {
                consumePendingCaptureAction()
            }
            .show()
    }

    private fun consumePendingCaptureAction() {
        val action = pendingCaptureAction ?: return
        pendingCaptureAction = null
        launchMediaProjection(action)
    }

    private fun launchMediaProjection(action: CaptureAction) {
        // Abre o dialogo OFICIAL do Android pedindo autorizacao para capturar
        // a tela. So depois que o usuario aceitar e que a captura de fato
        // comeca.
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        when (action) {
            CaptureAction.SHARE -> shareCaptureLauncher.launch(captureIntent)
            CaptureAction.RECORD -> recordCaptureLauncher.launch(captureIntent)
        }
    }

    private fun hasShownNotificationPermissionPrompt(): Boolean {
        return getSharedPreferences(UX_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN, false)
    }

    private fun markNotificationPermissionPromptShown() {
        getSharedPreferences(UX_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPT_SHOWN, true)
            .apply()
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun setupNetworkReconnect() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        ignoreInitialNetworkAvailable = connectivityManager.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (ignoreInitialNetworkAvailable) {
                        ignoreInitialNetworkAvailable = false
                        return@post
                    }
                    scheduleNetworkReconnect()
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    if (SignalingClient.isConnected() || SignalingClient.isConnecting()) {
                        updateStatus(getString(R.string.status_connecting))
                    }
                    scheduleNetworkReconnect()
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkReconnect() {
        val callback = networkCallback ?: return
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        networkCallback = null
    }

    private fun scheduleNetworkReconnect() {
        mainHandler.removeCallbacks(networkReconnectRunnable)
        mainHandler.postDelayed(networkReconnectRunnable, 1_500L)
    }

    private fun reconnectAfterNetworkChange() {
        if (isSharing) {
            WebRtcClient.closeAllPeerConnections()
        }
        updateStatus(getString(R.string.status_connecting))
        if (SignalingClient.isConnected() || SignalingClient.isConnecting()) {
            SignalingClient.reconnect(Constants.SIGNALING_SERVER_URL)
        } else {
            goOnline()
        }
    }

    private fun keepConnectionWhenBackIsPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }
}
