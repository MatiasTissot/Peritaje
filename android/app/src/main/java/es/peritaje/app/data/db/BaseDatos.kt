package es.peritaje.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CitaDao {
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM foto f WHERE f.idEncargo = c.idEncargo) AS numFotos " +
            "FROM cita c ORDER BY c.fechaHora"
    )
    fun observarResumenes(): Flow<List<CitaResumen>>

    @Query("SELECT * FROM cita WHERE idEncargo = :id")
    fun observar(id: String): Flow<Cita?>

    @Query("SELECT * FROM cita WHERE idEncargo = :id")
    suspend fun obtener(id: String): Cita?

    @Query("SELECT * FROM cita WHERE estado = 'VISITADA'")
    suspend fun visitadas(): List<Cita>

    @Query("SELECT COUNT(*) FROM cita WHERE estado = 'VISITADA'")
    fun observarPendientesDeEnvio(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(cita: Cita)

    @Update
    suspend fun actualizar(cita: Cita)

    @Query("DELETE FROM cita WHERE idEncargo = :id")
    suspend fun borrar(id: String)

    @Query("DELETE FROM cita WHERE estado = 'ENVIADA' AND fechaEnvio < :limite")
    suspend fun purgarEnviadasAntesDe(limite: Long): Int
}

@Dao
interface FotoDao {
    @Query("SELECT * FROM foto WHERE idEncargo = :id ORDER BY numero")
    fun observarDe(id: String): Flow<List<FotoPendiente>>

    @Query("SELECT COUNT(*) FROM foto WHERE idEncargo = :id")
    suspend fun contar(id: String): Int

    @Query("SELECT MAX(numero) FROM foto WHERE idEncargo = :id")
    suspend fun maxNumero(id: String): Int?

    @Insert
    suspend fun insertar(foto: FotoPendiente): Long

    @Delete
    suspend fun borrar(foto: FotoPendiente)
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

@Database(entities = [Cita::class, FotoPendiente::class, Configuracion::class], version = 1, exportSchema = true)
@TypeConverters(Conversores::class)
abstract class BaseDatos : RoomDatabase() {
    abstract fun citas(): CitaDao
    abstract fun fotos(): FotoDao
    abstract fun config(): ConfigDao

    companion object {
        fun crear(context: Context): BaseDatos =
            Room.databaseBuilder(context, BaseDatos::class.java, "peritaje.db").build()
    }
}
