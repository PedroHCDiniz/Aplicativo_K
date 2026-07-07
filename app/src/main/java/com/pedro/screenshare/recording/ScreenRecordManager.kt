package com.pedro.screenshare.recording

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.pedro.screenshare.utils.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ScreenRecordManager
 * ---------------------------------------------------------------------------
 * FUNCIONALIDADE EXTRA (nao faz parte da transmissao ao vivo): grava a tela
 * do TRANSMISSOR em um arquivo de video local (.mp4), de forma totalmente
 * independente do compartilhamento ao vivo via WebRTC. O usuario pode gravar
 * sem transmitir, transmitir sem gravar, ou os dois ao mesmo tempo.
 *
 * IMPORTANTE - por que isso e uma classe SEPARADA do ScreenShareManager?
 * Porque sao dois "consumidores" diferentes dos mesmos frames de tela:
 *   - ScreenShareManager entrega os frames para o WebRTC (para ENVIAR ao
 *     vivo para o visualizador).
 *   - ScreenRecordManager entrega os frames para um MediaRecorder (para
 *     SALVAR em um arquivo .mp4 no proprio aparelho).
 * Cada um pede sua PROPRIA permissao de MediaProjection (o Android exige uma
 * nova autorizacao do usuario para cada captura de tela iniciada), e cada um
 * cria seu proprio VirtualDisplay - por isso os dois podem ligar/desligar de
 * forma independente, sem um interferir no outro.
 *
 * PRIVACIDADE: o video gravado fica salvo em armazenamento PRIVADO do app
 * (pasta interna acessivel so por este app), nunca e enviado para o
 * servidor nem para o visualizador - e apenas um arquivo local no
 * transmissor. Ver Constants.RECORDINGS_FOLDER_NAME.
 */
class ScreenRecordManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    /**
     * Inicia a gravacao da tela em um arquivo .mp4 local.
     *
     * @param resultCode Codigo de resultado devolvido pelo dialogo de
     *        permissao do Android (deve ser Activity.RESULT_OK).
     * @param permissionData Intent de autorizacao devolvido pelo mesmo dialogo.
     * @param onProjectionStopped Chamado se o Android encerrar a gravacao por
     *        conta propria (ex: permissao revogada pela barra de status).
     * @return O caminho completo do arquivo .mp4 que esta sendo gravado.
     */
    fun startRecording(
        resultCode: Int,
        permissionData: Intent,
        onProjectionStopped: () -> Unit
    ): String {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, permissionData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                onProjectionStopped()
            }
        }, null)

        val screenInfo = readScreenInfo()
        val outputFile = createOutputFile()

        val recorder = createMediaRecorderInstance()
        recorder.apply {
            // SURFACE = os frames vem de uma Surface (a mesma que vamos passar
            // para o VirtualDisplay), em vez de vir de uma camera/microfone.
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(screenInfo.widthPixels, screenInfo.heightPixels)
            setVideoEncodingBitRate(8_000_000) // 8 Mbps - boa qualidade para tela (texto legivel)
            setVideoFrameRate(30)
            setOutputFile(outputFile.absolutePath)
            // prepare() PRECISA ser chamado antes de pedir a Surface abaixo.
            prepare()
        }
        mediaRecorder = recorder

        // Cria uma tela virtual que "espelha" a tela real diretamente dentro
        // da Surface do MediaRecorder - e assim que os frames chegam ate o
        // gravador, sem nenhum processamento manual de imagem por nossa parte.
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecordVirtualDisplay",
            screenInfo.widthPixels,
            screenInfo.heightPixels,
            screenInfo.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            null
        )

        recorder.start()
        return outputFile.absolutePath
    }

    /**
     * Para a gravacao e libera TODOS os recursos (MediaRecorder, VirtualDisplay
     * e MediaProjection). Assim como na transmissao ao vivo, e essencial
     * liberar esses recursos do sistema operacional explicitamente - eles nao
     * sao apenas objetos Kotlin comuns, sao recursos de captura de tela ativos.
     */
    fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (error: Exception) {
            // stop() pode lancar excecao se a gravacao foi interrompida cedo
            // demais (ex: menos de 1 frame gravado) - ignoramos com seguranca,
            // pois o objetivo aqui e so garantir que os recursos sejam
            // liberados de qualquer forma (bloco abaixo).
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * A partir do Android 12 (API 31), o construtor `MediaRecorder()` sem
     * Context foi descontinuado em favor de `MediaRecorder(context)`.
     * Mantemos os dois caminhos para funcionar do minSdk ate as versoes mais
     * novas do Android.
     */
    private fun createMediaRecorderInstance(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    /** Cria (se necessario) a pasta privada do app e devolve o arquivo .mp4 de destino. */
    private fun createOutputFile(): File {
        val recordingsDir = File(context.getExternalFilesDir(null), Constants.RECORDINGS_FOLDER_NAME)
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(recordingsDir, "gravacao_$timestamp.mp4")
    }

    private data class ScreenInfo(val widthPixels: Int, val heightPixels: Int, val densityDpi: Int)

    private fun readScreenInfo(): ScreenInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return ScreenInfo(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
    }
}
