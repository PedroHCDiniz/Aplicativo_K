package com.pedro.screenshare.signaling

import android.os.Handler
import android.os.Looper
import com.pedro.screenshare.utils.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * SignalingClient
 * ---------------------------------------------------------------------------
 * Responsavel POR UMA UNICA COISA: manter a conexao WebSocket com o backend
 * de sinalizacao e enviar/receber mensagens JSON (SignalingMessage).
 *
 * Este arquivo NAO SABE NADA sobre WebRTC (PeerConnection, video, etc) nem
 * sobre a interface do app. Ele so entrega mensagens brutas via um
 * "listener" (callback). Quem decide o que fazer com cada mensagem e a tela
 * (TransmitterActivity/ViewerActivity) ou o WebRtcClient. Essa separacao
 * evita misturar "logica de rede" com "logica de video" ou "logica de tela".
 *
 * POR QUE E UM "object" (singleton)?
 * Porque so faz sentido existir UMA conexao WebSocket ativa por vez neste
 * app, e ela precisa sobreviver a trocas de tela (por exemplo, o usuario
 * pode sair da TransmitterActivity com o app ainda "online" no backend).
 * Um singleton simples e a forma mais direta de compartilhar essa conexao
 * entre as Activities e o Foreground Service, sem precisar de um framework
 * de injecao de dependencia para um MVP como este.
 */
object SignalingClient {

    /** Callback para quem quiser "escutar" eventos desta conexao. */
    interface Listener {
        fun onOpen()
        fun onMessage(message: SignalingMessage)
        fun onClosed()
        fun onError(description: String)
    }

    // So existe UM listener ativo por vez (a tela que estiver visivel no
    // momento). E simples e suficiente para este app.
    var listener: Listener? = null

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private var connectionGeneration = 0

    // Timeouts generosos porque WebSocket e uma conexao de longa duracao
    // (fica aberta o tempo todo, nao e uma requisicao rapida).
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // sem timeout de leitura (conexao permanente)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    // As respostas do servidor chegam em uma thread de rede (do OkHttp). Como
    // quem escuta (Activities) mexe em UI, sempre repassamos os callbacks
    // para a thread principal (main thread) usando este Handler.
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    fun isConnected(): Boolean = isConnected

    fun isConnecting(): Boolean = isConnecting

    /** Abre a conexao WebSocket com o servidor. Nao faz nada se ja estiver conectado. */
    fun connect(serverUrl: String) {
        if (isConnected || isConnecting) return
        isConnecting = true
        connectionGeneration += 1
        val generation = connectionGeneration

        val requestBuilder = Request.Builder().url(serverUrl)
        if (Constants.SIGNALING_AUTH_TOKEN.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${Constants.SIGNALING_AUTH_TOKEN}")
        }

        val request = requestBuilder.build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration) return
                isConnecting = false
                isConnected = true
                mainThreadHandler.post { listener?.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                val message = SignalingMessage.fromJson(text)
                mainThreadHandler.post { listener?.onMessage(message) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                isConnecting = false
                isConnected = false
                mainThreadHandler.post { listener?.onClosed() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration) return
                isConnecting = false
                isConnected = false
                mainThreadHandler.post { listener?.onError(t.message ?: "Erro de conexao desconhecido") }
            }
        })
    }

    /** Fecha qualquer socket antigo e abre um novo, util em troca Wi-Fi/dados moveis. */
    fun reconnect(serverUrl: String) {
        val oldSocket = webSocket
        connectionGeneration += 1
        webSocket = null
        isConnecting = false
        isConnected = false
        oldSocket?.close(1001, "Reconectando")
        connect(serverUrl)
    }

    /** Envia uma mensagem para o servidor. Nao faz nada se a conexao estiver fechada. */
    fun send(message: SignalingMessage) {
        webSocket?.send(message.toJson())
    }

    /** Fecha a conexao WebSocket de propósito (ex: usuario clicou em "Parar"). */
    fun disconnect() {
        connectionGeneration += 1
        webSocket?.close(1000, "Desconectado pelo usuario")
        webSocket = null
        isConnecting = false
        isConnected = false
    }
}
