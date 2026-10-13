package es.peritaje.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface CitaDao {
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM foto f WHERE f.idEncargo = c.idEncargo AND f.quitada = 0) AS numFotos " +
            "FROM cita c ORDER BY c.fechaHora"
    )
    fun observarResumenes(): Flow<List<CitaResumen>>

    @Query("SELECT * FROM cita WHERE idEncargo = :id")
    fun observar(id: String): Flow<Cita?>

    @Query("SELECT * FROM cita WHERE idEncargo = :id")
    suspend fun obtener(id: String): Cita?

    @Query("SELECT * FROM cita WHERE estado = 'VISITADA'")
    suspend fun visitadas(): List<Cita>

    @Query("SELECT * FROM cita WHERE estado != 'ENVIADA'")
    suspend fun noEnviadas(): List<Cita>

    @Query("SELECT COUNT(*) FROM cita WHERE estado = 'VISITADA'")
    fun observarPendientesDeEnvio(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(cita: Cita)

    @Update
    suspend fun actualizar(cita: Cita)

    @Query("DELETE FROM cita WHERE idEncargo = :id")
    suspend fun borrar(id: String)

    // Actualizaciones por columnas: así una sincronización nunca pisa lo que el perito está escribiendo.

    @Query(
        "UPDATE cita SET fechaHora = :fechaHora, direccion = :direccion, asegurado = :asegurado, " +
            "telefonoContacto = :telefono, tipoSiniestro = :tipo, notasGabinete = :notasGabinete, titulo = :titulo, " +
            "expCompania = :expCompania, urlMaps = :urlMaps, notasVisita = :notasVisita, contactosJson = :contactosJson, " +
            "anulada = 0, avisoNotion = :aviso WHERE idEncargo = :id"
    )
    suspend fun actualizarDatosNotion(
        id: String, fechaHora: Long, direccion: String, asegurado: String, telefono: String?, tipo: String?,
        notasGabinete: String?, titulo: String?, expCompania: String?, urlMaps: String?, notasVisita: String?,
        contactosJson: String?, aviso: String?,
    )

    @Query("UPDATE cita SET anulada = 1, avisoNotion = :aviso WHERE idEncargo = :id")
    suspend fun marcarAnulada(id: String, aviso: String)

    @Query("UPDATE cita SET avisoNotion = NULL WHERE idEncargo = :id")
    suspend fun quitarAviso(id: String)

    /**
     * Borra la cita solo si sigue sin datos: pendiente, checklist vacío y sin fotos.
     * Se comprueba en la misma sentencia para no borrar algo que el perito acaba de escribir.
     */
    @Query(
        "DELETE FROM cita WHERE idEncargo = :id AND estado = 'PENDIENTE' " +
            "AND COALESCE(TRIM(fechaOcurrencia), '') = '' AND COALESCE(TRIM(causa), '') = '' " +
            "AND COALESCE(TRIM(quienAtiende), '') = '' AND COALESCE(TRIM(testigos), '') = '' " +
            "AND COALESCE(TRIM(observacionesLibres), '') = '' AND hayCausante IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM foto f WHERE f.idEncargo = cita.idEncargo AND f.quitada = 0)"
    )
    suspend fun borrarSiVacia(id: String): Int

    /** Nueva visita de un encargo ya enviado: vuelve a PENDIENTE con el checklist en blanco (las fotos se conservan). */
    @Query(
        "UPDATE cita SET estado = 'PENDIENTE', fechaOcurrencia = NULL, causa = NULL, hayCausante = NULL, " +
            "quienAtiende = NULL, testigos = NULL, observacionesLibres = NULL, fechaVisitaRealizada = NULL, " +
            "fechaEnvio = NULL, idEnvio = NULL, ultimoError = NULL, anulada = 0, avisoNotion = NULL " +
            "WHERE idEncargo = :id AND estado = 'ENVIADA'"
    )
    suspend fun reabrir(id: String): Int

    @Query(
        "UPDATE cita SET fechaOcurrencia = :fechaOcurrencia, causa = :causa, hayCausante = :hayCausante, " +
            "quienAtiende = :quienAtiende, testigos = :testigos, observacionesLibres = :observaciones " +
            "WHERE idEncargo = :id AND estado = 'PENDIENTE'"
    )
    suspend fun guardarChecklist(
        id: String, fechaOcurrencia: String?, causa: String?, hayCausante: Boolean?,
        quienAtiende: String?, testigos: String?, observaciones: String?,
    )

    @Query(
        "UPDATE cita SET estado = 'VISITADA', fechaVisitaRealizada = :ahora, idEnvio = :idEnvio, ultimoError = NULL " +
            "WHERE idEncargo = :id AND estado = 'PENDIENTE'"
    )
    suspend fun finalizar(id: String, ahora: Long, idEnvio: String): Int

    @Query("UPDATE cita SET estado = 'ENVIADA', fechaEnvio = :ahora, ultimoError = NULL WHERE idEncargo = :id AND estado = 'VISITADA'")
    suspend fun marcarEnviada(id: String, ahora: Long)

    @Query("UPDATE cita SET ultimoError = :error WHERE idEncargo = :id")
    suspend fun ponerError(id: String, error: String?)

    /**
     * Borra las citas enviadas hace más de 7 días, salvo las que tengan fotos aún no subidas
     * (no se borra nada hasta que el destino lo confirme; los archivos de la galería nunca se tocan).
     */
    @Query(
        "DELETE FROM cita WHERE estado = 'ENVIADA' AND fechaEnvio < :limite AND NOT EXISTS " +
            "(SELECT 1 FROM foto f WHERE f.idEncargo = cita.idEncargo AND f.quitada = 0 AND f.estadoSubida != 'SUBIDA')"
    )
    suspend fun purgarEnviadasAntesDe(limite: Long): Int
}

@Dao
interface FotoDao {
    @Query("SELECT * FROM foto WHERE idEncargo = :id AND quitada = 0 ORDER BY numero")
    fun observarDe(id: String): Flow<List<FotoPendiente>>

    @Query("SELECT COUNT(*) FROM foto WHERE idEncargo = :id AND quitada = 0")
    suspend fun contar(id: String): Int

    @Query("SELECT COUNT(*) FROM foto WHERE uriMediastore = :uri")
    suspend fun existeUri(uri: String): Int

    @Query("SELECT MAX(numero) FROM foto WHERE idEncargo = :id")
    suspend fun maxNumero(id: String): Int?

    @Insert
    suspend fun insertar(foto: FotoPendiente): Long

    @Query("UPDATE foto SET quitada = 1 WHERE id = :id")
    suspend fun quitar(id: Long)
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM configuracion WHERE id = 1")
    fun observar(): Flow<Configuracion?>

    @Query("SELECT * FROM configuracion WHERE id = 1")
    suspend fun obtener(): Configuracion?

    @Upsert
    suspend fun guardar(config: Configuracion)
}

class Conversores {
    @TypeConverter fun estadoCitaATexto(e: EstadoCita): String = e.name
    @TypeConverter fun textoAEstadoCita(s: String): EstadoCita = EstadoCita.valueOf(s)
    @TypeConverter fun estadoSubidaATexto(e: EstadoSubida): String = e.name
    @TypeConverter fun textoAEstadoSubida(s: String): EstadoSubida = EstadoSubida.valueOf(s)
}

@Database(entities = [Cita::class, FotoPendiente::class, Configuracion::class], version = 2, exportSchema = true)
@TypeConverters(Conversores::class)
abstract class BaseDatos : RoomDatabase() {
    abstract fun citas(): CitaDao
    abstract fun fotos(): FotoDao
    abstract fun config(): ConfigDao

    companion object {
        /** v0.1.2: visitas anuladas en Notion, avisos y fotos quitadas sin borrar. */
        private val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cita ADD COLUMN anulada INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cita ADD COLUMN avisoNotion TEXT")
                db.execSQL("ALTER TABLE foto ADD COLUMN quitada INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Sin migraciones destructivas: si faltara una, la app debe fallar antes que borrar datos de visitas.
        fun crear(context: Context): BaseDatos =
            Room.databaseBuilder(context, BaseDatos::class.java, "peritaje.db")
                .addMigrations(MIGRACION_1_2)
                .build()
    }
}
