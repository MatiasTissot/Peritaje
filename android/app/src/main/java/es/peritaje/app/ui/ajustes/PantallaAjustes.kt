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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import es.peritaje.app.data.red.Modo
import es.peritaje.app.data.red.NotionError
import es.peritaje.app.trabajo.Planificador
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AjustesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as PeritajeApp).contenedor.repositorio

    var modo by mutableStateOf(Modo.NOTION)
    var tokenNotion by mutableStateOf("")
    var url by mutableStateOf("")
    var tokenPc by mutableStateOf("")
    var resultado by mutableStateOf<String?>(null)
        private set
    var probando by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            url = repo.configuracion.first()?.urlPc.orEmpty()
            val (m, tn, tp) = withContext(Dispatchers.IO) { Triple(repo.modo(), repo.tokenNotion(), repo.tokenPc()) }
            modo = m
            tokenNotion = tn
            tokenPc = tp
        }
    }

    val completo: Boolean
        get() = when (modo) {
            Modo.NOTION -> tokenNotion.isNotBlank()
            Modo.PC -> url.isNotBlank() && tokenPc.isNotBlank()
        }

    fun guardar() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.guardarConexion(modo, url, tokenPc, tokenNotion) }
            resultado = "Guardado. Sincronizando…"
            Planificador.sincronizarAhora(getApplication())
        }
    }

    fun probar() {
        viewModelScope.launch {
            probando = true
            resultado = try {
                repo.probarConexion(modo, url, tokenPc, tokenNotion)
            } catch (e: NotionError) {
                if (e.codigo == 401) "Notion rechaza el token. Revisa que lo has copiado entero." else e.message
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) "El PC rechaza el token" else "Error del PC (${e.code()})"
            } catch (e: Exception) {
                "No se puede conectar: ${e.message ?: e.javaClass.simpleName}"
            }
            probando = false
        }
    }
}

@Composable
fun PantallaAjustes(vm: AjustesViewModel, volver: () -> Unit) {
    var verToken by rememberSaveable { mutableStateOf(false) }
    val transformacion = if (verToken) VisualTransformation.None else PasswordVisualTransformation()
    val ojo: @Composable () -> Unit = {
        IconButton(onClick = { verToken = !verToken }) {
            Icon(if (verToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (verToken) "Ocultar" else "Mostrar")
        }
    }
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
            Text("Conexión", style = MaterialTheme.typography.titleMedium)
            val opciones = listOf(Modo.NOTION to "Notion directo", Modo.PC to "A través del PC")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                opciones.forEachIndexed { i, (m, texto) ->
                    SegmentedButton(
                        selected = vm.modo == m,
                        onClick = { vm.modo = m },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = opciones.size),
                        modifier = Modifier.height(56.dp),
                    ) { Text(texto) }
                }
            }

            when (vm.modo) {
                Modo.NOTION -> {
                    OutlinedTextField(
                        value = vm.tokenNotion,
                        onValueChange = { vm.tokenNotion = it },
                        label = { Text("Token de Notion") },
                        placeholder = { Text("ntn_…") },
                        singleLine = true,
                        visualTransformation = transformacion,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = ojo,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Es el token de una integración interna de Notion (notion.so/my-integrations) con acceso a " +
                            "\"Gestión de Encargos - Peritaciones\". Se guarda cifrado en el móvil y no sale de él salvo hacia Notion.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Modo.PC -> {
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
                        value = vm.tokenPc,
                        onValueChange = { vm.tokenPc = it },
                        label = { Text("Token del PC") },
                        singleLine = true,
                        visualTransformation = transformacion,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = ojo,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Para cuando el PC esté montado. La dirección es la IP de Tailscale del PC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(
                onClick = { vm.probar() },
                enabled = !vm.probando && vm.completo,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (vm.probando) "Probando…" else "Probar conexión") }
            Button(
                onClick = { vm.guardar() },
                enabled = vm.completo,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Guardar") }
            vm.resultado?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text("Versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
