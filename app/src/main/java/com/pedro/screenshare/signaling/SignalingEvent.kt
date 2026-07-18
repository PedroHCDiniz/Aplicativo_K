package com.pedro.screenshare.signaling

/**
 * SignalingEvent
 * ---------------------------------------------------------------------------
 * Lista de todos os "tipos" de mensagem que trafegam entre o app e o
 * servidor de sinalizacao (server.js), via WebSocket.
 *
 * Usamos texto simples (String) para o campo "type" das mensagens JSON, em
 * vez de numeros ou enums complexos, para o protocolo ficar facil de ler e
 * de depurar (dá pra ver a mensagem crua no log e entender na hora).
 *
 * Estes nomes SAO EXATAMENTE OS MESMOS usados no server.js - se mudar aqui,
 * tem que mudar la tambem.
 */
object SignalingEvent {

    // --- Enviados pelo APP para o SERVIDOR ---

    /** Transmissor avisa o servidor que quer se registrar como transmissor da sala. */
    const val REGISTER_TRANSMITTER = "register-transmitter"

    /** Visualizador avisa o servidor que quer se registrar como visualizador da sala. */
    const val REGISTER_VIEWER = "register-viewer"

    /** Visualizador pede para o servidor avisar o transmissor que alguem quer assistir. */
    const val START_SHARE_REQUEST = "start-share-request"

    /** Transmissor avisa que comecou a capturar/enviar a tela. */
    const val SHARING_STARTED = "sharing-started"

    /** Transmissor avisa que parou de compartilhar. */
    const val SHARING_STOPPED = "sharing-stopped"

    /** Oferta WebRTC (SDP) - enviada pelo transmissor para um visualizador especifico. */
    const val OFFER = "offer"

    /** Resposta WebRTC (SDP) - enviada pelo visualizador de volta para o transmissor. */
    const val ANSWER = "answer"

    /** Candidato de rede (endereco/porta) trocado entre os dois lados da conexao WebRTC. */
    const val ICE_CANDIDATE = "ice-candidate"

    /** Transmissor envia um novo ponto da rota do dia. */
    const val ROUTE_POINT = "route-point"

    /** Transmissor limpa a rota atual no servidor. */
    const val ROUTE_CLEAR = "route-clear"

    /** Servidor envia a rota atual para um visualizador que acabou de entrar. */
    const val ROUTE_HISTORY = "route-history"

    // --- Enviados pelo SERVIDOR para o APP ---

    /** Avisa um visualizador que o transmissor esta online (conectado). */
    const val TRANSMITTER_ONLINE = "transmitter-online"

    /** Avisa um visualizador que o transmissor desconectou. */
    const val TRANSMITTER_OFFLINE = "transmitter-offline"

    /** Avisa o transmissor que um novo visualizador entrou na sala. */
    const val VIEWER_ONLINE = "viewer-online"

    /** Avisa o transmissor que um visualizador saiu da sala (limpeza de recursos). */
    const val VIEWER_OFFLINE = "viewer-offline"

    /** Mensagem de erro generica vinda do servidor. */
    const val ERROR = "error"
}
