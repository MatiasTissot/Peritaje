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
import es.peritaje.app.data.db.CitaDao
import es.peritaje.app.data.db.FotoDao
import es.peritaje.app.data.db.FotoPendiente
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Guarda las fotos como fotos normales de la galería, en Pictures/PeritajeApp/<expediente>/,
 * con la fecha y hora de la foto como nombre (AAAA-MM-DD_HH-MM-SS.jpg).
 *
 * Regla principal: **una foto nunca se pierde**.
 * - La cámara escribe la foto original, a máxima calidad, directamente en su archivo definitivo.
 * - Antes de abrir la cámara se apunta la captura en curso en disco ([capturas]); si Android
 *   cierra la app mientras la cámara está abierta, al volver se recupera.
 * - Un archivo con contenido nunca se borra, aunque la cámara diga "cancelado".
 * - La app no borra nunca archivos de la galería por su cuenta.
 */
class AlmacenFotos(
    private val context: Context,
    private val fotos: FotoDao,
    private val citas: CitaDao,
) {

    /** Hueco reservado para una foto. [uriEscritura] es donde escribe la cámara; [uriGuardada] la que se apunta en Room. */
    data class Destino(
        val idVisita: String,
        val idEncargo: String,
        val uriEscritura: Uri,
        val uriGuardada: Uri,
        val nombre: String,
        val numero: Int,
        val creada: Long = System.currentTimeMillis(),
    ) {
        fun aJson(): String = JSONObject()
            .put("id", idVisita).put("encargo", idEncargo).put("escritura", uriEscritura.toString()).put("guardada", uriGuardada.toString())
            .put("nombre", nombre).put("numero", numero).put("creada", creada)
            .toString()

        companion object {
            fun deJson(s: String): Destino? = runCatching {
                val o = JSONObject(s)
                Destino(
                    o.getString("id"), o.optString("encargo", o.getString("id")), Uri.parse(o.getString("escritura")), Uri.parse(o.getString("guardada")),
                    o.getString("nombre"), o.getInt("numero"), o.getLong("creada"),
                )
            }.getOrNull()
        }
    }

    private val resolver get() = context.contentResolver
    private val mutex = Mutex()

    /** Capturas en curso, guardadas en disco (no en memoria) para sobrevivir a que Android cierre la app. */
    private val capturas = context.getSharedPreferences("capturas_en_curso", Context.MODE_PRIVATE)

    /** Carpeta del expediente (sin caracteres que no admita el sistema de archivos). */
    private fun carpeta(cita: Cita): String = cita.idEncargo.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sin_expediente" }

    private var ultimaBase = ""
    private var repetidas = 0

    /**
     * Fecha y hora de la foto. Si caen varias en el mismo segundo (p. ej. al importar de la galería)
     * se añade _2, _3... Además la galería nunca sobrescribe: si el nombre existe, le añade un sufijo.
     */
    private fun nombrePorHora(): String {
        val base = NOMBRE.format(LocalDateTime.now())
        if (base == ultimaBase) repetidas++ else { ultimaBase = base; repetidas = 1 }
        return if (repetidas == 1) "$base.jpg" else "${base}_$repetidas.jpg"
    }

    private fun siguienteNumero(idVisita: String, enBd: Int?): Int {
        val enCurso = pendientes().filter { it.idVisita == idVisita }.maxOfOrNull { it.numero } ?: 0
        return maxOf(enBd ?: 0, enCurso) + 1
    }

    /** Reserva el archivo de la siguiente foto y lo apunta como captura en curso. */
    suspend fun preparar(cita: Cita): Destino = withContext(Dispatchers.IO) {
        mutex.withLock {
            val numero = siguienteNumero(cita.idVisita, fotos.maxNumero(cita.idVisita))
            val nombre = nombrePorHora()
            val subcarpeta = carpeta(cita)
            val destino = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val valores = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$CARPETA/$subcarpeta")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores)
                    ?: error("No se pudo crear la foto en la galería")
                Destino(cita.idVisita, cita.idEncargo, uri, uri, nombre, numero)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "$CARPETA/$subcarpeta")
                dir.mkdirs()
                var archivo = File(dir, nombre)
                var n = 1
                while (archivo.exists()) archivo = File(dir, nombre.removeSuffix(".jpg") + " (${n++}).jpg")
                val uriCamara = FileProvider.getUriForFile(context, "${context.packageName}.fotos", archivo)
                Destino(cita.idVisita, cita.idEncargo, uriCamara, Uri.fromFile(archivo), archivo.name, numero)
            }
            // commit() y no apply(): tiene que estar en disco antes de abrir la cámara.
            capturas.edit().putString(destino.uriGuardada.toString(), destino.aJson()).commit()
            destino
        }
    }

    private fun tamano(d: Destino): Long = runCatching {
        if (d.uriGuardada.scheme == "file") File(d.uriGuardada.path!!).length()
        else resolver.openFileDescriptor(d.uriEscritura, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    /** Nombre real en la galería (Android añade " (1)" si ya existía uno igual). */
    private fun nombreReal(d: Destino): String {
        if (d.uriGuardada.scheme == "file") return File(d.uriGuardada.path!!).name
        return runCatching {
            resolver.query(d.uriEscritura, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: d.nombre
    }

    /** Hace visible el archivo en la galería. */
    private fun publicar(d: Destino) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && d.uriGuardada.scheme != "file") {
            resolver.update(d.uriEscritura, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } else {
            d.uriGuardada.path?.let { MediaScannerConnection.scanFile(context, arrayOf(it), arrayOf("image/jpeg"), null) }
        }
    }

    /**
     * Cierra una captura. Si el archivo tiene contenido, se publica y se apunta en el expediente
     * (aunque la cámara haya devuelto "cancelado"). Devuelve true si hay foto.
     * Si está vacío solo se borra cuando [borrarSiVacio] (la cámara ya terminó).
     */
    private suspend fun cerrar(d: Destino, borrarSiVacio: Boolean): Boolean {
        val clave = d.uriGuardada.toString()
        if (tamano(d) > 0) {
            publicar(d)
            val cita = citas.obtener(d.idVisita)
            if (cita != null && fotos.existeUri(clave) == 0) {
                fotos.insertar(
                    FotoPendiente(
                        idVisita = d.idVisita,
                        idEncargo = d.idEncargo,
                        uriMediastore = clave,
                        nombreArchivo = nombreReal(d),
                        numero = d.numero,
                        fechaCaptura = d.creada,
                    )
                )
            }
            // Si la visita ya no existe, la foto queda igualmente en la galería.
            capturas.edit().remove(clave).commit()
            return true
        }
        if (borrarSiVacio) {
            runCatching {
                if (d.uriGuardada.scheme == "file") d.uriGuardada.path?.let { File(it).delete() }
                else resolver.delete(d.uriEscritura, null, null)
            }
            capturas.edit().remove(clave).commit()
        }
        return false
    }

    /**
     * La cámara ha vuelto (con o sin éxito). Revisa todas las capturas en curso de esa visita,
     * también las que quedaron de una vez en que Android cerró la app. Devuelve cuántas fotos se guardaron.
     */
    suspend fun alVolverDeCamara(idVisita: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            pendientes().filter { it.idVisita == idVisita }.count { cerrar(it, borrarSiVacio = true) }
        }
    }

    /**
     * Al abrir la app: rescata cualquier foto que se quedara a medias. Las vacías recientes se
     * dejan (la cámara podría seguir abierta); las vacías de más de un día se limpian.
     */
    suspend fun recuperarCapturas(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ahora = System.currentTimeMillis()
            pendientes().count { cerrar(it, borrarSiVacio = ahora - it.creada > TimeUnit.DAYS.toMillis(1)) }
        }
    }

    /** true si hay una foto de esa visita a medio hacer (cámara abierta o app cerrada con la cámara abierta). */
    fun hayCapturaEnCurso(idVisita: String): Boolean = pendientes().any { it.idVisita == idVisita }

    private fun pendientes(): List<Destino> =
        capturas.all.values.mapNotNull { (it as? String)?.let(Destino::deJson) }

    /** Copia una foto elegida de la galería al expediente, byte a byte (misma calidad), con el nombre de la app. */
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
        } catch (e: Exception) {
            // Copia a medias: se descarta la copia (la foto original sigue intacta en la galería).
            runCatching {
                if (destino.uriGuardada.scheme == "file") destino.uriGuardada.path?.let { File(it).delete() }
                else resolver.delete(destino.uriEscritura, null, null)
            }
            capturas.edit().remove(destino.uriGuardada.toString()).commit()
            throw e
        }
        mutex.withLock { cerrar(destino, borrarSiVacio = true) }
    }

    /** Quita la foto del expediente. El archivo se queda en la galería: la app nunca borra fotos. */
    suspend fun quitar(foto: FotoPendiente) = withContext(Dispatchers.IO) { fotos.quitar(foto.id) }

    /** URI para abrir la foto en el visor del móvil (en Android 8-9 hay que pasar por FileProvider). */
    fun uriParaVer(foto: FotoPendiente): Uri {
        val uri = Uri.parse(foto.uriMediastore)
        if (uri.scheme != "file") return uri
        return FileProvider.getUriForFile(context, "${context.packageName}.fotos", File(uri.path!!))
    }

    companion object {
        const val CARPETA = "PeritajeApp"
        private val NOMBRE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
