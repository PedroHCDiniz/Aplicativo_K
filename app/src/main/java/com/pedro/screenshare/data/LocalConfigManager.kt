package com.pedro.screenshare.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pedro.screenshare.utils.Constants

/**
 * LocalConfigManager
 * ---------------------------------------------------------------------------
 * Responsavel por SALVAR e LER, de forma segura e local (so neste aparelho),
 * qual e o papel do aparelho (TRANSMISSOR ou VISUALIZADOR).
 *
 * PONTO IMPORTANTE - por que "EncryptedSharedPreferences" e nao o
 * SharedPreferences comum?
 * O SharedPreferences normal salva um arquivo XML em texto puro dentro do
 * armazenamento do app. Em um aparelho "rooteado" ou com acesso fisico, esse
 * arquivo poderia ser lido/alterado por fora. O EncryptedSharedPreferences
 * (biblioteca androidx.security) criptografa tanto as CHAVES quanto os
 * VALORES automaticamente, usando uma chave mestra gerada e guardada no
 * Android Keystore (uma area segura de hardware/SO). Para este app isso
 * garante que ninguem consiga adulterar facilmente "qual e o papel deste
 * aparelho" nem inspecionar esse dado copiando o arquivo de preferencias.
 *
 * Esta classe e o UNICO lugar do app que sabe como o papel e persistido.
 * Todo o resto do app (Activities, etc) so chama os metodos publicos daqui -
 * eles nao sabem (nem precisam saber) que por baixo existe criptografia.
 */
class LocalConfigManager(context: Context) {

    private val securePrefs: SharedPreferences

    init {
        // MasterKey: chave mestra usada para criptografar/descriptografar as
        // preferencias. E gerada automaticamente na primeira vez e guardada
        // no Android Keystore (nunca fica exposta em texto puro).
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        securePrefs = EncryptedSharedPreferences.create(
            context,
            Constants.PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Salva o aparelho como TRANSMISSOR ou VISUALIZADOR, junto com a sala
     * fixa. Chamado uma unica vez, na tela de configuracao inicial
     * (SetupActivity), quando o usuario digita um dos codigos corretos.
     */
    fun saveRole(role: UserRole) {
        securePrefs.edit()
            .putString(Constants.KEY_USER_ROLE, role.name)
            .putString(Constants.KEY_ROOM_ID, Constants.ROOM_ID)
            .putInt(Constants.KEY_CONFIG_VERSION, Constants.CURRENT_CONFIG_VERSION)
            .apply()
    }

    /**
     * Le o papel salvo do aparelho. Retorna null se o aparelho ainda nao
     * passou pela configuracao inicial (primeira vez que o app e aberto).
     */
    fun getRole(): UserRole? {
        if (securePrefs.getInt(Constants.KEY_CONFIG_VERSION, 0) != Constants.CURRENT_CONFIG_VERSION) {
            return null
        }
        return UserRole.fromStorageValue(securePrefs.getString(Constants.KEY_USER_ROLE, null))
    }

    /** Retorna a sala salva (sempre a sala fixa, ver Constants.ROOM_ID). */
    fun getRoomId(): String {
        return securePrefs.getString(Constants.KEY_ROOM_ID, Constants.ROOM_ID) ?: Constants.ROOM_ID
    }

    /** Verdadeiro se o aparelho ja passou pela configuracao inicial. */
    fun isConfigured(): Boolean {
        return getRole() != null
    }

    /**
     * "Redefinir configuracao": apaga o papel salvo, fazendo o app voltar a
     * pedir o codigo de configuracao na proxima vez que for aberto.
     */
    fun resetConfig() {
        securePrefs.edit().clear().apply()
    }
}
