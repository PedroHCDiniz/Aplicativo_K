package com.pedro.screenshare.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pedro.screenshare.recording.ScreenRecordManager
import com.pedro.screenshare.webrtc.ScreenShareManager
import org.webrtc.VideoTrack

/**
 * ScreenCaptureService
 * ---------------------------------------------------------------------------
 * Foreground Service responsavel por manter a captura de tela VIVA mesmo se
 * o usuario minimizar o app. Este servico cuida APENAS do "ciclo de vida" da
 * captura de tela (permissao do sistema + notificacao fixa) - ele delega o
 * trabalho de verdade para:
 *   - ScreenShareManager: transforma a tela em uma VideoTrack para o WebRTC
 *     (transmissao ao vivo para o visualizador).
 *   - ScreenRecordManager: grava a tela em um arquivo .mp4 local.
 * Este servico NAO SABE NADA sobre WebSocket ou WebRTC (PeerConnection) - ele
 * so avisa, por callbacks estaticos, quando uma VideoTrack fica pronta ou
 * quando a gravacao/transmissao para. Quem decide o que fazer com isso e a
 * TransmitterActivity.
 *
 * CONCEITO IMPORTANTE - Foreground Service:
 * O Android mata processos em segundo plano para economizar bateria/memoria.
 * Um Foreground Service e um tipo especial de servico que o sistema evita
 * matar, MAS exige, em troca, que o app mostre uma notificacao fixa e visivel
 * o tempo todo, para o usuario sempre saber que algo continua ativo (nesse
 * caso, a captura de tela). Isso e uma exigencia de PRIVACIDADE do proprio
 * Android: nunca e possivel capturar a tela escondido, sem notificacao.
 *
 * CONCEITO IMPORTANTE - foregroundServiceType="mediaProjection":
 * A partir do Android 10 (API 29), o sistema exige que servicos que usam
 * MediaProjection (captura de tela) declarem esse tipo explicitamente no
 * AndroidManifest.xml E que o servico ja esteja rodando em primeiro piano
 * (startForeground ja chamado) ANTES de obter a permissao de MediaProjection.
 * E por isso que SEMPRE chamamos `startForeground(...)` antes de acionar o
 * ScreenShareManager/ScreenRecordManager em cada handler abaixo.
 *
 * Este servico controla DUAS funcionalidades independentes (compartilhar ao
 * vivo e gravar localmente), que podem estar ativas ao mesmo tempo, cada uma
 * ou nenhuma. O servico so se encerra quando as duas estiverem paradas.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val ACTION_START_SHARE = "com.pedro.screenshare.action.START_SHARE"
        private const val ACTION_STOP_SHARE = "com.pedro.screenshare.action.STOP_SHARE"
        private const val ACTION_START_RECORD = "com.pedro.screenshare.action.START_RECORD"
        private const val ACTION_STOP_RECORD = "com.pedro.screenshare.action.STOP_RECORD"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        // --- Callbacks estaticos simples para a Activity escutar o servico ---
        // (app roda em um unico processo, entao um singleton simples e
        // suficiente - evita a complexidade de um Service "bound"/AIDL para
        // um MVP como este).

        /** Chamado quando a captura para WebRTC esta pronta (transmissao ao vivo). */
        var onLocalVideoTrackReady: ((VideoTrack) -> Unit)? = null

        /** Chamado se a transmissao ao vivo for encerrada pelo proprio sistema Android. */
        var onSharingStoppedBySystem: (() -> Unit)? = null

        /** Chamado se a gravacao local for encerrada pelo proprio sistema Android. */
        var onRecordingStoppedBySystem: (() -> Unit)? = null

        /** Chamado quando uma gravacao termina com sucesso, com o caminho do arquivo .mp4 salvo. */
        var onRecordingSaved: ((filePath: String) -> Unit)? = null

        /**
         * Inicia a transmissao ao vivo (WebRTC) da tela.
         * OBS: diferente de `startRecording`, aqui nao precisamos do
         * resultCode - o ScreenCapturerAndroid do WebRTC so exige o Intent de
         * permissao (sempre chamado apos o usuario aceitar, ou seja, sempre
         * equivalente a RESULT_OK).
         */
        fun startSharing(context: Context, permissionData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START_SHARE)
                .putExtra(EXTRA_RESULT_DATA, permissionData)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Para a transmissao ao vivo (WebRTC) da tela. */
        fun stopSharing(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP_SHARE))
        }

        /** Inicia a gravacao local (.mp4) da tela. */
        fun startRecording(context: Context, resultCode: Int, permissionData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START_RECORD)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, permissionData)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Para a gravacao local (.mp4) da tela. */
        fun stopRecording(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP_RECORD))
        }
    }

    private var screenShareManager: ScreenShareManager? = null
    private var screenRecordManager: ScreenRecordManager? = null

    private var isSharing = false
    private var isRecording = false
    private var currentRecordingFilePath: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SHARE -> handleStartShare(intent)
            ACTION_STOP_SHARE -> handleStopShare()
            ACTION_START_RECORD -> handleStartRecord(intent)
            ACTION_STOP_RECORD -> handleStopRecord()
        }
        // START_NOT_STICKY: se o sistema matar o servico por falta de memoria,
        // NAO tentar recria-lo sozinho depois. Faz sentido aqui porque a
        // captura de tela exige uma permissao explicita do usuario que so
        // pode ser concedida de novo por uma tela do app - nao ha como
        // "continuar sozinho" sem o usuario.
        return START_NOT_STICKY
    }

    private fun handleStartShare(intent: Intent) {
        // Primeiro entra em primeiro piano (mostra a notificacao fixa),
        // SO DEPOIS pedimos o MediaProjection - essa ordem e obrigatoria a
        // partir do Android 10 (ver explicacao no topo do arquivo).
        startForeground(NOTIFICATION_ID, buildNotification())

        // OBS: o ScreenCapturerAndroid do WebRTC so precisa do Intent de
        // permissao (ele assume RESULT_OK, que e sempre o caso quando o
        // usuario concede a permissao) - por isso nao usamos EXTRA_RESULT_CODE
        // aqui como usamos na gravacao local (handleStartRecord).
        val permissionData = readParcelableExtra(intent, EXTRA_RESULT_DATA) ?: return

        val manager = ScreenShareManager(applicationContext)
        screenShareManager = manager

        val localVideoTrack = manager.startCapturing(permissionData) {
            // O sistema encerrou a projecao sozinho (ex: usuario revogou a
            // permissao pela barra de notificacoes do Android).
            handleStopShare()
            onSharingStoppedBySystem?.invoke()
        }

        isSharing = true
        refreshNotification()
        onLocalVideoTrackReady?.invoke(localVideoTrack)
    }

    private fun handleStopShare() {
        screenShareManager?.stopCapturing()
        screenShareManager = null
        isSharing = false
        stopServiceIfFullyIdle()
        refreshNotification()
    }

    private fun handleStartRecord(intent: Intent) {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val permissionData = readParcelableExtra(intent, EXTRA_RESULT_DATA) ?: return

        val manager = ScreenRecordManager(applicationContext)
        screenRecordManager = manager

        currentRecordingFilePath = manager.startRecording(resultCode, permissionData) {
            handleStopRecord()
            onRecordingStoppedBySystem?.invoke()
        }

        isRecording = true
        refreshNotification()
    }

    private fun handleStopRecord() {
        screenRecordManager?.stopRecording()
        screenRecordManager = null
        isRecording = false
        stopServiceIfFullyIdle()
        refreshNotification()

        currentRecordingFilePath?.let { savedPath -> onRecordingSaved?.invoke(savedPath) }
        currentRecordingFilePath = null
    }

    /** So encerra o servico quando NEM a transmissao NEM a gravacao estiverem ativas. */
    private fun stopServiceIfFullyIdle() {
        if (!isSharing && !isRecording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        if (isSharing || isRecording) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /** Texto da notificacao fixa muda de acordo com o que esta ativo no momento. */
    private fun buildNotification(): android.app.Notification {
        val statusText = when {
            isSharing && isRecording -> "Compartilhando e gravando a tela"
            isSharing -> "Compartilhando tela em tempo real"
            isRecording -> "Gravando tela localmente"
            else -> "Preparando captura de tela..."
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Aplicativo K")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true) // notificacao fixa - usuario nao consegue deslizar para remover
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Compartilhamento de tela",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificacao fixa exibida enquanto a tela esta sendo compartilhada ou gravada."
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Helper para ler um Intent extra Parcelable sem warning de depreciacao no Android 13+. */
    private fun readParcelableExtra(intent: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    override fun onDestroy() {
        // Rede de seguranca: garante que nada fique "vazando" mesmo se o
        // servico for encerrado de forma inesperada.
        screenShareManager?.stopCapturing()
        screenRecordManager?.stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
