package es.peritaje.app

import android.app.Application
import android.content.Context
import es.peritaje.app.data.AlmacenToken
import es.peritaje.app.data.Repositorio
import es.peritaje.app.data.db.BaseDatos
import es.peritaje.app.data.fotos.AlmacenFotos
import es.peritaje.app.data.fotos.SubidaFotos
import es.peritaje.app.trabajo.Planificador
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Dependencias de la app, creadas una sola vez. */
class Contenedor(context: Context) {
    private val db = BaseDatos.crear(context)
    val fotos = AlmacenFotos(context, db.fotos(), db.citas())
    // Las fotos van a Drive a través del PC, con la misma conexión que el avance.
    val subidaFotos = SubidaFotos(context.applicationContext, db.fotos(), db.citas()) { repositorio.apiFotos() }
    val repositorio = Repositorio(db, AlmacenToken(context), subidaFotos, fotos::hayCapturaEnCurso)

    /** Ámbito de toda la app: lo que se lanza aquí (autoguardado, rescate de fotos) no se cancela al salir de una pantalla. */
    val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
