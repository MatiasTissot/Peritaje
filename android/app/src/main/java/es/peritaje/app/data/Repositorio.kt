package es.peritaje.app.data

import es.peritaje.app.data.db.BaseDatos
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.Configuracion
import es.peritaje.app.data.db.ContactoVisita
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.data.fotos.SubidorFotos
import es.peritaje.app.data.red.Avance
import es.peritaje.app.data.red.ContactoDto
import es.peritaje.app.data.red.ContactoNuevo
import es.peritaje.app.data.red.DetalleCita
import es.peritaje.app.data.red.FabricaApi
import es.peritaje.app.data.red.JsonApp
import es.peritaje.app.data.red.PcApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import java.util.concurrent.TimeUnit

class SinConfiguracion : Exception("Falta configurar la dirección del PC o el token")

sealed interface ResultadoEnvio {
    data object Enviado : ResultadoEnvio
    data class Reintentar(val motivo: String) : ResultadoEnvio
}

class Repositorio(
    private val db: BaseDatos,
    private val token: AlmacenToken,
    private val subidorFotos: SubidorFotos,
    /** true si la visita tiene una foto a medio hacer: entonces nunca se borra. */
    private val capturaEnCurso: (String) -> Boolean = { false },
) {
    private val citas = db.citas()
    private val fotos = db.fotos()
    private val config = db.config()
    private val contactosDao = db.contactos()

    val resumenes = citas.observarResumenes()
    val configuracion = config.observar()
    val pendientesDeEnvio = citas.observarPendientesDeEnvio()

    fun observarCita(id: String) = citas.observar(id)
    fun observarFotos(id: String) = fotos.observarDe(id)
    fun observarContactos(id: String) = contactosDao.observarDe(id)
    suspend fun contactosDe(id: String) = contactosDao.deVisita(id)

    // --- configuración ---------------------------------------------------

    suspend fun guardarConexion(url: String, nuevoToken: String) {
        val actual = config.obtener() ?: Configuracion()
        config.guardar(actual.copy(urlPc = normalizarUrl(url)))
        token.token = nuevoToken.trim()
    }

    fun tokenActual(): String = token.token

    private suspend fun api(): PcApi {
        val url = config.obtener()?.urlPc.orEmpty()
        val t = token.token
        if (url.isBlank() || t.isBlank()) throw SinConfiguracion()
        return FabricaApi.crear(url, t)
    }

    suspend fun probarConexion(url: String, t: String): String =
        FabricaApi.crear(normalizarUrl(url), t.trim()).salud().estado

    // --- sincronización (GET /citas-index + /citas/{id}) -----------------

    /**
     * En el móvil solo hay: las visitas de hoy, las de mañana si se sincroniza a partir de las 17:00,
     * y las pendientes de cierre (no enviadas). En cada sincronización se vuelve a pedir a Notion
     * el detalle de todas ellas:
     * - Anulada o borrada en Notion: desaparece; si tiene datos sin enviar se conserva marcada.
     * - Movida fuera de la ventana: desaparece; si tiene datos se conserva con un aviso.
     * - Si no, se refrescan sus datos (nunca el checklist ni las fotos).
     */
    suspend fun sincronizar() {
        try {
            val api = api()
            val zona = ZoneId.systemDefault()
            val ahora = ZonedDateTime.now(zona)
            val hoy = ahora.toLocalDate()
            val inicioHoy = hoy.atStartOfDay(zona).toInstant().toEpochMilli()
            val diasVentana = if (ahora.hour >= HORA_CARGA_MANANA) 2L else 1L
            val finVentana = hoy.plusDays(diasVentana).atStartOfDay(zona).toInstant().toEpochMilli()
            fun enVentana(f: Long) = f in inicioHoy until finVentana

            // Una entrada por visita; si el PC no manda id_visita, la visita se identifica por el encargo.
            val delIndice = api.indice().citas
                .map { Triple(it.idVisita ?: it.idEncargo, it.idEncargo, aMillis(it.fechaHora)) }
                .filter { enVentana(it.third) }
                .associateBy { it.first }

            // Datos antiguos (una visita por encargo): se pasan al id de su visita sin perder nada.
            if (delIndice.values.any { it.first != it.second }) {
                for (local in citas.noEnviadas()) {
                    if (local.idVisita != local.idEncargo) continue
                    val nueva = delIndice.values
                        .filter { it.second == local.idEncargo && it.first != it.second }
                        .minByOrNull { abs(it.third - local.fechaHora) } ?: continue
                    citas.cambiarId(local.idVisita, nueva.first)
                }
            }

            val ids = LinkedHashMap<String, String>()   // idVisita -> idEncargo
            delIndice.values.forEach { ids[it.first] = it.second }
            citas.noEnviadas().forEach { ids[it.idVisita] = it.idEncargo }

            var fallidas = 0
            for ((idVisita, idEncargo) in ids) {
                try {
                    val detalle = try {
                        if (idVisita == idEncargo) api.detalle(idEncargo) else api.visita(idVisita)
                    } catch (e: HttpException) {
                        if (e.code() == 404) null else throw e
                    }
                    procesar(idVisita, idEncargo, detalle, delIndice[idVisita]?.third, ::enVentana, finVentana)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Una visita concreta falla: se sigue con el resto y se reintenta en la próxima.
                    fallidas++
                }
            }
            val actual = config.obtener() ?: Configuracion()
            config.guardar(
                actual.copy(
                    ultimaSincronizacion = System.currentTimeMillis(),
                    ultimoErrorSync = if (fallidas > 0) "No se pudieron actualizar $fallidas visitas" else null,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val actual = config.obtener() ?: Configuracion()
            config.guardar(actual.copy(ultimoErrorSync = e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    private suspend fun procesar(
        id: String, idEncargo: String, detalle: DetalleCita?, fechaIndice: Long?,
        enVentana: (Long) -> Boolean, finVentana: Long,
    ) {
        val local = citas.obtener(id)
        val fecha = fechaIndice ?: detalle?.fechaVisita?.let(::aMillis)
        val anulada = detalle == null || fecha == null ||
            (detalle.estadoNotion?.trim()?.uppercase() ?: "") in ESTADOS_ANULADOS

        if (anulada) {
            if (local == null || local.estado == EstadoCita.ENVIADA) return
            if (borrarSiSinDatos(local)) return
            if (!local.anulada) {
                citas.marcarAnulada(
                    id,
                    if (detalle == null) "Esta visita se ha borrado en Notion. Tiene datos sin enviar: envíala o descártala."
                    else "Esta visita está anulada en Notion. Tiene datos sin enviar: envíala o descártala.",
                )
            }
            return
        }
        detalle!!
        fecha!!

        when {
            local == null -> if (enVentana(fecha)) citas.insertar(detalle.aCita(id, idEncargo, fecha))

            local.estado == EstadoCita.ENVIADA -> {
                // Solo datos antiguos (una visita por encargo): nueva visita otro día de un encargo ya enviado.
                if (id == idEncargo && fechaIndice != null && !mismoDia(fecha, local.fechaHora) &&
                    fecha > local.fechaHora && citas.reabrir(id) > 0
                ) {
                    contactosDao.borrarDeVisita(id)
                    actualizarDatos(local, detalle, fecha, null)
                }
            }

            fecha >= finVentana -> {
                // Movida a un día posterior: se carga cuando llegue, salvo que ya tenga datos.
                if (borrarSiSinDatos(local)) return
                actualizarDatos(
                    local, detalle, fecha,
                    "Notion ha movido esta visita al ${fechaHoraLegible(fecha)}. Se conserva porque tiene datos.",
                )
            }

            else -> {
                val aviso = when {
                    fecha != local.fechaHora && (local.estado != EstadoCita.PENDIENTE || tieneDatos(local)) ->
                        "Notion ha cambiado la fecha: antes ${fechaHoraLegible(local.fechaHora)}, ahora ${fechaHoraLegible(fecha)}."
                    local.anulada -> null
                    else -> local.avisoNotion
                }
                actualizarDatos(local, detalle, fecha, aviso)
            }
        }
    }

    private suspend fun actualizarDatos(local: Cita, d: DetalleCita, fecha: Long, aviso: String?) {
        citas.actualizarDatosNotion(
            id = local.idVisita, fechaHora = fecha, direccion = d.direccion, asegurado = d.asegurado,
            telefono = d.telefonoContacto, tipo = d.tipoSiniestro, notasGabinete = d.notasGabinete, titulo = d.titulo,
            expCompania = d.expCompania, urlMaps = d.urlMaps, notasVisita = d.notasVisita,
            contactosJson = JsonApp.encodeToString(ListSerializer(ContactoDto.serializer()), d.contactos),
            aviso = aviso,
        )
    }

    /** Borra la visita solo si no hay nada capturado (ni checklist, ni fotos, ni una foto a medias). */
    private suspend fun borrarSiSinDatos(cita: Cita): Boolean {
        if (cita.estado != EstadoCita.PENDIENTE || capturaEnCurso(cita.idVisita)) return false
        return citas.borrarSiVacia(cita.idVisita) > 0
    }

    suspend fun tieneDatos(cita: Cita): Boolean =
        !cita.checklistVacio() || fotos.contar(cita.idVisita) > 0 || capturaEnCurso(cita.idVisita) ||
            contactosDao.deVisita(cita.idVisita).isNotEmpty()

    suspend fun quitarAviso(id: String) = citas.quitarAviso(id)

    /**
     * Descarta una visita anulada en Notion. Se borra del móvil; las fotos siguen en la galería
     * (Pictures/PeritajeApp). Solo se permite si Notion la ha anulado o borrado.
     */
    suspend fun descartarAnulada(id: String): Boolean {
        val cita = citas.obtener(id) ?: return false
        if (!cita.anulada) return false
        citas.borrar(id)
        return true
    }

    // --- visita -------------------------------------------------------------

    /** Autoguardado del borrador: solo toca las columnas del checklist, y solo mientras esté pendiente. */
    suspend fun guardarChecklist(cita: Cita) {
        citas.guardarChecklist(
            id = cita.idVisita, fechaOcurrencia = cita.fechaOcurrencia, causa = cita.causa,
            hayCausante = cita.hayCausante, quienAtiende = cita.quienAtiende, testigos = cita.testigos,
            observaciones = cita.observacionesLibres, causanteNombre = cita.causanteNombre,
            causanteTelefono = cita.causanteTelefono, causanteDni = cita.causanteDni, causanteUbicacion = cita.causanteUbicacion,
            causanteAseguradora = cita.causanteAseguradora,
        )
    }

    /** Crea un contacto vacío con ese rol y devuelve su id. */
    suspend fun nuevoContacto(idVisita: String, rol: String): Long =
        contactosDao.insertar(ContactoVisita(idVisita = idVisita, rol = rol))

    suspend fun guardarContacto(c: ContactoVisita) =
        contactosDao.actualizar(c.id, c.rol, c.nombre, c.telefono, c.email, c.dni, c.direccion, c.notas)

    suspend fun borrarContacto(id: Long) = contactosDao.borrar(id)

    /** "Finalizar visita": bloquea el checklist y deja el avance (y en el futuro las fotos) en cola. */
    suspend fun finalizarVisita(id: String): Boolean {
        val hecho = citas.finalizar(id, System.currentTimeMillis(), UUID.randomUUID().toString()) > 0
        if (hecho) citas.obtener(id)?.let { subidorFotos.encolar(it.idVisita, it.idEncargo) }
        return hecho
    }

    suspend fun idsVisitadas(): List<String> = citas.visitadas().map { it.idVisita }

    // --- envío del avance (POST /avance) ------------------------------------

    suspend fun enviarAvance(id: String): ResultadoEnvio {
        val cita = citas.obtener(id) ?: return ResultadoEnvio.Enviado
        if (cita.estado != EstadoCita.VISITADA) return ResultadoEnvio.Enviado
        val motivo = try {
            val respuesta = api().avance(
                Avance(
                    idEncargo = cita.idEncargo,
                    fechaVisitaRealizada = aIso(cita.fechaVisitaRealizada ?: System.currentTimeMillis()),
                    textoAvance = textoAvance(cita, fotos.contar(id), contactosDao.deVisita(id)),
                    idEnvio = cita.idEnvio,
                    idVisita = cita.idVisita.takeIf { it != cita.idEncargo },
                    contactos = contactosParaNotion(cita, contactosDao.deVisita(id)),
                )
            )
            if (respuesta.estado == "ok") {
                citas.marcarEnviada(id, System.currentTimeMillis())
                return ResultadoEnvio.Enviado
            }
            respuesta.detalle ?: "El PC respondió con error"
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) "El encargo ya no existe en Notion" else "Error del PC (${e.code()})"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        citas.ponerError(id, motivo)
        return ResultadoEnvio.Reintentar(motivo)
    }

    suspend fun purgar(): Int =
        citas.purgarEnviadasAntesDe(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(DIAS_RETENCION))

    companion object {
        const val DIAS_RETENCION = 7L
        /** A partir de esta hora, la sincronización carga también las visitas de mañana. */
        const val HORA_CARGA_MANANA = 17
        private val ESTADOS_ANULADOS = setOf("ANULADO", "PNDT ANULADO")
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        fun aMillis(iso: String): Long =
            LocalDateTime.parse(iso.take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun mismoDia(a: Long, b: Long): Boolean {
            val z = ZoneId.systemDefault()
            return Instant.ofEpochMilli(a).atZone(z).toLocalDate() == Instant.ofEpochMilli(b).atZone(z).toLocalDate()
        }

        /** Acepta "100.1.2.3:8000" y le añade http:// (la conexión va por Tailscale). */
        fun normalizarUrl(url: String): String {
            val u = url.trim().trimEnd('/')
            if (u.isEmpty()) return u
            return if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else "http://$u"
        }

        fun aIso(millis: Long): String =
            ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        fun fechaHoraLegible(millis: Long): String =
            FECHA_HORA.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        fun contactos(cita: Cita): List<ContactoDto> =
            cita.contactosJson?.let {
                runCatching { JsonApp.decodeFromString(ListSerializer(ContactoDto.serializer()), it) }.getOrNull()
            } ?: emptyList()

        private fun limpio(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }

        /** Causante (si lo hay) y contactos añadidos con algún dato, tal como se crean en Notion. */
        fun contactosParaNotion(cita: Cita, contactos: List<ContactoVisita>): List<ContactoNuevo> = buildList {
            val causante = listOf(cita.causanteNombre, cita.causanteTelefono, cita.causanteDni, cita.causanteUbicacion)
            if (cita.hayCausante == true && causante.any { limpio(it) != null }) {
                add(
                    ContactoNuevo(
                        rol = "Causante",
                        // El nombre es obligatorio en Notion: si no se tomó, se deja indicado.
                        nombre = limpio(cita.causanteNombre) ?: "Causante (sin nombre)",
                        telefono = limpio(cita.causanteTelefono),
                        dni = limpio(cita.causanteDni),
                        direccion = limpio(cita.causanteUbicacion),
                        notas = limpio(cita.causanteAseguradora)?.let { "Aseguradora/póliza: $it" },
                    )
                )
            }
            contactos.filterNot { it.vacio() }.forEach {
                add(
                    ContactoNuevo(
                        rol = it.rol, nombre = limpio(it.nombre) ?: "${it.rol} (sin nombre)",
                        telefono = limpio(it.telefono), email = limpio(it.email), dni = limpio(it.dni),
                        direccion = limpio(it.direccion), notas = limpio(it.notas),
                    )
                )
            }
        }

        /** Los datos de la visita se concatenan en un único bloque de texto solo al enviar. */
        fun textoAvance(cita: Cita, numFotos: Int, contactos: List<ContactoVisita> = emptyList()): String {
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
                if (cita.hayCausante == true) {
                    appendLine("Datos del causante:")
                    appendLine("  Nombre: ${v(cita.causanteNombre)}")
                    appendLine("  Teléfono: ${v(cita.causanteTelefono)}")
                    limpio(cita.causanteDni)?.let { appendLine("  DNI: $it") }
                    appendLine("  Ubicación: ${v(cita.causanteUbicacion)}")
                    appendLine("  Aseguradora/póliza: ${v(cita.causanteAseguradora)}")
                }
                limpio(cita.testigos)?.let { appendLine("Testigos: $it") }
                val conDatos = contactos.filterNot { it.vacio() }
                if (conDatos.isNotEmpty()) {
                    appendLine("Otros contactos:")
                    conDatos.forEach { k ->
                        val datos = listOfNotNull(
                            limpio(k.nombre), limpio(k.telefono), limpio(k.email), limpio(k.dni)?.let { "DNI $it" },
                            limpio(k.direccion), limpio(k.notas),
                        )
                        appendLine("  ${k.rol}: " + datos.joinToString(" · "))
                    }
                }
                appendLine("Fotos tomadas: $numFotos")
                appendLine("Observaciones:")
                append(v(cita.observacionesLibres))
            }
        }
    }
}

private fun DetalleCita.aCita(idVisita: String, idEncargo: String, fechaIndice: Long) = Cita(
    idVisita = idVisita,
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
