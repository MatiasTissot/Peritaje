@file:OptIn(ExperimentalMaterial3Api::class)

package es.peritaje.app.ui.ajustes

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.peritaje.app.BuildConfig
import es.peritaje.app.PeritajeApp
import es.peritaje.app.data.Repositorio
import es.peritaje.app.trabajo.Planificador
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AjustesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as PeritajeApp).contenedor.repositorio

    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var resultado by mutableStateOf<String?>(null)
        private set
    var probando by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            url = repo.configuracion.first()?.urlPc.orEmpty()
            token = withContext(Dispatchers.IO) { repo.tokenActual() }
        }
    }

    fun guardar() {
        viewModelScope.launch {
            url = Repositorio.normalizarUrl(url)
            token = token.trim()
            withContext(Dispatchers.IO) { repo.guardarConexion(url, token) }
            resultado = "Guardado. Sincronizando…"
            Planificador.sincronizarAhora(getApplication())
        }
    }

    fun probar() {
        viewModelScope.launch {
            probando = true
            resultado = try {
                val salud = repo.probarConexion(url, token)
                val version = salud.version?.let { " · servidor v$it" } ?: " · servidor sin versión (actualízalo)"
                if (salud.estado == "ok") "Conexión correcta con el PC$version" else "El PC respondió: ${salud.estado}$version"
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) "El PC rechaza el token" else "Error del PC (${e.code()})"
            } catch (e: Exception) {
                "No se puede conectar: ${e.message ?: e.javaClass.simpleName}. ¿Está Tailscale activo?"
            }
            probando = false
        }
    }
}

@Composable
fun PantallaAjustes(vm: AjustesViewModel, volver: () -> Unit) {
    var verToken by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = volver, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { relleno ->
        Column(
            Modifier.padding(relleno).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Conexión con el PC", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = vm.url,
                onValueChange = { vm.url = it },
                label = { Text("Dirección del PC") },
                placeholder = { Text("http://100.x.x.x:8000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.token,
                onValueChange = { vm.token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = if (verToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { verToken = !verToken }) {
                        Icon(if (verToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (verToken) "Ocultar" else "Mostrar")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "El token se guarda cifrado en el móvil. La dirección es la IP de Tailscale del PC.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { vm.probar() },
                enabled = !vm.probando && vm.url.isNotBlank() && vm.token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (vm.probando) "Probando…" else "Probar conexión") }
            Button(
                onClick = { vm.guardar() },
                enabled = vm.url.isNotBlank() && vm.token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Guardar") }
            vm.resultado?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text("App v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
