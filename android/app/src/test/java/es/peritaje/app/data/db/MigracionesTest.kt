package es.peritaje.app.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprueba que al actualizar la app no se pierde nada: se crea una base como la de la
 * versión 0.1.1 (esquema 1) con una visita a medias y una foto, y se abre con la versión actual.
 * Room valida además que el resultado coincide exactamente con el esquema esperado.
 */
@RunWith(RobolectricTestRunner::class)
// Application vacía: la de la app arranca WorkManager, que aquí no hace falta.
@Config(sdk = [34], application = Application::class)
class MigracionesTest {

    @Test
    fun deLaPrimeraVersionALaActualSinPerderDatos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ruta = context.getDatabasePath("peritaje.db")
        ruta.parentFile?.mkdirs()
        ruta.delete()

        SQLiteDatabase.openOrCreateDatabase(ruta, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cita` (`idEncargo` TEXT NOT NULL, `fechaHora` INTEGER NOT NULL, " +
                    "`direccion` TEXT NOT NULL, `asegurado` TEXT NOT NULL, `telefonoContacto` TEXT, `tipoSiniestro` TEXT, " +
                    "`notasGabinete` TEXT, `titulo` TEXT, `expCompania` TEXT, `urlMaps` TEXT, `notasVisita` TEXT, " +
                    "`contactosJson` TEXT, `fechaOcurrencia` TEXT, `causa` TEXT, `hayCausante` INTEGER, " +
                    "`quienAtiende` TEXT, `testigos` TEXT, `observacionesLibres` TEXT, `estado` TEXT NOT NULL, " +
                    "`fechaVisitaRealizada` INTEGER, `fechaEnvio` INTEGER, `idEnvio` TEXT, `ultimoError` TEXT, " +
                    "`fechaHoraNueva` INTEGER, PRIMARY KEY(`idEncargo`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `foto` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`idEncargo` TEXT NOT NULL, `uriMediastore` TEXT NOT NULL, `nombreArchivo` TEXT NOT NULL, " +
                    "`numero` INTEGER NOT NULL, `estadoSubida` TEXT NOT NULL, `intentos` INTEGER NOT NULL, " +
                    "`fechaCaptura` INTEGER NOT NULL, FOREIGN KEY(`idEncargo`) REFERENCES `cita`(`idEncargo`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_foto_idEncargo` ON `foto` (`idEncargo`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `configuracion` (`id` INTEGER NOT NULL, `urlPc` TEXT NOT NULL, " +
                    "`ultimaSincronizacion` INTEGER, `ultimoErrorSync` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO cita (idEncargo, fechaHora, direccion, asegurado, causa, observacionesLibres, estado) " +
                    "VALUES ('MA26000001', 1760000000000, 'Calle Mayor 1', 'Ana', 'Fuga en baño', 'Mancha en techo', 'PENDIENTE')"
            )
            db.execSQL(
                "INSERT INTO foto (idEncargo, uriMediastore, nombreArchivo, numero, estadoSubida, intentos, fechaCaptura) " +
                    "VALUES ('MA26000001', 'content://media/external/images/media/7', 'MA26000001_01.jpg', 1, 'PENDIENTE', 0, 1760000100000)"
            )
            db.execSQL("INSERT INTO configuracion (id, urlPc) VALUES (1, 'http://100.1.2.3:8000')")
            db.version = 1
        }

        val bd = BaseDatos.crear(context)
        try {
            runBlocking {
                val cita = bd.citas().obtener("MA26000001")
                assertNotNull("La visita a medias debe seguir ahí", cita)
                cita!!
                assertEquals("MA26000001", cita.idEncargo)
                assertEquals("Fuga en baño", cita.causa)
                assertEquals("Mancha en techo", cita.observacionesLibres)
                assertEquals(1, bd.fotos().contar("MA26000001"))
                assertEquals("http://100.1.2.3:8000", bd.config().obtener()?.urlPc)

                // Una segunda visita del mismo encargo convive con la primera.
                bd.citas().insertar(cita.copy(idVisita = "visita2", causa = null, observacionesLibres = null))
                assertEquals(0, bd.fotos().contar("visita2"))

                // Al pasar la visita antigua a su id de Notion, se lleva el checklist y las fotos.
                bd.citas().cambiarId("MA26000001", "visita1")
                assertNull(bd.citas().obtener("MA26000001"))
                assertEquals("Fuga en baño", bd.citas().obtener("visita1")?.causa)
                assertEquals(1, bd.fotos().contar("visita1"))

                // No se borra una visita con datos aunque se pida "borrar si vacía".
                assertEquals(0, bd.citas().borrarSiVacia("visita1"))
                assertEquals(1, bd.citas().borrarSiVacia("visita2"))
            }
        } finally {
            bd.close()
        }
    }
}
