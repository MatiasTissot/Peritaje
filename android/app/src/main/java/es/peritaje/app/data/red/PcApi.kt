package es.peritaje.app.data.red

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** Contrato PC <-> app (ver especificación, sección "Contrato PC ↔ app"). */
interface PcApi {
    @GET("salud")
    suspend fun salud(): RespuestaEstado

    @GET("citas-index")
    suspend fun indice(): IndiceCitas

    /** Detalle de la visita "elegida" de un encargo. Solo para datos antiguos o un PC sin id_visita. */
    @GET("citas/{id}")
    suspend fun detalle(@Path("id") idEncargo: String): DetalleCita

    /** Detalle de una visita concreta (página de Visitas) con los datos de su encargo. 404 si se borró. */
    @GET("visitas/{id}")
    suspend fun visita(@Path("id") idVisita: String): DetalleCita

    @POST("avance")
    suspend fun avance(@Body avance: Avance): RespuestaEstado

    /**
     * Manda una foto al PC, que la sube a Drive en la carpeta del encargo
     * (Fotografias/Visita_<fechaVisita>). "ok" solo cuando Drive la ha confirmado; reenviar la
     * misma foto no la duplica (el PC la reconoce por su [sha256]).
     */
    @PUT("fotos")
    suspend fun foto(
        @Query("id_encargo") idEncargo: String,
        @Query("fecha_visita") fechaVisita: String,
        @Query("nombre") nombre: String,
        @Header("X-Sha256") sha256: String,
        @Body foto: RequestBody,
    ): RespuestaFoto
}

@Serializable
data class IndiceCitas(val citas: List<EntradaIndice>)

@Serializable
data class EntradaIndice(
    @SerialName("id_encargo") val idEncargo: String,
    /** Una entrada por visita. Si el PC aún no lo manda, la app trabaja con una visita por encargo. */
    @SerialName("id_visita") val idVisita: String? = null,
    @SerialName("fecha_hora") val fechaHora: String,
)

@Serializable
data class ContactoDto(
    val rol: String = "",
    val nombre: String = "",
    val telefono: String? = null,
    val notas: String? = null,
)

@Serializable
data class DetalleCita(
    @SerialName("id_encargo") val idEncargo: String,
    @SerialName("id_visita") val idVisita: String? = null,
    @SerialName("fecha_visita") val fechaVisita: String? = null,
    val direccion: String = "",
    val asegurado: String = "",
    @SerialName("telefono_contacto") val telefonoContacto: String? = null,
    @SerialName("tipo_siniestro") val tipoSiniestro: String? = null,
    @SerialName("notas_gabinete") val notasGabinete: String? = null,
    val titulo: String? = null,
    @SerialName("exp_compania") val expCompania: String? = null,
    @SerialName("url_maps") val urlMaps: String? = null,
    @SerialName("notas_visita") val notasVisita: String? = null,
    @SerialName("estado_notion") val estadoNotion: String? = null,
    val contactos: List<ContactoDto> = emptyList(),
)

@Serializable
data class Avance(
    @SerialName("id_encargo") val idEncargo: String,
    @SerialName("fecha_visita_realizada") val fechaVisitaRealizada: String,
    @SerialName("texto_avance") val textoAvance: String,
    @SerialName("id_envio") val idEnvio: String? = null,
    @SerialName("id_visita") val idVisita: String? = null,
    /** Contactos tomados en la visita (incluido el causante); el PC los crea en la base Contactos de Notion. */
    val contactos: List<ContactoNuevo> = emptyList(),
)

/** Contacto para crear en Notion. El PC no duplica (nombre + teléfono), así que reintentar es seguro. */
@Serializable
data class ContactoNuevo(
    val rol: String,
    val nombre: String,
    val telefono: String? = null,
    val email: String? = null,
    val dni: String? = null,
    val direccion: String? = null,
    val notas: String? = null,
)

@Serializable
data class RespuestaEstado(
    val estado: String,
    val detalle: String? = null,
)

@Serializable
data class RespuestaFoto(
    val estado: String,
    val detalle: String? = null,
    @SerialName("id_drive") val idDrive: String? = null,
)

val JsonApp = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Crea el cliente hacia el PC con la URL y el token actuales. */
object FabricaApi {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Con [paraFotos] se da más margen de espera: el PC responde cuando Drive ha recibido la
     * foto entera, y con poca cobertura enviar unos MB puede tardar.
     */
    fun crear(urlPc: String, token: String, paraFotos: Boolean = false): PcApi {
        val base = urlPc.trim().let { if (it.endsWith("/")) it else "$it/" }
        val cliente = http.newBuilder()
            .apply {
                if (paraFotos) readTimeout(3, TimeUnit.MINUTES).writeTimeout(3, TimeUnit.MINUTES)
            }
            .addInterceptor { cadena ->
                cadena.proceed(
                    cadena.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(cliente)
            .addConverterFactory(JsonApp.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PcApi::class.java)
    }
}
