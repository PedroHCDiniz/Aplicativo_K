package com.pedro.screenshare.data

/**
 * UserRole
 * ---------------------------------------------------------------------------
 * Representa o "papel" (tipo de uso) que este aparelho tem no app.
 *
 * Um mesmo app pode se comportar como TRANSMISSOR (quem compartilha a tela)
 * ou VISUALIZADOR (quem assiste). Esse papel e escolhido UMA VEZ na primeira
 * configuracao (SetupActivity) e depois fica salvo localmente (ver
 * LocalConfigManager), entao o app "lembra" para sempre qual e o seu papel.
 */
enum class UserRole {
    TRANSMITTER,
    VIEWER;

    companion object {
        /**
         * Converte o texto salvo nas preferencias de volta para o enum.
         * Retorna null se o texto nao for reconhecido (ex: aparelho ainda nao
         * configurado, ou preferencia corrompida).
         */
        fun fromStorageValue(value: String?): UserRole? {
            return values().firstOrNull { it.name == value }
        }
    }
}
