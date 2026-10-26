package es.peritaje.app.ui.detalle

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.peritaje.app.PeritajeApp
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.ContactoVisita
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.data.db.FotoPendiente
import es.peritaje.app.data.Repositorio
import es.peritaje.app.trabajo.Planificador
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class DetalleViewModel(app: Application, val id: String) : AndroidViewModel(app) {
    private val contenedor = (app as PeritajeApp).contenedor
    private val repo = contenedor.repositorio
    private val almacen = contenedor.fotos

    val cita: StateFlow<Cita?> = repo.observarCita(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val fotos: StateFlow<List<FotoPendiente>> =
        repo.observarFotos(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Citas activas en orden, para saltar a la anterior / siguiente sin volver a la lista. */
    val vecinas: StateFlow<Pair<String?, String?>> = repo.resumenes.map { lista ->
        val ids = lista.filter { it.cita.estado != EstadoCita.ENVIADA }.map { it.cita.idVisita }
        val i = ids.indexOf(id)
        if (i < 0) null to null else ids.getOrNull(i - 1) to ids.getOrNull(i + 1)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

    /**
     * Copia editable del checklist. Es un borrador que se guarda en Room con cada cambio, sin esperas,
     * en el ámbito de la app: aunque se cierre la pantalla o la app, lo escrito ya está en disco.
     */
    var borrador by mutableStateOf<Cita?>(null)
        private set
    @Volatile private var ultimo: Cita? = null
    private val candado = Mutex()

    var mensaje by mutableStateOf<String?>(null)
        private set

    /** Contactos añadidos en la visita. Se editan aquí y cada cambio se guarda al momento en Room. */
    val contactos = mutableStateListOf<ContactoVisita>()
    private val ultimosContactos = ConcurrentHashMap<Long, ContactoVisita>()

    init {
        viewModelScope.launch { borrador = repo.observarCita(id).filterNotNull().first() }
        viewModelScope.launch { contactos.addAll(repo.contactosDe(id)) }
    }

    val editable: Boolean get() = cita.value?.estado == EstadoCita.PENDIENTE

    fun editar(cambio: (Cita) -> Cita) {
        val actual = borrador ?: return
        if (!editable) return
        val nuevo = cambio(actual)
        borrador = nuevo
        ultimo = nuevo
        contenedor.ambito.launch { guardarUltimo() }
    }

    /** Escribe la última versión del borrador y de los contactos (si llegan varias a la vez, la última siempre gana). */
    private suspend fun guardarUltimo() = candado.withLock {
        ultimo?.let { repo.guardarChecklist(it) }
        ultimosContactos.values.forEach { repo.guardarContacto(it) }
    }

    fun anadirContacto(rol: String) {
        if (!editable) return
        viewModelScope.launch {
            val nuevoId = contenedor.ambito.async { repo.nuevoContacto(id, rol) }.await()
            contactos.add(ContactoVisita(id = nuevoId, idVisita = id, rol = rol))
        }
    }

    fun editarContacto(contactoId: Long, cambio: (ContactoVisita) -> ContactoVisita) {
        if (!editable) return
        val i = contactos.indexOfFirst { it.id == contactoId }
        if (i < 0) return
        val nuevo = cambio(contactos[i])
        contactos[i] = nuevo
        ultimosContactos[contactoId] = nuevo
        contenedor.ambito.launch { guardarUltimo() }
    }

    fun borrarContacto(contactoId: Long) {
        if (!editable) return
        contactos.removeAll { it.id == contactoId }
        contenedor.ambito.launch {
            candado.withLock {
                ultimosContactos.remove(contactoId)
                repo.borrarContacto(contactoId)
            }
        }
    }

    override fun onCleared() {
        contenedor.ambito.launch { guardarUltimo() }
    }

    fun finalizar() {
        viewModelScope.launch {
            val hecho = contenedor.ambito.async {
                guardarUltimo()
                repo.finalizarVisita(id)
            }.await()
            if (hecho) {
                Planificador.enviarAvance(getApplication(), id)
                mensaje = "Visita finalizada. El avance se enviará al PC en cuanto haya red."
            }
        }
    }

    fun limpiarMensaje() { mensaje = null }

    fun quitarAviso() {
        contenedor.ambito.launch { repo.quitarAviso(id) }
    }

    /** Texto del avance tal como se enviaría, para copiarlo antes de descartar una visita anulada. */
    fun textoAvance(): String? = (borrador ?: cita.value)?.let { Repositorio.textoAvance(it, fotos.value.size, contactos.toList()) }

    /** Descarta una visita anulada en Notion. Las fotos siguen en la galería. */
    fun descartarAnulada(alTerminar: () -> Unit) {
        viewModelScope.launch {
            val hecho = contenedor.ambito.async { guardarUltimo(); repo.descartarAnulada(id) }.await()
            if (hecho) alTerminar()
        }
    }

    // --- fotos ---------------------------------------------------------------

    /**
     * Reserva el archivo de la siguiente foto y devuelve la URI donde debe escribir la cámara.
     * La reserva queda apuntada en disco, así que la foto se rescata aunque Android cierre la app.
     */
    suspend fun prepararFoto(): Uri? {
        val c = cita.value ?: return null
        return runCatching { almacen.preparar(c) }
            .onFailure { mensaje = "No se pudo preparar la foto: ${it.message}" }
            .getOrNull()
            ?.uriEscritura
    }

    /**
     * La cámara ha vuelto. Se guarda cualquier foto con contenido, aunque la cámara diga "cancelado".
     * Devuelve true si se guardó alguna (para preguntar si se quiere tomar otra).
     */
    suspend fun resultadoCamara(ok: Boolean): Boolean {
        val guardadas = contenedor.ambito.async { almacen.alVolverDeCamara(id) }.await()
        if (ok && guardadas == 0) mensaje = "La cámara no devolvió ninguna foto"
        return guardadas > 0
    }

    fun importar(uris: List<Uri>) {
        val c = cita.value ?: return
        viewModelScope.launch {
            val fallos = contenedor.ambito.async {
                uris.count { runCatching { almacen.importar(c, it) }.isFailure }
            }.await()
            if (fallos > 0) mensaje = "No se pudieron copiar $fallos fotos (siguen en la galería)"
        }
    }

    /** Quita la foto del expediente; el archivo sigue en la galería. */
    fun quitarFoto(foto: FotoPendiente) {
        if (!editable) return
        contenedor.ambito.launch { almacen.quitar(foto) }
    }

    fun uriParaVer(foto: FotoPendiente): Uri = almacen.uriParaVer(foto)
}
