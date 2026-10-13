package es.peritaje.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Token app <-> PC cifrado con una clave del Android Keystore.
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

    var token: String
        get() = prefs.getString(CLAVE, "") ?: ""
        set(valor) = prefs.edit().putString(CLAVE, valor.trim()).apply()

    private companion object {
        const val CLAVE = "token_pc"
    }
}
