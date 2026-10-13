package es.peritaje.app.data.fotos

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.FotoDao
import es.peritaje.app.data.db.FotoPendiente
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Guarda las fotos como fotos normales de la galería, en Pictures/PeritajeApp/AAAA-MM/
 * (mes de la visita), con nombre <id_encargo>_NN.jpg. La app nunca las borra sola.
 */
class AlmacenFotos(private val context: Context, private val fotos: FotoDao) {

    /** Hueco reservado para una foto. [uriEscritura] es donde escribe la cámara; [uriGuardada] la que se apunta en Room. */
    data class Destino(val uriEscritura: Uri, val uriGuardada: Uri, val nombre: String, val numero: Int)

    private val resolver get() = context.contentResolver

    private fun carpetaMes(cita: Cita): String =
        DateTimeFormatter.ofPattern("yyyy-MM").format(
            Instant.ofEpochMilli(cita.fechaHora).atZone(ZoneId.systemDefault())
        )

    suspend fun preparar(cita: Cita): Destino = withContext(Dispatchers.IO) {
        val numero = (fotos.maxNumero(cita.idEncargo) ?: 0) + 1
        val nombre = "%s_%02d.jpg".format(cita.idEncargo, numero)
        val mes = carpetaMes(cita)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val valores = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$CARPETA/$mes")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores)
                ?: error("No se pudo crear la foto en la galería")
            Destino(uri, uri, nombre, numero)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "$CARPETA/$mes")
            dir.mkdirs()
            val archivo = File(dir, nombre)
            val uriCamara = FileProvider.getUriForFile(context, "${context.packageName}.fotos", archivo)
            Destino(uriCamara, Uri.fromFile(archivo), nombre, numero)
        }
    }

    /** La cámara (o la copia desde la galería) terminó bien: se publica y se apunta en Room. */
    suspend fun confirmar(cita: Cita, destino: Destino) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(destino.uriEscritura, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } else {
            destino.uriGuardada.path?.let { MediaScannerConnection.scanFile(context, arrayOf(it), arrayOf("image/jpeg"), null) }
        }
        fotos.insertar(
            FotoPendiente(
                idEncargo = cita.idEncargo,
                uriMediastore = destino.uriGuardada.toString(),
                nombreArchivo = destino.nombre,
                numero = destino.numero,
                fechaCaptura = System.currentTimeMillis(),
            )
        )
    }

    /** La captura se canceló: se elimina el hueco vacío. */
    suspend fun descartar(destino: Destino) = withContext(Dispatchers.IO) {
        runCatching {
            if (destino.uriGuardada.scheme == "file") destino.uriGuardada.path?.let { File(it).delete() }
            else resolver.delete(destino.uriEscritura, null, null)
        }
    }

    /** Copia una foto elegida de la galería al expediente, con el nombre y carpeta de la app. */
    suspend fun importar(cita: Cita, origen: Uri) = withContext(Dispatchers.IO) {
        val destino = preparar(cita)
        try {
            resolver.openInputStream(origen).use { entrada ->
                resolver.openOutputStream(destino.uriEscritura).use { salida ->
                    requireNotNull(entrada) { "No se pudo leer la foto" }
                    requireNotNull(salida) { "No se pudo escribir la foto" }
                    entrada.copyTo(salida)
                }
            }
            confirmar(cita, destino)
        } catch (e: Exception) {
            descartar(destino)
            throw e
        }
    }

    /** Quita la foto del expediente y de la galería (solo mientras la visita está abierta). */
    suspend fun borrar(foto: FotoPendiente) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(foto.uriMediastore)
        runCatching {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() } else resolver.delete(uri, null, null)
        }
        fotos.borrar(foto)
    }

    companion object {
        const val CARPETA = "PeritajeApp"
    }
}
