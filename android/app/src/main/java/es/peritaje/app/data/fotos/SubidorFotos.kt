package es.peritaje.app.data.fotos

/**
 * Punto de enganche para el futuro módulo de Google Drive.
 *
 * Cuando exista, la implementación encolará en WorkManager la subida resumible de las
 * fotos PENDIENTE de la cita a Expedientes_2026/<ID_Encargo>/Fotografias y actualizará
 * FotoPendiente.estadoSubida. De momento las fotos se quedan solo en el móvil.
 */
interface SubidorFotos {
    suspend fun encolar(idEncargo: String)
}

object SubidorFotosDesactivado : SubidorFotos {
    override suspend fun encolar(idEncargo: String) = Unit
}
