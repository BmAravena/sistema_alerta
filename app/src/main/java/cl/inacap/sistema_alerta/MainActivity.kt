package cl.inacap.sistema_alerta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cl.inacap.sistema_alerta.data.*
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt

@Serializable
data class UserInsertDto(
    val username: String,
    val password_hash: String,
    val role: String,
    val is_active: Boolean = true
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userId = intent.getIntExtra("USER_ID", -1)
        val username = intent.getStringExtra("USERNAME") ?: "Invitado"
        val role = intent.getStringExtra("ROLE") ?: "operador"

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(userId = userId, username = username, role = role)
                }
            }
        }
    }
}

fun formatearFechaHora(isoTimestamp: String): String {
    return try {
        val partes = isoTimestamp.split("T")
        val fecha = partes[0].split("-")
        val hora = partes[1].substring(0, 8)
        "${fecha[2]}/${fecha[1]}/${fecha[0]} | $hora"
    } catch (e: Exception) {
        isoTimestamp
    }
}

@Composable
fun DashboardScreen(userId: Int, username: String, role: String) {
    val esAdmin = role.equals("admin", ignoreCase = true)

    var alertaProximidadActiva by remember { mutableStateOf(false) }
    var dispositivoVinculado by remember { mutableStateOf(false) }
    val nombreDispositivo = "HC-05_Alarma_IoT"

    var distanciaUmbral by remember { mutableFloatStateOf(50f) }
    var estadoConexionCloud by remember { mutableStateOf("Sin verificar") }

    var mostrarDialogoUsuario by remember { mutableStateOf(false) }
    var mostrarDialogoEliminar by remember { mutableStateOf(false) }
    var listaUsuariosActivos by remember { mutableStateOf<List<UserDto>>(emptyList()) }

    var eventos by remember { mutableStateOf<List<SystemEventDetailDto>>(emptyList()) }
    var cargandoHistorial by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val permisoBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            Toast.makeText(context, "Permiso concedido, vuelve a pulsar Vincular", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permiso denegado: habilítalo en Ajustes", Toast.LENGTH_LONG).show()
        }
    }

    fun cargarHistorial() {
        coroutineScope.launch {
            cargandoHistorial = true
            try {
                eventos = SupabaseClientInstance.client
                    .from("system_events")
                    .select(
                        columns = Columns.raw("id, event_type, description, created_at, users(username, role)")
                    ) {
                        if (!esAdmin) {
                            filter {
                                isIn("event_type", listOf("ALERTA_PROXIMIDAD_ON", "ALERTA_INTRUSION"))
                            }
                        }
                        order(column = "created_at", order = Order.DESCENDING)
                    }
                    .decodeList<SystemEventDetailDto>()
            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar historial: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                cargandoHistorial = false
            }
        }
    }

    fun registrarEvento(uid: Int?, tipo: String, descripcion: String) {
        coroutineScope.launch {
            try {
                SupabaseClientInstance.client.from("system_events").insert(
                    SystemEventInsertDto(
                        user_id = uid,
                        event_type = tipo,
                        description = descripcion
                    )
                )
                cargarHistorial()
            } catch (e: Exception) {
                Toast.makeText(context, "Error al registrar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        cargarHistorial()
    }

    // Modal: Crear nuevo usuario
    if (mostrarDialogoUsuario && esAdmin) {
        CrearUsuarioDialog(
            onDismiss = { mostrarDialogoUsuario = false },
            onConfirm = { nuevoUser, nuevaPass, nuevoRol ->
                coroutineScope.launch {
                    try {
                        val hash = BCrypt.hashpw(nuevaPass, BCrypt.gensalt(10))
                        SupabaseClientInstance.client.from("users").insert(
                            UserInsertDto(
                                username = nuevoUser.trim(),
                                password_hash = hash,
                                role = nuevoRol.lowercase(),
                                is_active = true
                            )
                        )
                        registrarEvento(
                            uid = if (userId != -1) userId else null,
                            tipo = "USUARIO_CREADO",
                            descripcion = "Admin ($username) creó usuario '$nuevoUser' con rol ${nuevoRol.uppercase()}"
                        )
                        Toast.makeText(context, "Usuario '$nuevoUser' creado con éxito", Toast.LENGTH_SHORT).show()
                        mostrarDialogoUsuario = false
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al crear usuario: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // Modal: Desactivar usuario (Borrado Lógico)
    if (mostrarDialogoEliminar && esAdmin) {
        EliminarUsuarioDialog(
            usuarios = listaUsuariosActivos,
            onDismiss = { mostrarDialogoEliminar = false },
            onConfirmEliminar = { usuarioObjetivo ->
                coroutineScope.launch {
                    try {
                        SupabaseClientInstance.client
                            .from("users")
                            .update({ set("is_active", false) }) {
                                filter { eq("id", usuarioObjetivo.id) }
                            }

                        registrarEvento(
                            uid = if (userId != -1) userId else null,
                            tipo = "USUARIO_DESACTIVADO",
                            descripcion = "Admin ($username) dio de baja lógica al usuario '${usuarioObjetivo.username}'"
                        )
                        Toast.makeText(context, "Usuario '${usuarioObjetivo.username}' desactivado", Toast.LENGTH_SHORT).show()
                        mostrarDialogoEliminar = false
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al desactivar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Cabecera
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Panel de Control Cloud",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Usuario: $username | Rol: ${role.uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (esAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(onClick = {
                    val intent = Intent(context, LoginActivity::class.java)
                    context.startActivity(intent)
                    (context as? ComponentActivity)?.finish()
                }) {
                    Text("Salir")
                }
            }
        }

        // 2. Control Operativo Base (Disponible para Usuario Común y Administrador)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Control Operativo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Acción 1: Vincular Dispositivos
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dispositivo: $nombreDispositivo",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (dispositivoVinculado) "Estado: VINCULADO" else "Estado: DESCONECTADO",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dispositivoVinculado) Color(0xFF2E7D32) else Color.Gray
                            )
                        }

                        Button(
                            onClick = {
                                if (!dispositivoVinculado) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.BLUETOOTH_CONNECT
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        permisoBluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                        return@Button
                                    }

                                    coroutineScope.launch {
                                        val resultado = withContext(Dispatchers.IO) {
                                            validarYConectarBluetooth(
                                                context = context,
                                                nombreEsperado = nombreDispositivo,
                                                intentarSocketReal = false
                                            )
                                        }

                                        when (resultado) {
                                            is BluetoothCheckResult.Vinculado -> {
                                                dispositivoVinculado = true
                                                registrarEvento(
                                                    uid = if (userId != -1) userId else null,
                                                    tipo = "DISPOSITIVO_VINCULADO",
                                                    descripcion = "${resultado.detalle} por $username"
                                                )
                                                Toast.makeText(context, resultado.detalle, Toast.LENGTH_SHORT).show()
                                            }
                                            is BluetoothCheckResult.Error -> {
                                                dispositivoVinculado = false
                                                Toast.makeText(context, resultado.mensaje, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                } else {
                                    dispositivoVinculado = false
                                    registrarEvento(
                                        uid = if (userId != -1) userId else null,
                                        tipo = "DISPOSITIVO_DESVINCULADO",
                                        descripcion = "Dispositivo $nombreDispositivo desvinculado por $username"
                                    )
                                    Toast.makeText(context, "Dispositivo desvinculado", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(if (dispositivoVinculado) "Desvincular" else "Vincular Dispositivo")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Acción 2: Activar y Apagar Alerta de Proximidad
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Alerta de Proximidad",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (alertaProximidadActiva) "ACTIVADA" else "APAGADA",
                                fontWeight = FontWeight.Bold,
                                color = if (alertaProximidadActiva) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                        }
                        Switch(
                            checked = alertaProximidadActiva,
                            onCheckedChange = { nuevoEstado ->
                                alertaProximidadActiva = nuevoEstado
                                val tipo = if (nuevoEstado) "ALERTA_PROXIMIDAD_ON" else "ALERTA_PROXIMIDAD_OFF"
                                val desc = if (nuevoEstado) {
                                    "Activación manual | Distancia configurada: ${distanciaUmbral.toInt()} cm"
                                } else {
                                    "Alerta de proximidad apagada por $username"
                                }
                                registrarEvento(if (userId != -1) userId else null, tipo, desc)
                            }
                        )
                    }
                }
            }
        }

        // 3. Panel Exclusivo de Administrador (RBAC - ISO 27400)
        if (esAdmin) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Administración Avanzada (Solo Admin)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 1. Verificar estado de conexión
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Conexión Cloud / IoT",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Estado: $estadoConexionCloud",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (estadoConexionCloud.startsWith("EN LÍNEA")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val inicio = System.currentTimeMillis()
                                        try {
                                            SupabaseClientInstance.client
                                                .from("users")
                                                .select(columns = Columns.raw("id")) { limit(1) }
                                            val latencia = System.currentTimeMillis() - inicio
                                            estadoConexionCloud = "EN LÍNEA (${latencia} ms)"
                                            registrarEvento(
                                                uid = if (userId != -1) userId else null,
                                                tipo = "VERIFICACION_CONEXION",
                                                descripcion = "Conexión verificada por $username (${latencia} ms)"
                                            )
                                        } catch (e: Exception) {
                                            estadoConexionCloud = "SIN CONEXIÓN"
                                            Toast.makeText(context, "Fallo de conexión: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ) {
                                Text("Verificar Conexión")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // 2. Configurar el umbral de distancia
                        Text(
                            text = "Umbral de distancia sensor: ${distanciaUmbral.toInt()} cm",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = distanciaUmbral,
                            onValueChange = { distanciaUmbral = it },
                            valueRange = 10f..200f,
                            steps = 18
                        )
                        Button(
                            onClick = {
                                registrarEvento(
                                    uid = if (userId != -1) userId else null,
                                    tipo = "CONFIG_DISTANCIA",
                                    descripcion = "Umbral ajustado a ${distanciaUmbral.toInt()} cm por $username"
                                )
                                Toast.makeText(context, "Umbral guardado: ${distanciaUmbral.toInt()} cm", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Guardar Umbral de Distancia")
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // 3 y 4. Crear usuario y Eliminar usuario (Borrado lógico)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { mostrarDialogoUsuario = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Crear Usuario")
                            }

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            val todos = SupabaseClientInstance.client
                                                .from("users")
                                                .select {
                                                    filter { eq("is_active", true) }
                                                }
                                                .decodeList<UserDto>()
                                            listaUsuariosActivos = todos.filter { it.id != userId }
                                            mostrarDialogoEliminar = true
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error al cargar usuarios: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Eliminar Usuario")
                            }
                        }
                    }
                }
            }
        }

        // 4. Encabezado del Historial (Diferenciado por rol)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (esAdmin) "Auditoría Completa del Sistema" else "Historial de Activación de Alarma",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { cargarHistorial() }) {
                    Text("Actualizar")
                }
            }
            if (cargandoHistorial) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // 5. Tarjetas de Eventos
        items(eventos) { evento ->
            val esAlerta = evento.event_type == "ALERTA_INTRUSION" || evento.event_type == "ALERTA_PROXIMIDAD_ON"
            val tituloEvento = if (esAdmin) {
                evento.event_type
            } else {
                if (evento.event_type == "ALERTA_INTRUSION") "ALARMA AUTOMÁTICA (SENSOR)" else "ALARMA ACTIVADA MANUALMENTE"
            }
            val autor = evento.users?.let { "${it.username} (${it.role.uppercase()})" } ?: "Sensor / Sistema Automático"

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (esAlerta) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = tituloEvento,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (esAlerta) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fecha y Hora: ${formatearFechaHora(evento.created_at)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = evento.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Origen: $autor",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
fun CrearUsuarioDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var nuevoUsername by remember { mutableStateOf("") }
    var nuevaPassword by remember { mutableStateOf("") }
    var rolSeleccionado by remember { mutableStateOf("operador") }
    var verificando by remember { mutableStateOf(false) }

    val validacionPass = remember(nuevaPassword) {
        validarPasswordSegura(nuevaPassword)
    }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar Nuevo Usuario (BCrypt)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nuevoUsername,
                    onValueChange = { nuevoUsername = it },
                    label = { Text("Nombre de usuario") },
                    singleLine = true,
                    enabled = !verificando
                )
                OutlinedTextField(
                    value = nuevaPassword,
                    onValueChange = { nuevaPassword = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    enabled = !verificando,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (nuevaPassword.isNotEmpty() && !validacionPass.isValid) {
                    Text(
                        text = "Falta: ${validacionPass.missingRequirements.joinToString(", ")}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text("Rol asignado:", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = rolSeleccionado == "operador",
                        onClick = { rolSeleccionado = "operador" },
                        enabled = !verificando
                    )
                    Text("Operador")
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(
                        selected = rolSeleccionado == "admin",
                        onClick = { rolSeleccionado = "admin" },
                        enabled = !verificando
                    )
                    Text("Admin")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val userLimpio = nuevoUsername.trim()
                    if (userLimpio.isNotEmpty() && validacionPass.isValid && !verificando) {
                        coroutineScope.launch {
                            verificando = true
                            try {
                                val existe = validarExistencia(table = "users", username = userLimpio)
                                if (existe) {
                                    Toast.makeText(
                                        context,
                                        "El usuario '$userLimpio' ya existe",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    onConfirm(userLimpio, nuevaPassword, rolSeleccionado)
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Error al verificar usuario: ${e.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                verificando = false
                            }
                        }
                    }
                },
                enabled = nuevoUsername.isNotBlank() && validacionPass.isValid && !verificando
            ) {
                Text(if (verificando) "Verificando..." else "Crear")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !verificando
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun EliminarUsuarioDialog(
    usuarios: List<UserDto>,
    onDismiss: () -> Unit,
    onConfirmEliminar: (UserDto) -> Unit
) {
    var seleccionado by remember(usuarios) { mutableStateOf<UserDto?>(usuarios.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Desactivar Usuario (Borrado Lógico)") },
        text = {
            if (usuarios.isEmpty()) {
                Text("No hay otros usuarios activos disponibles para desactivar.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Selecciona el usuario que perderá acceso al sistema (se conservará su historial de auditoría):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    usuarios.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = seleccionado?.id == user.id,
                                onClick = { seleccionado = user }
                            )
                            Text("${user.username} (${user.role.uppercase()})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (usuarios.isNotEmpty()) {
                Button(
                    onClick = { seleccionado?.let { onConfirmEliminar(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Desactivar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}