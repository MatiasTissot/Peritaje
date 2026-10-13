package es.peritaje.app.ui.detalle

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.peritaje.app.PeritajeApp
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.data.db.FotoPendiente
import es.peritaje.app.data.fotos.AlmacenFotos
import es.peritaje.app.trabajo.Planificador
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetalleViewModel(app: Application, val id: String) : AndroidViewModel(app) {
    private val contenedor = (app as PeritajeApp).contenedor
    private val repo = contenedor.repositorio
    private val almacen = contenedor.fotos

    val cita: StateFlow<Cita?> = repo.observarCita(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val fotos: StateFlow<List<FotoPendiente>> =
        repo.observarFotos(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Citas activas en orden, para saltar a la anterior / siguiente sin volver a la lista. */
    val vecinas: StateFlow<Pair<String?, String?>> = repo.resumenes.map { lista ->
        val ids = lista.filter { it.cita.estado != EstadoCita.ENVIADA }.map { it.cita.idEncargo }
        val i = ids.indexOf(id)
        if (i < 0) null to null else ids.getOrNull(i - 1) to ids.getOrNull(i + 1)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

    /** Copia editable del checklist; se guarda sola en Room unos instantes después de cada cambio. */
    var borrador by mutableStateOf<Cita?>(null)
        private set
    private var guardado: Job? = null

    var mensaje by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch { borrador = repo.observarCita(id).filterNotNull().first() }
    }

    val editable: Boolean get() = cita.value?.estado == EstadoCita.PENDIENTE

    fun editar(cambio: (Cita) -> Cita) {
        val actual = borrador ?: return
        if (!editable) return
        borrador = cambio(actual)
        guardado?.cancel()
        guardado = viewModelScope.launch {
            delay(400)
            borrador?.let { repo.guardarChecklist(it) }
        }
    }

    fun finalizar() {
        viewModelScope.launch {
            guardado?.cancel()
            borrador?.let { repo.guardarChecklist(it) }
            if (repo.finalizarVisita(id)) {
                Planificador.enviarAvance(getApplication(), id)
                mensaje = "Visita finalizada. El avance se enviará a Notion en cuanto haya red."
            }
        }
    }

    fun limpiarMensaje() { mensaje = null }

    // --- fotos ---------------------------------------------------------------

    // Se guarda aquí (y no en la pantalla) para que sobreviva a girar el móvil con la cámara abierta.
    private var destinoPendiente: AlmacenFotos.Destino? = null

    /** Reserva el hueco de la siguiente foto y devuelve la URI donde debe escribir la cámara. */
    suspend fun prepararFoto(): Uri? {
        val c = cita.value ?: return null
        return runCatching { almacen.preparar(c) }
            .onFailure { mensaje = "No se pudo preparar la foto: ${it.message}" }
            .getOrNull()
            ?.also { destinoPendiente = it }
            ?.uriEscritura
    }

    /** Devuelve true si la foto se guardó (para preguntar si se quiere tomar otra). */
    suspend fun resultadoCamara(ok: Boolean): Boolean {
        val destino = destinoPendiente ?: return false
        destinoPendiente = null
        val c = cita.value
        return if (ok && c != null) {
            almacen.confirmar(c, destino)
            true
        } else {
            almacen.descartar(destino)
            false
        }
    }

    fun importar(uris: List<Uri>) {
        val c = cita.value ?: return
        viewModelScope.launch {
            var fallos = 0
            uris.forEach { runCatching { almacen.importar(c, it) }.onFailure { fallos++ } }
            if (fallos > 0) mensaje = "No se pudieron copiar $fallos fotos"
        }
    }

    fun borrarFoto(foto: FotoPendiente) {
        if (!editable) return
        viewModelScope.launch { almacen.borrar(foto) }
    }
}
