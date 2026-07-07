package com.pedro.screenshare.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * ScreenShareManager
 * ---------------------------------------------------------------------------
 * Responsavel por transformar a permissao de "gravar tela" (MediaProjection)
 * em uma VideoTrack do WebRTC, que pode entao ser enviada para os
 * visualizadores atraves de uma PeerConnection (ver WebRtcClient).
 *
 * Esta classe NAO sabe nada sobre WebSocket ou sobre PeerConnection - ela so
 * entrega uma VideoTrack pronta. Isso mantem a captura de tela separada da
 * logica de sinalizacao e de conexao WebRTC.
 *
 * CONCEITO IMPORTANTE - MediaProjection:
 * E a API do Android que permite capturar tudo o que esta sendo desenhado na
 * tela do aparelho, como se fosse uma "camera" apontada para a propria tela.
 * Por questoes de privacidade, o Android SEMPRE mostra uma caixa de dialogo
 * do sistema pedindo autorizacao explicita do usuario antes de qualquer app
 * conseguir usar isso (ver MediaProjectionManager.createScreenCaptureIntent(),
 * chamado em TransmitterActivity). O resultado dessa autorizacao (um Intent)
 * e o que recebemos aqui em `startCapturing`.
 *
 * A biblioteca do WebRTC ja possui uma classe pronta, `ScreenCapturerAndroid`,
 * que sabe transformar esse Intent de autorizacao em frames de video - por
 * isso nao precisamos mexer diretamente com VirtualDisplay/ImageReader aqui.
 */
class ScreenShareManager(private val context: Context) {

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    /**
     * Inicia a captura da tela e devolve a VideoTrack pronta para ser usada
     * em uma PeerConnection.
     *
     * @param screenCapturePermissionData O Intent de resultado devolvido pelo
     *        dialogo de permissao do Android (MediaProjectionManager).
     * @param onProjectionStopped Chamado se o sistema encerrar a captura por
     *        conta propria (ex: usuario revogou a permissao pela barra de
     *        status do Android) - usado para o app atualizar a UI e liberar
     *        recursos corretamente.
     */
    fun startCapturing(
        screenCapturePermissionData: Intent,
        onProjectionStopped: () -> Unit
    ): VideoTrack {
        val factory = PeerConnectionFactoryProvider.getFactory(context)
        val eglBaseContext = PeerConnectionFactoryProvider.eglBaseContext

        // SurfaceTextureHelper: "ponte" entre a superficie de captura de tela
        // (OpenGL) e o formato de frame que o WebRTC entende.
        val textureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBaseContext)
        surfaceTextureHelper = textureHelper

        // isScreencast = true avisa o WebRTC que esta e uma fonte de "tela",
        // permitindo otimizacoes de codificacao adequadas para esse tipo de
        // conteudo (texto/UI estatica), diferente de video de camera.
        val source = factory.createVideoSource(true)
        videoSource = source

        val capturer = ScreenCapturerAndroid(
            screenCapturePermissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // O sistema Android encerrou a projecao (ex: usuario
                    // revogou a permissao). Avisamos quem estiver escutando
                    // para parar a UI/servico corretamente.
                    onProjectionStopped()
                }
            }
        )
        screenCapturer = capturer
        capturer.initialize(textureHelper, context, source.capturerObserver)

        val (width, height) = getScreenSize()
        // 30 fps e um valor razoavel para compartilhamento de tela (nao
        // precisa de mais que isso para texto/UI, e economiza banda/bateria).
        capturer.startCapture(width, height, 30)

        val track = factory.createVideoTrack("ScreenShareVideoTrack", source)
        videoTrack = track
        return track
    }

    /**
     * Para a captura e libera TODOS os recursos usados (MediaProjection,
     * VirtualDisplay/Surface internos do ScreenCapturerAndroid, VideoSource e
     * VideoTrack). Chamado quando o usuario clica em "Parar compartilhamento".
     *
     * E fundamental liberar tudo aqui: MediaProjection e um recurso do
     * sistema operacional (nao so um objeto Kotlin comum) - se nao for
     * liberado, a tela continua sendo capturada mesmo sem ninguem usando.
     */
    fun stopCapturing() {
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    /** Le a resolucao atual da tela, usada para configurar a captura no mesmo tamanho. */
    private fun getScreenSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }
}
