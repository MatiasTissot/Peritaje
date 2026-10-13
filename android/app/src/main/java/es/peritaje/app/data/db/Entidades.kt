package es.peritaje.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EstadoCita { PENDIENTE, VISITADA, ENVIADA }

/** Estado de subida a Drive. Hasta que exista el módulo de Drive, todas quedan en PENDIENTE. */
enum class EstadoSubida { PENDIENTE, SUBIENDO, SUBIDA, ERROR }

@Entity(tableName = "cita")
data class Cita(
    @PrimaryKey val idEncargo: String,
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
    val testigos: String? = null,
    val observacionesLibres: String? = null,
    // Ciclo de vida
    val estado: EstadoCita = EstadoCita.PENDIENTE,
    val fechaVisitaRealizada: Long? = null,
    val fechaEnvio: Long? = null,
    /** Identificador único del envío del avance, para que un reintento no lo duplique en Notion. */
    val idEnvio: String? = null,
    val ultimoError: String? = null,
    /** Si no es null, el PC ha cambiado la fecha y hay que preguntar al perito qué hacer. */
    val fechaHoraNueva: Long? = null,
) {
    fun checklistVacio(): Boolean =
        listOf(fechaOcurrencia, causa, quienAtiende, testigos, observacionesLibres).all { it.isNullOrBlank() } &&
            hayCausante == null
}

@Entity(
    tableName = "foto",
    foreignKeys = [ForeignKey(
        entity = Cita::class,
        parentColumns = ["idEncargo"],
        childColumns = ["idEncargo"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("idEncargo")],
)
data class FotoPendiente(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idEncargo: String,
    val uriMediastore: String,
    val nombreArchivo: String,
    val numero: Int,
    val estadoSubida: EstadoSubida = EstadoSubida.PENDIENTE,
    val intentos: Int = 0,
    val fechaCaptura: Long,
)

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
