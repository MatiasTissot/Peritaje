@file:OptIn(ExperimentalMaterial3Api::class)

package es.peritaje.app.ui.detalle

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import es.peritaje.app.data.Repositorio
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.ContactoVisita
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.data.db.EstadoSubida
import es.peritaje.app.data.db.FotoPendiente
import es.peritaje.app.ui.comun.IconoEstado
import es.peritaje.app.ui.comun.situacion
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun PantallaDetalle(vm: DetalleViewModel, volver: () -> Unit, irA: (String) -> Unit) {
    val cita by vm.cita.collectAsStateWithLifecycle()
    val fotos by vm.fotos.collectAsStateWithLifecycle()
    val vecinas by vm.vecinas.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var pestana by rememberSaveable { mutableStateOf(0) }
    var confirmarFin by remember { mutableStateOf(false) }
    var preguntarOtra by remember { mutableStateOf(false) }
    var fotoABorrar by remember { mutableStateOf<FotoPendiente?>(null) }
    var confirmarDescarte by remember { mutableStateOf(false) }

    // --- cámara y galería ----------------------------------------------------
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        scope.launch { if (vm.resultadoCamara(ok)) preguntarOtra = true }
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) vm.importar(uris)
    }
    var accionTrasPermiso by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) accionTrasPermiso?.invoke()
        accionTrasPermiso = null
    }
    // En Android 8-9 hace falta permiso de escritura para guardar en Pictures/; en 10+ no.
    fun conPermiso(accion: () -> Unit) {
        val necesita = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (necesita) {
            accionTrasPermiso = accion
            permiso.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else accion()
    }
    val abrirCamara: () -> Unit = {
        conPermiso {
            scope.launch {
                val uri = vm.prepararFoto() ?: return@launch
                try {
                    camara.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    vm.resultadoCamara(false)
                    snackbar.showSnackbar("No hay ninguna app de cámara")
                }
            }
        }
    }
    val abrirGaleria: () -> Unit = {
        conPermiso { galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    LaunchedEffect(vm.mensaje) {
        vm.mensaje?.let {
            snackbar.showSnackbar(it)
            vm.limpiarMensaje()
        }
    }

    val c = cita
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = volver, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                title = {
                    Column {
                        Text(c?.idEncargo.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        c?.let {
                            Text(Repositorio.fechaHoraLegible(it.fechaHora), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vecinas.first?.let(irA) }, enabled = vecinas.first != null, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Visita anterior", modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { vecinas.second?.let(irA) }, enabled = vecinas.second != null, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Visita siguiente", modifier = Modifier.size(32.dp))
                    }
                },
            )
        },
        bottomBar = {
            if (c?.estado == EstadoCita.PENDIENTE) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { confirmarFin = true },
                        modifier = Modifier.fillMaxWidth().padding(12.dp).height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text("Finalizar visita", style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
    ) { relleno ->
        if (c == null) {
            Box(Modifier.padding(relleno).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Esta visita ya no está en el móvil.")
            }
            return@Scaffold
        }
        Column(Modifier.padding(relleno).fillMaxSize()) {
            BandaEstado(c, fotos.size, reintentar = { vm.reintentarEnvio() })
            AvisoNotion(c, entendido = { vm.quitarAviso() }, descartar = { confirmarDescarte = true })
            PrimaryTabRow(selectedTabIndex = pestana) {
                listOf("Datos", "Visita", "Fotos (${fotos.size})").forEachIndexed { i, t ->
                    Tab(selected = pestana == i, onClick = { pestana = i }, modifier = Modifier.height(56.dp),
                        text = { Text(t, style = MaterialTheme.typography.labelLarge) })
                }
            }
            when (pestana) {
                0 -> PestanaDatos(c, context)
                1 -> PestanaVisita(vm)
                else -> PestanaFotos(fotos, vm.editable, abrirCamara, abrirGaleria,
                    abrir = { lanzar(context, Intent(Intent.ACTION_VIEW).setDataAndType(vm.uriParaVer(it), "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }, borrar = { fotoABorrar = it })
            }
        }
    }

    if (confirmarFin) {
        val b = vm.borrador ?: c
        val faltan = b?.let { camposVacios(it) } ?: emptyList()
        AlertDialog(
            onDismissRequest = { confirmarFin = false },
            title = { Text("¿Finalizar la visita?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Los datos de la visita quedarán bloqueados y el avance se enviará al PC en cuanto haya red.")
                    Text("Fotos: ${fotos.size}")
                    if (faltan.isNotEmpty()) {
                        Text("Revise los siguientes datos (no es obligatorio):", fontWeight = FontWeight.SemiBold)
                        faltan.forEach { Text("• $it") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { confirmarFin = false; vm.finalizar() }) { Text("Finalizar y enviar") }
            },
            dismissButton = { TextButton(onClick = { confirmarFin = false }) { Text("Seguir editando") } },
        )
    }

    if (preguntarOtra) {
        AlertDialog(
            onDismissRequest = { preguntarOtra = false },
            title = { Text("Foto guardada") },
            text = { Text("¿Desea tomar otra foto?") },
            confirmButton = { Button(onClick = { preguntarOtra = false; abrirCamara() }) { Text("Sí, otra") } },
            dismissButton = { TextButton(onClick = { preguntarOtra = false }) { Text("No") } },
        )
    }

    if (confirmarDescarte) {
        DialogoDescartar(vm, context, cerrar = { confirmarDescarte = false }, volver = volver)
    }

    fotoABorrar?.let { f ->
        AlertDialog(
            onDismissRequest = { fotoABorrar = null },
            title = { Text("¿Quitar ${f.nombreArchivo} del expediente?") },
            text = { Text("La foto no se borra: se queda en la galería del móvil (Pictures/PeritajeApp).") },
            confirmButton = {
                TextButton(onClick = { vm.quitarFoto(f); fotoABorrar = null }) {
                    Text("Quitar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { fotoABorrar = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DialogoDescartar(vm: DetalleViewModel, context: Context, cerrar: () -> Unit, volver: () -> Unit) {
    AlertDialog(
        onDismissRequest = cerrar,
        title = { Text("¿Descartar esta visita?") },
        text = {
            Text(
                "Notion la ha anulado. Se quitará del móvil. El texto del avance se copiará al portapapeles " +
                    "y las fotos se quedan en la galería (Pictures/PeritajeApp)."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.textoAvance()?.let { texto ->
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Avance", texto))
                }
                cerrar()
                vm.descartarAnulada(volver)
            }) { Text("Copiar y descartar", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = cerrar) { Text("Cancelar") } },
    )
}

/** Avisos de cambios hechos en Notion: visita anulada/borrada o fecha cambiada. */
@Composable
private fun AvisoNotion(c: Cita, entendido: () -> Unit, descartar: () -> Unit) {
    val texto = c.avisoNotion ?: if (c.anulada) "Esta visita está anulada en Notion." else return
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.anulada) {
                    TextButton(onClick = descartar, modifier = Modifier.height(48.dp)) { Text("Descartar visita") }
                } else {
                    TextButton(onClick = entendido, modifier = Modifier.height(48.dp)) { Text("Entendido") }
                }
            }
        }
    }
}

private fun camposVacios(c: Cita): List<String> = buildList {
    if (c.quienAtiende.isNullOrBlank()) add("Quién atiende")
    if (c.fechaOcurrencia.isNullOrBlank()) add("Fecha de ocurrencia")
    if (c.causa.isNullOrBlank()) add("Causa")
    if (c.hayCausante == null) add("Si hay causante")
    if (c.hayCausante == true && c.causanteNombre.isNullOrBlank()) add("Nombre del causante")
    if (c.observacionesLibres.isNullOrBlank()) add("Observaciones")
}

@Composable
private fun BandaEstado(c: Cita, numFotos: Int, reintentar: () -> Unit) {
    val s = situacion(c, numFotos)
    val texto = when (c.estado) {
        EstadoCita.PENDIENTE -> null
        EstadoCita.VISITADA -> c.ultimoError?.let { "No se pudo enviar: $it" }
            ?: "Visita finalizada. Enviando el avance al PC…"
        EstadoCita.ENVIADA -> "Avance enviado a Notion el ${c.fechaEnvio?.let { Repositorio.fechaHoraLegible(it) } ?: ""}"
    } ?: return
    Surface(color = s.color.copy(alpha = 0.12f)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconoEstado(s)
            Spacer(Modifier.width(12.dp))
            Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (c.estado == EstadoCita.VISITADA) {
                TextButton(onClick = reintentar, modifier = Modifier.height(48.dp)) { Text("Reintentar ahora") }
            } else {
                Icon(Icons.Filled.Lock, contentDescription = "Bloqueada", tint = s.color)
            }
        }
    }
}

// --- Datos -------------------------------------------------------------------

@Composable
private fun PestanaDatos(c: Cita, context: Context) {
    val contactos = remember(c.contactosJson) { Repositorio.contactos(c) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Etiqueta("Dirección")
                Text(c.direccion.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(
                    onClick = {
                        val uri = c.urlMaps?.let(Uri::parse) ?: Uri.parse("geo:0,0?q=" + Uri.encode(c.direccion))
                        lanzar(context, Intent(Intent.ACTION_VIEW, uri))
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.Directions, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cómo llegar")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Etiqueta("Asegurado")
                Text(c.asegurado.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
                c.telefonoContacto?.let { tel -> BotonLlamar(context, tel) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                c.titulo?.let { Dato("Encargo", it) }
                Dato("Tipo de siniestro", c.tipoSiniestro)
                Dato("Exp. compañía", c.expCompania)
                Dato("Notas del gabinete", c.notasGabinete)
                Dato("Notas de la visita", c.notasVisita)
            }
        }
        // Todos los contactos del encargo en Notion, con cualquier rol (inquilino, comunidad...).
        val otros = contactos.filter { it.nombre != c.asegurado || it.telefono != c.telefonoContacto }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Etiqueta("Contactos del encargo")
                if (otros.isEmpty()) {
                    Text("No hay más contactos en Notion.", style = MaterialTheme.typography.bodyMedium)
                }
                otros.forEachIndexed { i, k ->
                    if (i > 0) HorizontalDivider()
                    Text(k.rol.ifBlank { "Otro" }, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(k.nombre.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
                    k.direccion?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    k.email?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    k.notas?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    k.telefono?.takeIf { it.isNotBlank() }?.let { BotonLlamar(context, it) }
                }
            }
        }
    }
}

@Composable
private fun Etiqueta(t: String) =
    Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Dato(etiqueta: String, valor: String?) {
    Column {
        Etiqueta(etiqueta)
        Text(valor?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BotonLlamar(context: Context, tel: String) {
    FilledTonalButton(
        onClick = { lanzar(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel"))) },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(Icons.Filled.Call, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Llamar · $tel")
    }
}

private fun lanzar(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

// --- Visita ------------------------------------------------------------------

@Composable
private fun PestanaVisita(vm: DetalleViewModel) {
    val b = vm.borrador ?: return
    val editable = vm.editable
    var elegirFecha by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Campo("Quién atiende", b.quienAtiende, editable) { v -> vm.editar { it.copy(quienAtiende = v) } }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Campo("Fecha de ocurrencia", b.fechaOcurrencia, editable) { v -> vm.editar { it.copy(fechaOcurrencia = v) } }
            }
            IconButton(onClick = { elegirFecha = true }, enabled = editable, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Elegir fecha", modifier = Modifier.size(32.dp))
            }
        }

        Campo("Causa", b.causa, editable, lineas = 3) { v -> vm.editar { it.copy(causa = v) } }

        Column {
            Text("¿Hay causante?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            val opciones = listOf(true to "Sí", false to "No", null to "Sin determinar")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                opciones.forEachIndexed { i, (valor, texto) ->
                    SegmentedButton(
                        selected = b.hayCausante == valor,
                        onClick = { vm.editar { it.copy(hayCausante = valor) } },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = opciones.size),
                        enabled = editable,
                        modifier = Modifier.height(56.dp),
                    ) { Text(texto) }
                }
            }
        }

        if (b.hayCausante == true) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Etiqueta("Datos del causante")
                    Campo("Nombre", b.causanteNombre, editable) { v -> vm.editar { it.copy(causanteNombre = v) } }
                    Campo("Teléfono", b.causanteTelefono, editable, teclado = KeyboardType.Phone) { v ->
                        vm.editar { it.copy(causanteTelefono = v) }
                    }
                    Campo("DNI", b.causanteDni, editable) { v -> vm.editar { it.copy(causanteDni = v) } }
                    Campo("Vivienda (piso, puerta…)", b.causanteUbicacion, editable) { v ->
                        vm.editar { it.copy(causanteUbicacion = v) }
                    }
                    Campo("Aseguradora y póliza", b.causanteAseguradora, editable) { v ->
                        vm.editar { it.copy(causanteAseguradora = v) }
                    }
                }
            }
        }
        // Campo antiguo: solo se muestra si ya tenía algo escrito.
        if (!b.testigos.isNullOrBlank()) {
            Campo("Testigos", b.testigos, editable, lineas = 2) { v -> vm.editar { it.copy(testigos = v) } }
        }

        Contactos(vm, editable)

        Campo("Observaciones", b.observacionesLibres, editable, lineas = 8) { v -> vm.editar { it.copy(observacionesLibres = v) } }
        Spacer(Modifier.height(24.dp))
    }

    if (elegirFecha) {
        val estado = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { elegirFecha = false },
            confirmButton = {
                TextButton(onClick = {
                    estado.selectedDateMillis?.let { ms ->
                        val texto = FECHA.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                        vm.editar { it.copy(fechaOcurrencia = texto) }
                    }
                    elegirFecha = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { elegirFecha = false }) { Text("Cancelar") } },
        ) { DatePicker(state = estado) }
    }
}

/** Otros contactos de la visita (inquilino, perjudicado...). Se crean también en Notion al enviar. */
@Composable
private fun Contactos(vm: DetalleViewModel, editable: Boolean) {
    var aBorrar by remember { mutableStateOf<ContactoVisita?>(null) }
    var elegirRol by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Etiqueta("Otros contactos")
        vm.contactos.forEach { k ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ContactoVisita.ROLES.forEach { rol ->
                                FilterChip(
                                    selected = k.rol == rol,
                                    onClick = { vm.editarContacto(k.id) { it.copy(rol = rol) } },
                                    label = { Text(rol) },
                                    enabled = editable,
                                )
                            }
                        }
                        if (editable) {
                            IconButton(onClick = { aBorrar = k }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Delete, contentDescription = "Borrar contacto")
                            }
                        }
                    }
                    Campo("Nombre", k.nombre, editable) { v -> vm.editarContacto(k.id) { it.copy(nombre = v) } }
                    Campo("Teléfono", k.telefono, editable, teclado = KeyboardType.Phone) { v ->
                        vm.editarContacto(k.id) { it.copy(telefono = v) }
                    }
                    Campo("Vivienda o dirección", k.direccion, editable) { v -> vm.editarContacto(k.id) { it.copy(direccion = v) } }
                    Campo("DNI", k.dni, editable) { v -> vm.editarContacto(k.id) { it.copy(dni = v) } }
                    Campo("Email", k.email, editable, teclado = KeyboardType.Email) { v ->
                        vm.editarContacto(k.id) { it.copy(email = v) }
                    }
                    Campo("Notas", k.notas, editable, lineas = 2) { v -> vm.editarContacto(k.id) { it.copy(notas = v) } }
                }
            }
        }
        if (editable) {
            FilledTonalButton(onClick = { elegirRol = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Añadir contacto")
            }
        }
    }

    if (elegirRol) {
        AlertDialog(
            onDismissRequest = { elegirRol = false },
            title = { Text("¿Qué contacto?") },
            text = {
                Column {
                    ContactoVisita.ROLES.forEach { rol ->
                        TextButton(
                            onClick = { elegirRol = false; vm.anadirContacto(rol) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text(rol, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { elegirRol = false }) { Text("Cancelar") } },
        )
    }

    aBorrar?.let { k ->
        AlertDialog(
            onDismissRequest = { aBorrar = null },
            title = { Text("¿Borrar este contacto?") },
            text = { Text("${k.rol}: ${k.nombre.ifBlank { "sin nombre" }}") },
            confirmButton = {
                TextButton(onClick = { vm.borrarContacto(k.id); aBorrar = null }) {
                    Text("Borrar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { aBorrar = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Campo(
    etiqueta: String, valor: String?, editable: Boolean, lineas: Int = 1,
    teclado: KeyboardType = KeyboardType.Text, onCambio: (String) -> Unit,
) {
    OutlinedTextField(
        value = valor.orEmpty(),
        onValueChange = onCambio,
        label = { Text(etiqueta) },
        enabled = editable,
        singleLine = lineas == 1,
        minLines = lineas,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            capitalization = if (teclado == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = teclado,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// --- Fotos -------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PestanaFotos(
    fotos: List<FotoPendiente>,
    editable: Boolean,
    camara: () -> Unit,
    galeria: () -> Unit,
    abrir: (FotoPendiente) -> Unit,
    borrar: (FotoPendiente) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (editable) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = camara, modifier = Modifier.weight(1f).height(72.dp)) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cámara", style = MaterialTheme.typography.titleMedium)
                    }
                    FilledTonalButton(onClick = galeria, modifier = Modifier.weight(1f).height(72.dp)) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Galería", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (!editable && fotos.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { ResumenDrive(fotos) }
        }
        if (fotos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Aún no hay fotos. Se guardan en la galería, en Pictures/PeritajeApp/<mes>/<expediente>/Visita_<fecha>/.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            }
        }
        items(fotos, key = { it.id }) { f ->
            Column {
                AsyncImage(
                    model = f.uriMediastore,
                    contentDescription = f.nombreArchivo,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .combinedClickable(onClick = { abrir(f) }, onLongClick = { if (editable) borrar(f) }),
                )
                Text(f.nombreArchivo, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                if (!editable) EstadoDrive(f)
            }
        }
    }
}

/** Cuántas fotos de la visita están ya en Drive y, si algo falla, por qué. */
@Composable
private fun ResumenDrive(fotos: List<FotoPendiente>) {
    val subidas = fotos.count { it.estadoSubida == EstadoSubida.SUBIDA }
    val error = fotos.firstOrNull { it.estadoSubida != EstadoSubida.SUBIDA && it.errorSubida != null }?.errorSubida
    val texto = when {
        subidas == fotos.size -> "Todas las fotos están en Drive ($subidas)."
        error != null -> "En Drive: $subidas de ${fotos.size}. Último fallo: $error. Se reintenta solo; " +
            "las fotos siguen a salvo en el móvil."
        else -> "Subiendo a Drive: $subidas de ${fotos.size}…"
    }
    Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
}

@Composable
private fun EstadoDrive(f: FotoPendiente) {
    val (icono, texto, color) = when (f.estadoSubida) {
        EstadoSubida.SUBIDA -> Triple(Icons.Filled.CloudDone, "En Drive", MaterialTheme.colorScheme.primary)
        EstadoSubida.ERROR -> Triple(Icons.Filled.CloudOff, "No se puede subir", MaterialTheme.colorScheme.error)
        else -> Triple(Icons.Filled.CloudUpload, if (f.errorSubida != null) "Reintentando" else "Pendiente",
            MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(texto, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
