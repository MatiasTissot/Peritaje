package es.peritaje.app.ui.citas

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import es.peritaje.app.PeritajeApp
import es.peritaje.app.data.db.CitaResumen
import es.peritaje.app.trabajo.Planificador
import es.peritaje.app.ui.comun.Nube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class EstadoSync(
    val nube: Nube = Nube.OK,
    val ultima: Long? = null,
    val error: String? = null,
    val pendientes: Int = 0,
    /** Más de 12 horas sin sincronizar: se avisa en pantalla. */
    val antigua: Boolean = false,
    val sincronizando: Boolean = false,
)

class CitasViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as PeritajeApp).contenedor.repositorio

    val citas: StateFlow<List<CitaResumen>> =
        repo.resumenes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val sincronizando: Flow<Boolean> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Planificador.SYNC_AHORA)
            .map { lista -> lista.any { it.state == WorkInfo.State.RUNNING } }

    val estadoSync: StateFlow<EstadoSync> = combine(
        conectividad(app), sincronizando, repo.configuracion, repo.pendientesDeEnvio,
    ) { online, corriendo, config, pendientes ->
        val ultima = config?.ultimaSincronizacion
        val nube = when {
            !repo.configurado(config?.urlPc) -> Nube.SIN_CONFIGURAR
            corriendo -> Nube.SINCRONIZANDO
            !online -> Nube.SIN_CONEXION
            config?.ultimoErrorSync != null -> Nube.ERROR
            pendientes > 0 -> Nube.PENDIENTE
            else -> Nube.OK
        }
        EstadoSync(
            nube = nube,
            ultima = ultima,
            error = config?.ultimoErrorSync,
            pendientes = pendientes,
            antigua = ultima == null || System.currentTimeMillis() - ultima > TimeUnit.HOURS.toMillis(12),
            sincronizando = corriendo,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EstadoSync())

    fun sincronizar() = Planificador.sincronizarAhora(getApplication())

    fun resolverCambioFecha(id: String, conservar: Boolean) {
        viewModelScope.launch { repo.resolverCambioFecha(id, conservar) }
    }
}

/** true cuando hay red con internet. */
private fun conectividad(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    fun hayRed(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(true) }
        override fun onLost(network: Network) { trySend(hayRed()) }
    }
    trySend(hayRed())
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
