package com.pedro.screenshare.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PermissionUtils
 * ---------------------------------------------------------------------------
 * Funcoes pequenas para checar permissoes em tempo de execucao.
 *
 * NAO tratamos aqui a permissao de "gravar tela" (MediaProjection) - essa e
 * diferente das permissoes normais do Android: ela e concedida atraves de
 * uma tela do sistema (chamada com createScreenCaptureIntent()) e o
 * resultado chega via ActivityResult, nao via ActivityCompat.requestPermissions.
 * Isso e tratado diretamente nas Activities (TransmitterActivity).
 */
object PermissionUtils {

    /**
     * A partir do Android 13 (API 33), mostrar QUALQUER notificacao (inclusive
     * a notificacao fixa do Foreground Service) exige que o usuario conceda a
     * permissao POST_NOTIFICATIONS. Em versoes mais antigas, essa permissao
     * nao existe e a notificacao sempre e permitida.
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true // Android < 13: nao existe essa permissao, ja "concedida"
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Nome da permissao de notificacao, usado ao pedir ela na Activity. */
    const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
}
