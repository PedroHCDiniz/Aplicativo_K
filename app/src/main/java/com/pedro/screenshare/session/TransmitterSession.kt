package com.pedro.screenshare.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pedro.screenshare.R
import com.pedro.screenshare.service.ScreenCaptureService
import com.pedro.screenshare.signaling.SignalingClient
import com.pedro.screenshare.signaling.SignalingEvent
import com.pedro.screenshare.signaling.SignalingMessage
import com.pedro.screenshare.utils.Constants
import com.pedro.screenshare.webrtc.WebRtcClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Mantem a transmissao do aparelho emissor fora da Activity.
 *
 * A tela do transmissor pode ser destruida quando o usuario fecha/minimiza o
 * app. Por isso a parte que precisa continuar viva durante a transmissao
 * (WebSocket, WebRTC, reconexao e VideoTrack local) fica neste singleton.
 */
object TransmitterSession : SignalingClient.Listener {

    interface UiListener {
        fun onStatusChanged(text: String)
        fun onSharingStateChanged(isSharing: Boolean)
        fun onUserMessage(text: String)
    }

    private const val SHARE_REQUEST_NOTICE_COOLDOWN_MS = 30_000L
    private const val RECONNECT_DELAY_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        goOnline()
    }
    private val networkReconnectRunnable = Runnable {
        reconnectAfterNetworkChange()
    }

    private var appContext: Context? = null
    private var uiListener: UiListener? = null
    private var localVideoTrack: VideoTrack? = null
    private var isSharing = false
    private var lastShareRequestNoticeAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var ignoreInitialNetworkAvailable = false

    fun bindUi(context: Context, listener: UiListener) {
        prepare(context)
        uiListener = listener
        listener.onSharingStateChanged(isSharing)
        listener.onStatusChanged(
            when {
                isSharing -> context.getString(R.string.status_sharing)
                SignalingClient.isConnected() -> context.getString(R.string.status_ready)
                SignalingClient.isConnecting() -> context.getString(R.string.status_connecting)
                else -> context.getString(R.string.status_offline)
            }
        )
    }

    fun unbindUi(listener: UiListener) {
        if (uiListener === listener) {
            uiListener = null
        }
    }

    fun prepare(context: Context) {
        appContext = context.applicationContext
        SignalingClient.listener = this
        setupScreenSharingCallbacks()
        setupWebRtcCallbacks()
        setupNetworkReconnect()
    }

    fun goOnline(context: Context? = null) {
        context?.let { prepare(it) }
        val contextToUse = appContext ?: return
        if (SignalingClient.isConnected() || SignalingClient.isConnecting()) return
        updateStatus(contextToUse.getString(R.string.status_connecting))
        SignalingClient.connect(Constants.SIGNALING_SERVER_URL)
    }

    fun startSharing(context: Context, permissionData: android.content.Intent) {
        prepare(context)
        ScreenCaptureService.startSharing(context.applicationContext, permissionData)
    }

    fun stopSharing(context: Context) {
        prepare(context)
        ScreenCaptureService.stopSharing(context.applicationContext)
        WebRtcClient.closeAllPeerConnections()
        SignalingClient.send(SignalingMessage(type = SignalingEvent.SHARING_STOPPED, roomId = Constants.ROOM_ID))

        isSharing = false
        localVideoTrack = null
        notifySharingState()
        updateStatus(context.getString(R.string.status_ready))
    }

    fun reset(context: Context) {
        ScreenCaptureService.stopSharing(context.applicationContext)
        SignalingClient.disconnect()
        WebRtcClient.closeAllPeerConnections()
        unregisterNetworkReconnect()
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(networkReconnectRunnable)

        isSharing = false
        localVideoTrack = null
        notifySharingState()
    }

    private fun setupScreenSharingCallbacks() {
        ScreenCaptureService.onLocalVideoTrackReady = { track ->
            localVideoTrack = track
            isSharing = true
            notifySharingState()
            updateStatus(requireContext().getString(R.string.status_sharing))

            SignalingClient.send(SignalingMessage(type = SignalingEvent.SHARING_STARTED, roomId = Constants.ROOM_ID))
        }

        ScreenCaptureService.onSharingStoppedBySystem = {
            isSharing = false
            localVideoTrack = null
            WebRtcClient.closeAllPeerConnections()
            notifySharingState()
            updateStatus(requireContext().getString(R.string.status_ready))
            SignalingClient.send(SignalingMessage(type = SignalingEvent.SHARING_STOPPED, roomId = Constants.ROOM_ID))
            uiListener?.onUserMessage("Compartilhamento interrompido pelo sistema")
        }
    }

    private fun setupWebRtcCallbacks() {
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

    override fun onOpen() {
        SignalingClient.send(SignalingMessage(type = SignalingEvent.REGISTER_TRANSMITTER, roomId = Constants.ROOM_ID))
        if (isSharing && localVideoTrack != null) {
            updateStatus(requireContext().getString(R.string.status_sharing))
            SignalingClient.send(SignalingMessage(type = SignalingEvent.SHARING_STARTED, roomId = Constants.ROOM_ID))
        } else {
            updateStatus(requireContext().getString(R.string.status_ready))
        }
        notifySharingState()
    }

    override fun onMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingEvent.ERROR -> {
                updateStatus(requireContext().getString(R.string.status_error))
                uiListener?.onUserMessage(message.message ?: "Erro desconhecido")
            }

            SignalingEvent.VIEWER_ONLINE -> {
                val viewerId = message.viewerId ?: return
                val track = localVideoTrack
                if (isSharing && track != null) {
                    WebRtcClient.createOfferForViewer(requireContext(), viewerId, track)
                }
            }

            SignalingEvent.VIEWER_OFFLINE -> {
                message.viewerId?.let { WebRtcClient.closePeerConnection(it) }
            }

            SignalingEvent.START_SHARE_REQUEST -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastShareRequestNoticeAt >= SHARE_REQUEST_NOTICE_COOLDOWN_MS) {
                    lastShareRequestNoticeAt = now
                    uiListener?.onUserMessage(requireContext().getString(R.string.msg_viewer_requested_share))
                }
            }

            SignalingEvent.SHARING_STARTED -> {
                val track = localVideoTrack ?: return
                message.viewerIds?.forEach { viewerId ->
                    WebRtcClient.createOfferForViewer(requireContext(), viewerId, track)
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
        updateStatus(requireContext().getString(R.string.status_offline))
        notifySharingState()
        scheduleReconnect()
    }

    override fun onError(description: String) {
        updateStatus(requireContext().getString(R.string.status_error))
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun setupNetworkReconnect() {
        if (networkCallback != null) return
        val connectivityManager = requireContext().getSystemService(ConnectivityManager::class.java)
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
                        updateStatus(requireContext().getString(R.string.status_connecting))
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
        requireContext().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
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
        updateStatus(requireContext().getString(R.string.status_connecting))
        if (SignalingClient.isConnected() || SignalingClient.isConnecting()) {
            SignalingClient.reconnect(Constants.SIGNALING_SERVER_URL)
        } else {
            goOnline()
        }
    }

    private fun notifySharingState() {
        uiListener?.onSharingStateChanged(isSharing)
    }

    private fun updateStatus(text: String) {
        uiListener?.onStatusChanged(text)
    }

    private fun requireContext(): Context {
        return checkNotNull(appContext) { "TransmitterSession precisa ser preparada antes de usar." }
    }
}
