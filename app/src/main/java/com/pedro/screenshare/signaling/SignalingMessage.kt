package com.pedro.screenshare.signaling

import org.json.JSONObject

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long
)

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
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val timestamp: Long? = null,
    val routePoints: List<RoutePoint>? = null,
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
        latitude?.let { json.put("latitude", it) }
        longitude?.let { json.put("longitude", it) }
        accuracy?.let { json.put("accuracy", it.toDouble()) }
        timestamp?.let { json.put("timestamp", it) }
        routePoints?.let { points ->
            json.put("routePoints", org.json.JSONArray().apply {
                points.forEach { point ->
                    put(JSONObject().apply {
                        put("latitude", point.latitude)
                        put("longitude", point.longitude)
                        put("accuracy", point.accuracy.toDouble())
                        put("timestamp", point.timestamp)
                    })
                }
            })
        }
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
                latitude = if (json.has("latitude") && !json.isNull("latitude")) json.getDouble("latitude") else null,
                longitude = if (json.has("longitude") && !json.isNull("longitude")) json.getDouble("longitude") else null,
                accuracy = if (json.has("accuracy") && !json.isNull("accuracy")) json.getDouble("accuracy").toFloat() else null,
                timestamp = if (json.has("timestamp") && !json.isNull("timestamp")) json.getLong("timestamp") else null,
                routePoints = json.optJSONArrayAsRoutePointsOrNull("routePoints"),
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

        private fun JSONObject.optJSONArrayAsRoutePointsOrNull(key: String): List<RoutePoint>? {
            if (!has(key) || isNull(key)) return null
            val array = getJSONArray(key)
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val latitude = if (item.has("latitude")) item.getDouble("latitude") else return@mapNotNull null
                val longitude = if (item.has("longitude")) item.getDouble("longitude") else return@mapNotNull null
                RoutePoint(
                    latitude = latitude,
                    longitude = longitude,
                    accuracy = if (item.has("accuracy")) item.getDouble("accuracy").toFloat() else 0f,
                    timestamp = if (item.has("timestamp")) item.getLong("timestamp") else 0L
                )
            }
        }
    }
}
