package es.peritaje.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import es.peritaje.app.data.red.Modo

/**
 * Credenciales (token de Notion y token app <-> PC) cifradas con una clave del Android Keystore.
 * No se guarda en Room a propósito (ver especificación, sección Seguridad).
 */
class AlmacenToken(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val clave = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "credenciales",
            clave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Token app <-> PC (modo PC). */
    var token: String
        get() = prefs.getString(CLAVE_PC, "") ?: ""
        set(valor) = prefs.edit().putString(CLAVE_PC, valor.trim()).apply()

    /** Token de la integración de Notion (modo Notion directo). */
    var tokenNotion: String
        get() = prefs.getString(CLAVE_NOTION, "") ?: ""
        set(valor) = prefs.edit().putString(CLAVE_NOTION, valor.trim()).apply()

    var modo: Modo
        get() = runCatching { Modo.valueOf(prefs.getString(CLAVE_MODO, null) ?: "") }.getOrDefault(Modo.NOTION)
        set(valor) = prefs.edit().putString(CLAVE_MODO, valor.name).apply()

    private companion object {
        const val CLAVE_PC = "token_pc"
        const val CLAVE_NOTION = "token_notion"
        const val CLAVE_MODO = "modo"
    }
}
