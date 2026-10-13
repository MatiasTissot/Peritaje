package es.peritaje.app

import android.app.Application
import android.content.Context
import es.peritaje.app.data.AlmacenToken
import es.peritaje.app.data.Repositorio
import es.peritaje.app.data.db.BaseDatos
import es.peritaje.app.data.fotos.AlmacenFotos
import es.peritaje.app.data.fotos.SubidorFotosDesactivado
import es.peritaje.app.trabajo.Planificador

/** Dependencias de la app, creadas una sola vez. */
class Contenedor(context: Context) {
    private val db = BaseDatos.crear(context)
    // Al añadir Google Drive, sustituir SubidorFotosDesactivado por la implementación real.
    val repositorio = Repositorio(db, AlmacenToken(context), SubidorFotosDesactivado)
    val fotos = AlmacenFotos(context, db.fotos())
}

class PeritajeApp : Application() {
    lateinit var contenedor: Contenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
        Planificador.programarPeriodicos(this)
    }
}
