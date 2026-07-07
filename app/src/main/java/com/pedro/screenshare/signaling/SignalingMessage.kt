package com.pedro.screenshare.signaling

import org.json.JSONObject

/**
 * SignalingMessage
 * ---------------------------------------------------------------------------
 * Representa uma mensagem trocada com o servidor de sinalizacao.
 *
 * O protocolo e JSON simples, por exemplo:
 *   { "type": "offer", "roomId": "sala-pedro-principal", "sdp": "...", "viewerId": "abc" }
 *
 * Usamos um unico "molde" (data class) com campos opcionais (nullable) em
 * vez de uma classe para cada tipo de mensagem, porque isso deixa o codigo de
 * enviar/receber muito mais simples de ler - sem precisar de "sealed class"
 * com varios subtipos so para representar um JSON pequeno.
 *
 * Usamos org.json (ja vem no Android, sem precisar adicionar biblioteca) em
 * vez de Gson/Moshi, para manter a conversao JSON <-> objeto bem explicita e
 * facil de acompanhar linha a linha (sem "magica" de reflexao/anotacoes).
 */
data class SignalingMessage(
    val type: String,
    val roomId: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val viewerId: String? = null,
    val viewerIds: List<String>? = null,
    val message: String? = null
) {
    /** Converte esta mensagem para uma String JSON, pronta para enviar pelo WebSocket. */
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        roomId?.let { json.put("roomId", it) }
        sdp?.let { json.put("sdp", it) }
        candidate?.let { json.put("candidate", it) }
        sdpMid?.let { json.put("sdpMid", it) }
        sdpMLineIndex?.let { json.put("sdpMLineIndex", it) }
        viewerId?.let { json.put("viewerId", it) }
        viewerIds?.let { json.put("viewerIds", it) }
        message?.let { json.put("message", it) }
        return json.toString()
    }

    companion object {
        /** Converte uma String JSON recebida do WebSocket de volta para um objeto. */
        fun fromJson(raw: String): SignalingMessage {
            val json = JSONObject(raw)
            return SignalingMessage(
                type = json.getString("type"),
                roomId = json.optStringOrNull("roomId"),
                sdp = json.optStringOrNull("sdp"),
                candidate = json.optStringOrNull("candidate"),
                sdpMid = json.optStringOrNull("sdpMid"),
                sdpMLineIndex = if (json.has("sdpMLineIndex")) json.getInt("sdpMLineIndex") else null,
                viewerId = json.optStringOrNull("viewerId"),
                viewerIds = json.optJSONArrayAsStringListOrNull("viewerIds"),
                message = json.optStringOrNull("message")
            )
        }

        // --- Pequenos helpers para o JSONObject nao devolver a String "null"
        // (comportamento padrao do org.json quando o campo nao existe). ---

        private fun JSONObject.optStringOrNull(key: String): String? {
            return if (has(key) && !isNull(key)) getString(key) else null
        }

        private fun JSONObject.optJSONArrayAsStringListOrNull(key: String): List<String>? {
            if (!has(key) || isNull(key)) return null
            val array = getJSONArray(key)
            return (0 until array.length()).map { index -> array.getString(index) }
        }
    }
}
