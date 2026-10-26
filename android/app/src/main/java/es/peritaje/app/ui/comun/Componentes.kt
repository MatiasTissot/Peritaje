package es.peritaje.app.ui.comun

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import es.peritaje.app.data.db.Cita
import es.peritaje.app.data.db.EstadoCita
import es.peritaje.app.ui.tema.Ambar
import es.peritaje.app.ui.tema.Azul
import es.peritaje.app.ui.tema.Gris
import es.peritaje.app.ui.tema.Rojo
import es.peritaje.app.ui.tema.Verde

/** Situación visible de una cita: combina el estado con si ya hay algo capturado o hay error. */
enum class Situacion(val texto: String, val color: Color, val icono: ImageVector?) {
    SIN_EMPEZAR("Por visitar", Gris, null),
    EN_CURSO("En curso", Azul, Icons.Filled.ChevronRight),
    EN_COLA("Enviando", Ambar, Icons.Filled.Upload),
    ERROR("Error al enviar", Rojo, Icons.Filled.PriorityHigh),
    ENVIADA("Enviada", Verde, Icons.Filled.Check),
}

fun situacion(cita: Cita, numFotos: Int): Situacion = when (cita.estado) {
    EstadoCita.ENVIADA -> Situacion.ENVIADA
    EstadoCita.VISITADA -> if (cita.ultimoError != null) Situacion.ERROR else Situacion.EN_COLA
    EstadoCita.PENDIENTE -> if (cita.checklistVacio() && numFotos == 0) Situacion.SIN_EMPEZAR else Situacion.EN_CURSO
}

/** Círculo con símbolo y color propio para cada estado, visible de un vistazo. */
@Composable
fun IconoEstado(s: Situacion, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .border(3.dp, s.color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        s.icono?.let { Icon(it, contentDescription = s.texto, tint = s.color, modifier = Modifier.size(24.dp)) }
    }
}

enum class Nube(val texto: String, val icono: ImageVector, val color: Color) {
    OK("Sincronizado", Icons.Outlined.CloudDone, Verde),
    SINCRONIZANDO("Sincronizando…", Icons.Outlined.CloudSync, Azul),
    PENDIENTE("Hay envíos en cola", Icons.Outlined.CloudUpload, Ambar),
    SIN_CONEXION("Sin conexión", Icons.Outlined.CloudOff, Gris),
    ERROR("Error de sincronización", Icons.Outlined.SyncProblem, Rojo),
    SIN_CONFIGURAR("Falta configurar el PC", Icons.Outlined.SettingsSuggest, Rojo),
}

@Composable
fun BotonNube(nube: Nube, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(nube.icono, contentDescription = nube.texto, tint = nube.color, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun colorTextoSecundario() = MaterialTheme.colorScheme.onSurfaceVariant
