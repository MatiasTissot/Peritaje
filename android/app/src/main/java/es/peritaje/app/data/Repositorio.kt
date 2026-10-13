package es.peritaje.app.data

import es.peritaje.app.data.db.BaseDatos
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.Configuracion
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.data.fotos.SubidorFotos
import es.peritaje.app.data.red.Avance
import es.peritaje.app.data.red.ContactoDto
import es.peritaje.app.data.red.DetalleCita
import es.peritaje.app.data.red.FabricaApi
import es.peritaje.app.data.red.JsonApp
import es.peritaje.app.data.red.EncargoNoEncontrado
import es.peritaje.app.data.red.Modo
import es.peritaje.app.data.red.NotionError
import es.peritaje.app.data.red.OrigenDatos
import es.peritaje.app.data.red.OrigenNotion
import es.peritaje.app.data.red.OrigenPc
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

class SinConfiguracion : Exception("Falta configurar la conexión en Ajustes")

sealed interface ResultadoEnvio {
    data object Enviado : ResultadoEnvio
    data class Reintentar(val motivo: String) : ResultadoEnvio
}

class Repositorio(
    private val db: BaseDatos,
    private val token: AlmacenToken,
    private val subidorFotos: SubidorFotos,
) {
    private val citas = db.citas()
    private val fotos = db.fotos()
    private val config = db.config()

    val resumenes = citas.observarResumenes()
    val configuracion = config.observar()
    val pendientesDeEnvio = citas.observarPendientesDeEnvio()

    fun observarCita(id: String) = citas.observar(id)
    fun observarFotos(id: String) = fotos.observarDe(id)

    // --- configuración ---------------------------------------------------

    suspend fun guardarConexion(modo: Modo, url: String, tokenPc: String, tokenNotion: String) {
        val actual = config.obtener() ?: Configuracion()
        config.guardar(actual.copy(urlPc = url.trim()))
        token.modo = modo
        token.token = tokenPc
        token.tokenNotion = tokenNotion
    }

    fun modo(): Modo = token.modo
    fun tokenPc(): String = token.token
    fun tokenNotion(): String = token.tokenNotion

    /** true si hay credenciales para el modo elegido. */
    fun configurado(urlPc: String?): Boolean = when (token.modo) {
        Modo.NOTION -> token.tokenNotion.isNotBlank()
        Modo.PC -> !urlPc.isNullOrBlank() && token.token.isNotBlank()
    }

    private fun crearOrigen(modo: Modo, url: String, tokenPc: String, tokenNotion: String): OrigenDatos =
        when (modo) {
            Modo.NOTION -> {
                if (tokenNotion.isBlank()) throw SinConfiguracion()
                OrigenNotion(tokenNotion)
            }
            Modo.PC -> {
                if (url.isBlank() || tokenPc.isBlank()) throw SinConfiguracion()
                OrigenPc(FabricaApi.crear(url, tokenPc))
            }
        }

    private suspend fun origen(): OrigenDatos =
        crearOrigen(token.modo, config.obtener()?.urlPc.orEmpty(), token.token, token.tokenNotion)

    suspend fun probarConexion(modo: Modo, url: String, tokenPc: String, tokenNotion: String): String =
        crearOrigen(modo, url, tokenPc, tokenNotion).probar()

    // --- sincronización (GET /citas-index + /citas/{id}) -----------------

    suspend fun sincronizar() {
        try {
            val origen = origen()
            var fallidas = 0
            for (entrada in origen.indice()) {
                val fecha = aMillis(entrada.fechaHora)
                val local = citas.obtener(entrada.idEncargo)
                try {
                    when {
                        local == null -> citas.insertar(origen.detalle(entrada.idEncargo).aCita(fecha))
                        local.fechaHora == fecha -> Unit
                        // Caso normal: nada capturado, se repide en silencio.
                        local.estado == EstadoCita.PENDIENTE && !tieneDatos(local) ->
                            citas.insertar(origen.detalle(entrada.idEncargo).aCita(fecha))
                        // Hay información guardada: se pregunta al perito (Eliminar / Conservar).
                        local.fechaHoraNueva != fecha -> citas.actualizar(local.copy(fechaHoraNueva = fecha))
                        else -> Unit
                    }
                } catch (e: Exception) {
                    if (e !is EncargoNoEncontrado && !(e is NotionError && e.codigo in 400..499 && e.codigo != 401)) throw e
                    // Un encargo concreto falla (p. ej. borrado en Notion): se sigue con el resto.
                    fallidas++
                }
            }
            val actual = config.obtener() ?: Configuracion()
            config.guardar(
                actual.copy(
                    ultimaSincronizacion = System.currentTimeMillis(),
                    ultimoErrorSync = if (fallidas > 0) "No se pudieron traer $fallidas citas" else null,
                )
            )
        } catch (e: Exception) {
            val actual = config.obtener() ?: Configuracion()
            config.guardar(actual.copy(ultimoErrorSync = e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    suspend fun tieneDatos(cita: Cita): Boolean = !cita.checklistVacio() || fotos.contar(cita.idEncargo) > 0

    /** Respuesta al pop-up de cambio de fecha. */
    suspend fun resolverCambioFecha(id: String, conservar: Boolean) {
        val cita = citas.obtener(id) ?: return
        val nueva = cita.fechaHoraNueva ?: return
        if (conservar) {
            citas.actualizar(cita.copy(fechaHora = nueva, fechaHoraNueva = null))
        } else {
            // Se borra la cita y sus registros de foto (los archivos siguen en la galería).
            citas.borrar(id)
            runCatching { citas.insertar(origen().detalle(id).aCita(nueva)) }
        }
    }

    // --- visita -------------------------------------------------------------

    suspend fun guardarChecklist(cita: Cita) {
        val actual = citas.obtener(cita.idEncargo) ?: return
        if (actual.estado != EstadoCita.PENDIENTE) return
        citas.actualizar(
            actual.copy(
                fechaOcurrencia = cita.fechaOcurrencia,
                causa = cita.causa,
                hayCausante = cita.hayCausante,
                quienAtiende = cita.quienAtiende,
                testigos = cita.testigos,
                observacionesLibres = cita.observacionesLibres,
            )
        )
    }

    /** "Finalizar visita": bloquea el checklist y deja el avance (y en el futuro las fotos) en cola. */
    suspend fun finalizarVisita(id: String): Boolean {
        val cita = citas.obtener(id) ?: return false
        if (cita.estado != EstadoCita.PENDIENTE) return false
        citas.actualizar(
            cita.copy(
                estado = EstadoCita.VISITADA,
                fechaVisitaRealizada = System.currentTimeMillis(),
                idEnvio = UUID.randomUUID().toString(),
                ultimoError = null,
            )
        )
        subidorFotos.encolar(id)
        return true
    }

    suspend fun idsVisitadas(): List<String> = citas.visitadas().map { it.idEncargo }

    // --- envío del avance (POST /avance) ------------------------------------

    suspend fun enviarAvance(id: String): ResultadoEnvio {
        val cita = citas.obtener(id) ?: return ResultadoEnvio.Enviado
        if (cita.estado != EstadoCita.VISITADA) return ResultadoEnvio.Enviado
        val motivo = try {
            val respuesta = origen().enviarAvance(
                Avance(
                    idEncargo = cita.idEncargo,
                    fechaVisitaRealizada = aIso(cita.fechaVisitaRealizada ?: System.currentTimeMillis()),
                    textoAvance = textoAvance(cita, fotos.contar(id)),
                    idEnvio = cita.idEnvio,
                )
            )
            if (respuesta.estado == "ok") {
                citas.actualizar(
                    cita.copy(estado = EstadoCita.ENVIADA, fechaEnvio = System.currentTimeMillis(), ultimoError = null)
                )
                return ResultadoEnvio.Enviado
            }
            respuesta.detalle ?: "El PC respondió con error"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        citas.actualizar(cita.copy(ultimoError = motivo))
        return ResultadoEnvio.Reintentar(motivo)
    }

    suspend fun purgar(): Int =
        citas.purgarEnviadasAntesDe(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(DIAS_RETENCION))

    companion object {
        const val DIAS_RETENCION = 7L
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        fun aMillis(iso: String): Long =
            LocalDateTime.parse(iso.take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun aIso(millis: Long): String =
            ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        fun fechaHoraLegible(millis: Long): String =
            FECHA_HORA.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        fun contactos(cita: Cita): List<ContactoDto> =
            cita.contactosJson?.let {
                runCatching { JsonApp.decodeFromString(ListSerializer(ContactoDto.serializer()), it) }.getOrNull()
            } ?: emptyList()

        /** El checklist se concatena en un único bloque de texto solo al enviar. */
        fun textoAvance(cita: Cita, numFotos: Int): String {
            fun v(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
            val causante = when (cita.hayCausante) {
                true -> "Sí"
                false -> "No"
                null -> "Sin determinar"
            }
            return buildString {
                appendLine("Día de visita: ${fechaHoraLegible(cita.fechaVisitaRealizada ?: cita.fechaHora)}")
                appendLine("Quién atiende: ${v(cita.quienAtiende)}")
                appendLine("Fecha de ocurrencia: ${v(cita.fechaOcurrencia)}")
                appendLine("Causa: ${v(cita.causa)}")
                appendLine("Hay causante: $causante")
                appendLine("Testigos: ${v(cita.testigos)}")
                appendLine("Fotos tomadas: $numFotos")
                appendLine("Observaciones:")
                append(v(cita.observacionesLibres))
            }
        }
    }
}

private fun DetalleCita.aCita(fechaIndice: Long) = Cita(
    idEncargo = idEncargo,
    fechaHora = fechaIndice,
    direccion = direccion,
    asegurado = asegurado,
    telefonoContacto = telefonoContacto,
    tipoSiniestro = tipoSiniestro,
    notasGabinete = notasGabinete,
    titulo = titulo,
    expCompania = expCompania,
    urlMaps = urlMaps,
    notasVisita = notasVisita,
    contactosJson = JsonApp.encodeToString(ListSerializer(ContactoDto.serializer()), contactos),
)
