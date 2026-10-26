package es.peritaje.app.data.fotos

import android.content.Context
import android.net.Uri
import es.peritaje.app.data.SinConfiguracion
import es.peritaje.app.data.db.CitaDao
import es.peritaje.app.data.db.EstadoSubida
import es.peritaje.app.data.db.FotoDao
import es.peritaje.app.data.db.FotoPendiente
import es.peritaje.app.data.red.PcApi
import es.peritaje.app.trabajo.Planificador
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import retrofit2.HttpException
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Punto de enganche de la subida de fotos: se llama al finalizar una visita. */
interface SubidorFotos {
    suspend fun encolar(idVisita: String, idEncargo: String)
}

object SubidorFotosDesactivado : SubidorFotos {
    override suspend fun encolar(idVisita: String, idEncargo: String) = Unit
}

/** Resultado de una pasada de subida. */
sealed interface ResultadoSubida {
    data object Terminado : ResultadoSubida
    data class Reintentar(val motivo: String) : ResultadoSubida
}

/**
 * Sube las fotos de las visitas finalizadas a Google Drive a través del PC (PUT /fotos), que las
 * deja en Expedientes_2026/<encargo>/Fotografias/Visita_<fecha>/ con el mismo nombre que en el móvil.
 *
 * Reglas:
 * - La foto se lee de la galería y se manda tal cual (calidad original). En el móvil no se borra
 *   ni se toca nada: la subida es una copia.
 * - Solo se marca SUBIDA cuando el PC responde "ok" (Drive ya la tiene entera). Si falla la red,
 *   el PC o Drive, queda pendiente y WorkManager lo reintenta cuando haya red.
 * - Reintentar es seguro: el PC reconoce una foto ya subida por su huella (sha256) y no la duplica.
 */
class SubidaFotos(
    private val context: Context,
    private val fotos: FotoDao,
    private val citas: CitaDao,
    private val api: suspend () -> PcApi,
) : SubidorFotos {

    override suspend fun encolar(idVisita: String, idEncargo: String) {
        Planificador.subirFotos(context)
    }

    /** Sube todas las fotos pendientes, una a una (así el PC, con poca memoria, va desahogado). */
    suspend fun subirPendientes(): ResultadoSubida = withContext(Dispatchers.IO) {
        val api = api()
        val probadas = mutableSetOf<Long>()
        var ultimoFallo: String? = null
        while (true) {
            // Se vuelve a consultar cada vez: puede haberse finalizado otra visita mientras tanto.
            val foto = fotos.porSubir().firstOrNull { it.id !in probadas } ?: break
            probadas += foto.id
            val fallo = subirUna(api, foto) ?: continue
            ultimoFallo = fallo
            // Sin PC o sin Drive no tiene sentido seguir con las demás ahora.
            if (fallo.startsWith(SIN_PC)) break
        }
        ultimoFallo?.let { ResultadoSubida.Reintentar(it) } ?: ResultadoSubida.Terminado
    }

    /** Devuelve null si la foto quedó subida (o no se puede subir nunca); si no, el motivo para reintentar. */
    private suspend fun subirUna(api: PcApi, foto: FotoPendiente): String? {
        val cita = citas.obtener(foto.idVisita) ?: return null
        val uri = Uri.parse(foto.uriMediastore)
        fotos.marcarSubiendo(foto.id)
        val motivo = try {
            val (sha, tamano) = huella(uri)
            val r = api.foto(
                idEncargo = foto.idEncargo,
                fechaVisita = DIA.format(Instant.ofEpochMilli(cita.fechaHora).atZone(ZoneId.systemDefault())),
                nombre = foto.nombreArchivo,
                sha256 = sha,
                foto = CuerpoFoto(context, uri, tamano),
            )
            if (r.estado == "ok") {
                fotos.marcarSubida(foto.id, r.idDrive, System.currentTimeMillis())
                return null
            }
            r.detalle ?: "El PC no pudo subir la foto"
        } catch (e: CancellationException) {
            fotos.marcarFallo(foto.id, EstadoSubida.PENDIENTE, "Subida interrumpida")
            throw e
        } catch (e: FileNotFoundException) {
            // Alguien la borró de la galería: no hay nada que subir, pero se deja a la vista.
            fotos.marcarFallo(foto.id, EstadoSubida.ERROR, "La foto ya no está en la galería del móvil")
            return null
        } catch (e: SinConfiguracion) {
            throw e
        } catch (e: HttpException) {
            when (e.code()) {
                503 -> "$SIN_PC Google Drive aún no está configurado en el PC"
                400 -> "La foto llegó incompleta al PC"
                else -> "$SIN_PC Error del PC (${e.code()})"
            }
        } catch (e: Exception) {
            "$SIN_PC Sin conexión con el PC (${e.message ?: e.javaClass.simpleName})"
        }
        fotos.marcarFallo(foto.id, EstadoSubida.PENDIENTE, motivo.removePrefix(SIN_PC).trim())
        return motivo
    }

    /** sha256 y tamaño de la foto, leyéndola en trozos (sin cargarla entera en memoria). */
    private fun huella(uri: Uri): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val entrada = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        entrada.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        if (total == 0L) throw FileNotFoundException("Foto vacía: $uri")
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }

    /** Cuerpo de la petición que lee la foto de la galería mientras se envía. */
    private class CuerpoFoto(private val context: Context, private val uri: Uri, private val tamano: Long) : RequestBody() {
        override fun contentType(): MediaType = "image/jpeg".toMediaType()
        override fun contentLength(): Long = tamano
        override fun writeTo(sink: BufferedSink) {
            val entrada = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            entrada.source().use { sink.writeAll(it) }
        }
    }

    private companion object {
        /** Marca interna de los fallos que no dependen de la foto (PC apagado, Drive sin configurar...). */
        const val SIN_PC = "[pc]"
        val DIA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
