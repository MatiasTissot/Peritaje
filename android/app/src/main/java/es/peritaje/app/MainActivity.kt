package es.peritaje.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import es.peritaje.app.trabajo.Planificador
import es.peritaje.app.ui.ajustes.AjustesViewModel
import es.peritaje.app.ui.ajustes.PantallaAjustes
import es.peritaje.app.ui.citas.CitasViewModel
import es.peritaje.app.ui.citas.PantallaCitas
import es.peritaje.app.ui.detalle.DetalleViewModel
import es.peritaje.app.ui.detalle.PantallaDetalle
import es.peritaje.app.ui.tema.TemaPeritaje
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            // Primero se rescata cualquier foto que quedara a medias (p. ej. Android cerró la app con la cámara abierta).
            val contenedor = (application as PeritajeApp).contenedor
            contenedor.ambito.launch {
                runCatching { contenedor.fotos.recuperarCapturas() }
                Planificador.sincronizarAhora(this@MainActivity.applicationContext)
            }
        }
        setContent { TemaPeritaje { Navegacion() } }
    }
}

@Composable
private fun Navegacion() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as Application

    NavHost(navController = nav, startDestination = "citas") {
        composable("citas") {
            PantallaCitas(
                vm = viewModel<CitasViewModel>(factory = viewModelFactory { initializer { CitasViewModel(app) } }),
                abrirCita = { nav.navigate("cita/$it") },
                abrirAjustes = { nav.navigate("ajustes") },
            )
        }
        composable("cita/{id}") { entrada ->
            val id = entrada.arguments?.getString("id").orEmpty()
            PantallaDetalle(
                vm = viewModel<DetalleViewModel>(factory = viewModelFactory { initializer { DetalleViewModel(app, id) } }),
                volver = { nav.popBackStack() },
                // Saltar a la anterior/siguiente sin apilar pantallas.
                irA = { otro -> nav.navigate("cita/$otro") { popUpTo("citas") } },
            )
        }
        composable("ajustes") {
            PantallaAjustes(
                vm = viewModel<AjustesViewModel>(factory = viewModelFactory { initializer { AjustesViewModel(app) } }),
                volver = { nav.popBackStack() },
            )
        }
    }
}
