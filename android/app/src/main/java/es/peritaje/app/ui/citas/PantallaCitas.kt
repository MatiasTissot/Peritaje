@file:OptIn(ExperimentalMaterial3Api::class)

package es.peritaje.app.ui.citas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.peritaje.app.data.Repositorio
import es.peritaje.app.data.db.CitaResumen
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.ui.comun.BotonNube
import es.peritaje.app.ui.comun.IconoEstado
import es.peritaje.app.ui.comun.Nube
import es.peritaje.app.ui.comun.situacion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Filtro(val texto: String) { ACTIVAS("Activas"), HOY("Solo hoy"), ENVIADAS("Enviadas") }

private val HORA = DateTimeFormatter.ofPattern("HH:mm")
private val DIA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es", "ES"))

private fun fecha(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun etiquetaDia(d: LocalDate): String {
    val hoy = LocalDate.now()
    return when (d) {
        hoy -> "Hoy"
        hoy.plusDays(1) -> "Mañana"
        hoy.minusDays(1) -> "Ayer"
        else -> DIA.format(d).replaceFirstChar { it.uppercase() }
    }
}

@Composable
fun PantallaCitas(vm: CitasViewModel, abrirCita: (String) -> Unit, abrirAjustes: () -> Unit) {
    val citas by vm.citas.collectAsStateWithLifecycle()
    val sync by vm.estadoSync.collectAsStateWithLifecycle()
    var filtro by rememberSaveable { mutableStateOf(Filtro.ACTIVAS) }
    var verEstadoSync by rememberSaveable { mutableStateOf(false) }

    val visibles = citas.filter {
        when (filtro) {
            Filtro.ACTIVAS -> it.cita.estado != EstadoCita.ENVIADA
            Filtro.HOY -> it.cita.estado != EstadoCita.ENVIADA && fecha(it.cita.fechaHora) == LocalDate.now()
            Filtro.ENVIADAS -> it.cita.estado == EstadoCita.ENVIADA
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visitas") },
                actions = {
                    BotonNube(sync.nube) { verEstadoSync = true }
                    IconButton(onClick = abrirAjustes, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.sincronizar() },
                icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                text = { Text("Sincronizar") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { relleno ->
        PullToRefreshBox(
            isRefreshing = sync.sincronizando,
            onRefresh = { vm.sincronizar() },
            modifier = Modifier.padding(relleno).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Filtro.entries.forEach { f ->
                            FilterChip(
                                selected = filtro == f,
                                onClick = { filtro = f },
                                label = { Text(f.texto, style = MaterialTheme.typography.labelLarge) },
                                modifier = Modifier.height(44.dp),
                            )
                        }
                    }
                }
                if (sync.nube == Nube.SIN_CONFIGURAR) {
                    item { Aviso("Configura la dirección del PC y el token para traer las citas.", "Configurar", abrirAjustes) }
                } else if (sync.antigua) {
                    item {
                        val cuando = sync.ultima?.let { Repositorio.fechaHoraLegible(it) } ?: "nunca"
                        Aviso("Última sincronización: $cuando. Sincroniza antes de salir para tener las citas sin cobertura.", "Sincronizar") { vm.sincronizar() }
                    }
                }
                if (visibles.isEmpty()) {
                    item {
                        Text(
                            if (filtro == Filtro.ENVIADAS) "No hay visitas enviadas en los últimos 7 días."
                            else "No hay visitas. En el móvil se cargan las de hoy, las de mañana si sincronizas " +
                                "a partir de las 17:00, y las pendientes de cerrar.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                var diaAnterior: LocalDate? = null
                visibles.forEach { r ->
                    val dia = fecha(r.cita.fechaHora)
                    if (dia != diaAnterior) {
                        diaAnterior = dia
                        item(key = "dia-$dia") {
                            Text(
                                etiquetaDia(dia),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                    }
                    item(key = r.cita.idEncargo) { TarjetaCita(r) { abrirCita(r.cita.idEncargo) } }
                }
            }
        }
    }

    if (verEstadoSync) {
        AlertDialog(
            onDismissRequest = { verEstadoSync = false },
            title = { Text(sync.nube.texto) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Última sincronización: " + (sync.ultima?.let { Repositorio.fechaHoraLegible(it) } ?: "nunca"))
                    Text("Avances en cola: ${sync.pendientes}")
                    sync.error?.let { Text("Último error: $it", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.sincronizar(); verEstadoSync = false }) { Text("Sincronizar ahora") } },
            dismissButton = { TextButton(onClick = { verEstadoSync = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun Aviso(texto: String, accion: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(accion) }
        }
    }
}

@Composable
private fun TarjetaCita(r: CitaResumen, onClick: () -> Unit) {
    val c = r.cita
    val s = situacion(c, r.numFotos)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconoEstado(s)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        HORA.format(Instant.ofEpochMilli(c.fechaHora).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(c.idEncargo, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(c.direccion, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (c.asegurado.isNotBlank()) Text(c.asegurado, style = MaterialTheme.typography.bodyMedium)
                if (c.anulada) {
                    Text("Anulada en Notion · tiene datos sin enviar", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                } else if (c.avisoNotion != null) {
                    Text("Cambiada en Notion", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.texto, color = s.color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (r.numFotos > 0) {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Fotos", modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" ${r.numFotos}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
