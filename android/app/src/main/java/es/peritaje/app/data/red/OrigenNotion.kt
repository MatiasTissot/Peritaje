package es.peritaje.app.data.red

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class NotionError(val codigo: Int, mensaje: String) : Exception(mensaje)

/**
 * Acceso directo a la API de Notion desde el móvil (modo provisional mientras no haya PC).
 * Es la misma lógica que server/app/servicio.py, para que el resultado en Notion sea idéntico.
 *
 * Estructura usada (workspace "Gestión de Encargos - Peritaciones"):
 * - Encargos: ID_Encargo (título), Exp_Gabinete, Exp_Compania, Direccion Calle, "Piso, Puerta",
 *   Descripcion, Tipo_Siniestro, Estado, Ubicacion_Maps.
 * - Visitas: Fecha_Visita, Encargo (relación), Notas_Campo.
 * - Contactos: Contacto (título), Nombre, Rol, Telefono, Notas_Contacto, Encargo (relación).
 * El id_encargo que usa la app es el Exp_Gabinete (p. ej. MA26043441).
 */
class OrigenNotion(private val token: String) : OrigenDatos {

    companion object {
        const val DB_ENCARGOS = "7a39eda7868548fcbee05234305a9bd1"
        const val DB_VISITAS = "8c1aacdbae3e48bcbe88f41545450e66"
        const val DB_CONTACTOS = "e7352915ceb2400b8538aa822de2f6b0"
        const val TITULO_AVANCE = "AVANCE APP"
        const val DIAS_ATRAS = 1L
        const val DIAS_ADELANTE = 14L
        val ESTADOS_EXCLUIDOS = setOf("ANULADO", "PNDT ANULADO")
        private val CONTENEDORES = setOf("toggle", "callout", "quote", "tab", "column", "column_list", "synced_block")
        private val NO_SEGURO = Regex("[^A-Za-z0-9_-]")
        private val HEX32 = Regex("^[0-9a-fA-F]{32}$")
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val LEGIBLE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        private val TIPO_JSON = "application/json".toMediaType()

        private val http by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    private val zona = ZoneId.systemDefault()
    /** id_encargo -> id de página de Notion, para no tener que buscar dos veces. */
    private val ids = mutableMapOf<String, String>()

    // --- HTTP ------------------------------------------------------------------

    private suspend fun pedir(metodo: String, ruta: String, cuerpo: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            var intento = 0
            var resultado: JsonObject? = null
            while (resultado == null) {
                val peticion = Request.Builder()
                    .url("https://api.notion.com/v1$ruta")
                    .header("Authorization", "Bearer $token")
                    .header("Notion-Version", "2022-06-28")
                    .method(metodo, cuerpo?.toString()?.toRequestBody(TIPO_JSON))
                    .build()
                val (codigo, texto, esperaSegundos) = http.newCall(peticion).execute().use { r ->
                    Triple(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toLongOrNull())
                }
                // Notion limita a ~3 peticiones/s: ante 429 o 5xx se espera y se reintenta.
                if ((codigo == 429 || codigo >= 500) && intento < 3) {
                    intento++
                    delay(minOf(esperaSegundos ?: intento.toLong(), 10L) * 1000)
                    continue
                }
                if (codigo !in 200..299) {
                    val msg = runCatching {
                        JsonApp.parseToJsonElement(texto).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: texto.take(200)
                    throw NotionError(codigo, "Notion $codigo: $msg")
                }
                resultado = JsonApp.parseToJsonElement(texto).jsonObject
            }
            checkNotNull(resultado)
        }

    private suspend fun consultar(db: String, filtro: JsonObject): List<JsonObject> {
        val resultados = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val cuerpo = buildJsonObject {
                put("page_size", 100)
                put("filter", filtro)
                cursor?.let { put("start_cursor", it) }
            }
            val datos = pedir("POST", "/databases/$db/query", cuerpo)
            datos["results"]?.jsonArray?.forEach { resultados += it.jsonObject }
            cursor = if (datos.bool("has_more")) datos.str("next_cursor") else null
        } while (cursor != null)
        return resultados
    }

    private suspend fun pagina(id: String) = pedir("GET", "/pages/$id")

    private suspend fun hijos(id: String): List<JsonObject> {
        val resultados = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val ruta = "/blocks/$id/children?page_size=100" + (cursor?.let { "&start_cursor=$it" } ?: "")
            val datos = pedir("GET", ruta)
            datos["results"]?.jsonArray?.forEach { resultados += it.jsonObject }
            cursor = if (datos.bool("has_more")) datos.str("next_cursor") else null
        } while (cursor != null)
        return resultados
    }

    private suspend fun anadirHijos(id: String, bloques: List<JsonObject>, despuesDe: String? = null) {
        var after = despuesDe
        bloques.chunked(100).forEach { trozo ->
            val r = pedir("PATCH", "/blocks/$id/children", buildJsonObject {
                put("children", JsonArray(trozo))
                after?.let { put("after", it) }
            })
            if (after != null) after = r["results"]?.jsonArray?.lastOrNull()?.jsonObject?.str("id") ?: after
        }
    }

    // --- lectura de propiedades --------------------------------------------------

    private fun JsonObject.str(clave: String): String? = (this[clave] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(clave: String): Boolean = (this[clave] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.obj(clave: String): JsonObject? = this[clave] as? JsonObject

    private fun textoRico(e: JsonElement?): String =
        (e as? JsonArray)?.joinToString("") { (it as? JsonObject)?.str("plain_text").orEmpty() }?.trim().orEmpty()

    /** Valor de una propiedad como texto (título, texto, select, estado, fecha, url...). */
    private fun JsonObject.prop(nombre: String): String? {
        val p = obj("properties")?.obj(nombre) ?: return null
        val tipo = p.str("type") ?: return null
        val v = p[tipo]
        return when (tipo) {
            "title", "rich_text" -> textoRico(v)
            "select", "status" -> (v as? JsonObject)?.str("name")
            "date" -> (v as? JsonObject)?.str("start")
            "url", "phone_number", "email" -> (v as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.relacion(nombre: String): List<String> {
        val p = obj("properties")?.obj(nombre) ?: return emptyList()
        return (p["relation"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("id") } ?: emptyList()
    }

    // --- fechas ------------------------------------------------------------------

    private fun aLocal(iso: String): LocalDateTime = when {
        iso.length == 10 -> LocalDate.parse(iso).atStartOfDay()
        else -> runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zona).toLocalDateTime() }
            .getOrElse { LocalDateTime.parse(iso.take(19)) }
    }

    /** La próxima visita desde hoy; si no hay, la última pasada. */
    private fun <T> elegir(visitas: List<Pair<LocalDateTime, T>>): Pair<LocalDateTime, T>? {
        val ordenadas = visitas.sortedBy { it.first }
        val inicioHoy = LocalDate.now(zona).atStartOfDay()
        return ordenadas.firstOrNull { it.first >= inicioHoy } ?: ordenadas.lastOrNull()
    }

    // --- identificadores -----------------------------------------------------------

    private fun idLegible(encargo: JsonObject): String {
        val exp = encargo.prop("Exp_Gabinete")?.replace(NO_SEGURO, "").orEmpty()
        return exp.ifEmpty { encargo.str("id")!!.replace("-", "") }
    }

    private suspend fun resolver(idEncargo: String): JsonObject {
        ids[idEncargo]?.let { return pagina(it) }
        if (HEX32.matches(idEncargo)) return pagina(idEncargo)
        val filtros = listOf(
            filtroTexto("Exp_Gabinete", "rich_text", "equals", idEncargo),
            filtroTexto("Exp_Gabinete", "rich_text", "contains", idEncargo),
            filtroTexto("ID_Encargo", "title", "contains", idEncargo),
        )
        for (f in filtros) {
            consultar(DB_ENCARGOS, f).firstOrNull()?.let {
                ids[idEncargo] = it.str("id")!!
                return it
            }
        }
        throw EncargoNoEncontrado("No se encontró el encargo $idEncargo en Notion")
    }

    private fun filtroTexto(prop: String, tipo: String, op: String, valor: String) = buildJsonObject {
        put("property", prop)
        putJsonObject(tipo) { put(op, valor) }
    }

    private fun filtroRelacion(pageId: String) = buildJsonObject {
        put("property", "Encargo")
        putJsonObject("relation") { put("contains", pageId) }
    }

    // --- OrigenDatos -----------------------------------------------------------------

    override suspend fun indice(): List<EntradaIndice> {
        val hoy = LocalDate.now(zona)
        val desde = hoy.minusDays(DIAS_ATRAS).atStartOfDay(zona).toOffsetDateTime().toString()
        val hasta = hoy.plusDays(DIAS_ADELANTE).atTime(LocalTime.of(23, 59)).atZone(zona).toOffsetDateTime().toString()
        val visitas = consultar(DB_VISITAS, buildJsonObject {
            putJsonArray("and") {
                add(buildJsonObject { put("property", "Fecha_Visita"); putJsonObject("date") { put("on_or_after", desde) } })
                add(buildJsonObject { put("property", "Fecha_Visita"); putJsonObject("date") { put("on_or_before", hasta) } })
            }
        })
        val porEncargo = mutableMapOf<String, MutableList<Pair<LocalDateTime, JsonObject>>>()
        for (v in visitas) {
            val fecha = v.prop("Fecha_Visita") ?: continue
            val encargo = v.relacion("Encargo").firstOrNull() ?: continue
            porEncargo.getOrPut(encargo) { mutableListOf() } += aLocal(fecha) to v
        }
        val citas = mutableListOf<EntradaIndice>()
        for ((pageId, lista) in porEncargo) {
            val encargo = pagina(pageId)
            if (encargo.bool("archived") || encargo.bool("in_trash")) continue
            if (encargo.prop("Estado") in ESTADOS_EXCLUIDOS) continue
            val fecha = elegir(lista)?.first ?: continue
            val id = idLegible(encargo)
            ids[id] = encargo.str("id")!!
            citas += EntradaIndice(id, ISO.format(fecha))
        }
        return citas.sortedBy { it.fechaHora }
    }

    override suspend fun detalle(idEncargo: String): DetalleCita {
        val encargo = resolver(idEncargo)
        val pageId = encargo.str("id")!!
        val visitas = consultar(DB_VISITAS, filtroRelacion(pageId))
            .mapNotNull { v -> v.prop("Fecha_Visita")?.let { aLocal(it) to v } }
        val elegida = elegir(visitas)
        val contactos = consultar(DB_CONTACTOS, filtroRelacion(pageId)).map { c ->
            ContactoDto(
                rol = c.prop("Rol") ?: "Otro",
                nombre = c.prop("Nombre") ?: c.prop("Contacto").orEmpty(),
                telefono = c.prop("Telefono"),
                notas = c.prop("Notas_Contacto"),
            )
        }
        val asegurado = contactos.firstOrNull { it.rol == "Asegurado" } ?: contactos.firstOrNull()
        val direccion = listOfNotNull(encargo.prop("Direccion Calle"), encargo.prop("Piso, Puerta")).joinToString(", ")
        val id = idLegible(encargo)
        ids[id] = pageId
        return DetalleCita(
            idEncargo = id,
            fechaVisita = elegida?.first?.let { ISO.format(it) },
            direccion = direccion,
            asegurado = asegurado?.nombre.orEmpty(),
            telefonoContacto = asegurado?.telefono,
            tipoSiniestro = encargo.prop("Tipo_Siniestro"),
            notasGabinete = encargo.prop("Descripcion"),
            titulo = encargo.prop("ID_Encargo") ?: id,
            expCompania = encargo.prop("Exp_Compania"),
            urlMaps = encargo.prop("Ubicacion_Maps"),
            notasVisita = elegida?.second?.prop("Notas_Campo"),
            contactos = contactos,
        )
    }

    /** Busca el bloque "AVANCE APP": devuelve (padre, después de) igual que el servidor. */
    private suspend fun buscarDestino(pageId: String): Pair<String, String?>? {
        val pendientes = ArrayDeque(listOf(pageId to 0))
        while (pendientes.isNotEmpty()) {
            val (padre, nivel) = pendientes.removeFirst()
            for (b in hijos(padre)) {
                val tipo = b.str("type").orEmpty()
                val datos = b.obj(tipo)
                if (textoRico(datos?.get("rich_text")).equals(TITULO_AVANCE, ignoreCase = true)) {
                    return if (tipo in CONTENEDORES || datos?.bool("is_toggleable") == true) b.str("id")!! to null
                    else padre to b.str("id")
                }
                if (b.bool("has_children") && nivel < 3 && tipo in CONTENEDORES) pendientes += b.str("id")!! to nivel + 1
            }
        }
        return null
    }

    private fun parrafo(texto: String, negrita: Boolean = false) = buildJsonObject {
        put("object", "block")
        put("type", "paragraph")
        putJsonObject("paragraph") {
            put("rich_text", buildJsonArray {
                texto.chunked(1900).ifEmpty { listOf("") }.forEach { t ->
                    add(buildJsonObject {
                        put("type", "text")
                        putJsonObject("text") { put("content", t) }
                        putJsonObject("annotations") { put("bold", negrita) }
                    })
                }
            })
        }
    }

    override suspend fun enviarAvance(avance: Avance): RespuestaEstado {
        val encargo = try {
            resolver(avance.idEncargo)
        } catch (e: EncargoNoEncontrado) {
            return RespuestaEstado("error", e.message)
        }
        val pageId = encargo.str("id")!!
        // Marca corta del envío: si un reintento llega después de que Notion ya lo guardara, no se duplica.
        val ref = avance.idEnvio?.take(8)
        val visita = runCatching { LEGIBLE.format(aLocal(avance.fechaVisitaRealizada)) }.getOrDefault(avance.fechaVisitaRealizada)
        val cabecera = "Avance desde la app · visita $visita" + (ref?.let { " · ref $it" } ?: "")
        val bloques = buildList {
            add(parrafo(cabecera, negrita = true))
            avance.textoAvance.lines().forEach { add(parrafo(it)) }
            add(buildJsonObject { put("object", "block"); put("type", "divider"); putJsonObject("divider") {} })
        }

        val destino = buscarDestino(pageId)
        if (destino == null) {
            // No existe aún: se crea un desplegable "AVANCE APP" al final de la página.
            anadirHijos(pageId, listOf(buildJsonObject {
                put("object", "block")
                put("type", "heading_2")
                putJsonObject("heading_2") {
                    put("is_toggleable", true)
                    put("rich_text", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); putJsonObject("text") { put("content", TITULO_AVANCE) } })
                    })
                }
                put("children", JsonArray(bloques.take(99)))
            }))
            if (bloques.size > 99) buscarDestino(pageId)?.let { anadirHijos(it.first, bloques.drop(99)) }
        } else {
            val (padre, despuesDe) = destino
            if (ref != null && hijos(padre).any { b ->
                    textoRico(b.obj(b.str("type").orEmpty())?.get("rich_text")).contains("ref $ref")
                }) {
                return RespuestaEstado("ok")
            }
            anadirHijos(padre, bloques, despuesDe)
        }
        return RespuestaEstado("ok")
    }

    override suspend fun probar(): String {
        val yo = pedir("GET", "/users/me")
        val nombre = yo.str("name") ?: "integración"
        return try {
            pedir("GET", "/databases/$DB_ENCARGOS")
            "Conexión correcta con Notion ($nombre)"
        } catch (e: NotionError) {
            if (e.codigo == 404) "El token funciona, pero la integración \"$nombre\" no tiene acceso a " +
                "\"Gestión de Encargos - Peritaciones\". Añádela en Notion desde ··· → Conexiones."
            else throw e
        }
    }
}
