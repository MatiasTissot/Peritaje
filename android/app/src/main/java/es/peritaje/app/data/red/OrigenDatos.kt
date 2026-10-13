package es.peritaje.app.data.red

/**
 * De dónde salen las citas y a dónde va el avance.
 *
 * Hoy la app habla directamente con Notion ([OrigenNotion]). Cuando se monte el PC,
 * basta con elegir [OrigenPc] en Ajustes: el resto de la app no cambia.
 */
interface OrigenDatos {
    suspend fun indice(): List<EntradaIndice>
    suspend fun detalle(idEncargo: String): DetalleCita
    suspend fun enviarAvance(avance: Avance): RespuestaEstado
    /** Comprueba credenciales y permisos; devuelve un texto para mostrar al perito. */
    suspend fun probar(): String
}

enum class Modo { NOTION, PC }

/** El encargo no existe (o ya no es accesible) en el destino. */
class EncargoNoEncontrado(mensaje: String) : Exception(mensaje)

class OrigenPc(private val api: PcApi) : OrigenDatos {
    override suspend fun indice() = api.indice().citas

    override suspend fun detalle(idEncargo: String): DetalleCita = try {
        api.detalle(idEncargo)
    } catch (e: retrofit2.HttpException) {
        if (e.code() == 404) throw EncargoNoEncontrado("No se encontró el encargo $idEncargo") else throw e
    }

    override suspend fun enviarAvance(avance: Avance) = api.avance(avance)

    override suspend fun probar(): String {
        val estado = api.salud().estado
        return if (estado == "ok") "Conexión correcta con el PC" else "El PC respondió: $estado"
    }
}
