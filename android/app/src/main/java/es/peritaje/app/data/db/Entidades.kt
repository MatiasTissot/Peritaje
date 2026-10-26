package es.peritaje.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EstadoCita { PENDIENTE, VISITADA, ENVIADA }

/** Estado de subida a Drive (a través del PC). SUBIDA solo cuando Drive ha confirmado la foto entera. */
enum class EstadoSubida { PENDIENTE, SUBIENDO, SUBIDA, ERROR }

/**
 * Una visita (página de la base Visitas de Notion). Un encargo puede tener N visitas,
 * cada una con su checklist, sus fotos y su avance.
 */
@Entity(tableName = "cita", indices = [Index("idEncargo")])
data class Cita(
    /**
     * Id de la página de la visita en Notion. En datos antiguos (o si el PC aún no manda
     * id_visita) es igual a [idEncargo].
     */
    @PrimaryKey val idVisita: String,
    /** Expediente (Exp_Gabinete), p. ej. MA26036021. */
    val idEncargo: String,
    /** Fecha y hora de la visita (epoch millis). */
    val fechaHora: Long,
    val direccion: String,
    val asegurado: String,
    val telefonoContacto: String? = null,
    val tipoSiniestro: String? = null,
    val notasGabinete: String? = null,
    // Datos extra que manda el PC
    val titulo: String? = null,
    val expCompania: String? = null,
    val urlMaps: String? = null,
    val notasVisita: String? = null,
    /** Lista de contactos en JSON (ver data.red.ContactoDto). */
    val contactosJson: String? = null,
    // Checklist de visita
    val fechaOcurrencia: String? = null,
    val causa: String? = null,
    val hayCausante: Boolean? = null,
    val quienAtiende: String? = null,
    /** Sin uso en pantalla desde la v0.1.4 (se sustituye por los datos del causante); se conserva lo ya escrito. */
    val testigos: String? = null,
    val observacionesLibres: String? = null,
    // Datos del causante (solo si hay causante). Al enviar se crea también como contacto en Notion.
    val causanteNombre: String? = null,
    val causanteTelefono: String? = null,
    val causanteDni: String? = null,
    /** Vivienda o local del causante (piso, puerta...). */
    val causanteUbicacion: String? = null,
    /** Compañía aseguradora y póliza del causante. */
    val causanteAseguradora: String? = null,
    // Ciclo de vida
    val estado: EstadoCita = EstadoCita.PENDIENTE,
    val fechaVisitaRealizada: Long? = null,
    val fechaEnvio: Long? = null,
    /** Identificador único del envío del avance, para que un reintento no lo duplique en Notion. */
    val idEnvio: String? = null,
    val ultimoError: String? = null,
    /** La visita se anuló o borró en Notion, pero tiene datos sin enviar: se conserva hasta que el perito decida. */
    @ColumnInfo(defaultValue = "0") val anulada: Boolean = false,
    /** Aviso para el perito sobre cambios hechos en Notion (fecha movida, anulada...). */
    val avisoNotion: String? = null,
) {
    fun checklistVacio(): Boolean =
        listOf(
            fechaOcurrencia, causa, quienAtiende, testigos, observacionesLibres,
            causanteNombre, causanteTelefono, causanteDni, causanteUbicacion, causanteAseguradora,
        ).all { it.isNullOrBlank() } &&
            hayCausante == null
}

@Entity(
    tableName = "foto",
    foreignKeys = [ForeignKey(
        entity = Cita::class,
        parentColumns = ["idVisita"],
        childColumns = ["idVisita"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("idVisita"), Index("idEncargo")],
)
data class FotoPendiente(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idVisita: String,
    /** Expediente, para la subida a Drive. */
    val idEncargo: String,
    val uriMediastore: String,
    val nombreArchivo: String,
    /** Orden dentro de la visita (el nombre del archivo es la fecha y hora). */
    val numero: Int,
    val estadoSubida: EstadoSubida = EstadoSubida.PENDIENTE,
    val intentos: Int = 0,
    val fechaCaptura: Long,
    /**
     * Quitada del expediente por el perito. La fila se conserva (y el archivo sigue en la galería)
     * para que la numeración no se repita y para no perder nunca una foto.
     */
    @ColumnInfo(defaultValue = "0") val quitada: Boolean = false,
    /** Id del archivo en Google Drive, cuando ya está subida. */
    val idDrive: String? = null,
    val fechaSubida: Long? = null,
    /** Motivo del último fallo de subida (se reintenta solo). */
    val errorSubida: String? = null,
)

/**
 * Contacto añadido por el perito durante la visita (inquilino, propietario, vecino...).
 * Se manda con el avance y el PC lo crea en la base Contactos de Notion.
 */
@Entity(
    tableName = "contacto_visita",
    foreignKeys = [ForeignKey(
        entity = Cita::class,
        parentColumns = ["idVisita"],
        childColumns = ["idVisita"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("idVisita")],
)
data class ContactoVisita(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idVisita: String,
    val rol: String,
    val nombre: String = "",
    val telefono: String? = null,
    val email: String? = null,
    val dni: String? = null,
    /** Vivienda o dirección (piso, puerta...). */
    val direccion: String? = null,
    val notas: String? = null,
    val creado: Long = System.currentTimeMillis(),
) {
    fun vacio(): Boolean = listOf(nombre, telefono, email, dni, direccion, notas).all { it.isNullOrBlank() }

    companion object {
        /** Roles de la base Contactos de Notion. */
        val ROLES = listOf(
            "Inquilino", "Perjudicado", "Causante", "Comunidad", "Administrador de fincas",
            "Asegurado", "Gestoria", "Perito Compania", "Otro",
        )
    }
}

@Entity(tableName = "configuracion")
data class Configuracion(
    @PrimaryKey val id: Int = 1,
    val urlPc: String = "",
    val ultimaSincronizacion: Long? = null,
    val ultimoErrorSync: String? = null,
)

data class CitaResumen(
    @Embedded val cita: Cita,
    val numFotos: Int,
)
