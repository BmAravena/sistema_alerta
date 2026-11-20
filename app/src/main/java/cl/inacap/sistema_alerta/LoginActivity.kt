package cl.inacap.sistema_alerta

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cl.inacap.sistema_alerta.data.*
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt

private const val MAX_INTENTOS_FALLIDOS = 3
private const val SEGUNDOS_BLOQUEO = 30

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LoginScreen()
                }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    var intentosFallidos by remember { mutableIntStateOf(0) }
    var segundosRestantesBloqueo by remember { mutableIntStateOf(0) }
    var mostrarDialogoRecuperar by remember { mutableStateOf(false) }

    val bloqueado = segundosRestantesBloqueo > 0
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Cuenta regresiva cuando se alcanzan los 3 intentos fallidos
    LaunchedEffect(segundosRestantesBloqueo) {
        if (segundosRestantesBloqueo > 0) {
            delay(1000L)
            segundosRestantesBloqueo -= 1
            if (segundosRestantesBloqueo == 0) {
                intentosFallidos = 0
            }
        }
    }

    fun registrarFalloLogin(usuarioIngresado: String, motivo: String) {
        intentosFallidos += 1
        val restantes = MAX_INTENTOS_FALLIDOS - intentosFallidos

        coroutineScope.launch {
            try {
                SupabaseClientInstance.client.from("system_events").insert(
                    SystemEventInsertDto(
                        user_id = null,
                        event_type = if (intentosFallidos >= MAX_INTENTOS_FALLIDOS) "LOGIN_BLOQUEADO" else "LOGIN_FALLIDO",
                        description = "Intento fallido ($intentosFallidos/$MAX_INTENTOS_FALLIDOS) para '${usuarioIngresado.trim()}': $motivo"
                    )
                )
            } catch (_: Exception) {
                // Evita interrumpir el flujo de UI si falla el log
            }
        }

        if (intentosFallidos >= MAX_INTENTOS_FALLIDOS) {
            segundosRestantesBloqueo = SEGUNDOS_BLOQUEO
            Toast.makeText(
                context,
                "Máximo de $MAX_INTENTOS_FALLIDOS intentos alcanzado. Espera $SEGUNDOS_BLOQUEO s.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                context,
                "$motivo. Te quedan $restantes ${if (restantes == 1) "intento" else "intentos"}.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (mostrarDialogoRecuperar) {
        RecuperarPasswordDialog(
            usuarioInicial = username,
            onDismiss = { mostrarDialogoRecuperar = false },
            onConfirmarCambio = { targetUser, nuevaPass ->
                coroutineScope.launch {
                    try {
                        val usuarios = SupabaseClientInstance.client
                            .from("users")
                            .select {
                                filter { eq("username", targetUser.trim()) }
                            }
                            .decodeList<UserDto>()

                        val usuarioObjetivo = usuarios.firstOrNull()
                        if (usuarioObjetivo == null) {
                            Toast.makeText(context, "El usuario no existe", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        if (!usuarioObjetivo.is_active) {
                            Toast.makeText(context, "Usuario desactivado: contacta al administrador", Toast.LENGTH_LONG).show()
                            return@launch
                        }

                        val nuevoHash = BCrypt.hashpw(nuevaPass, BCrypt.gensalt(10))
                        SupabaseClientInstance.client
                            .from("users")
                            .update({ set("password_hash", nuevoHash) }) {
                                filter { eq("id", usuarioObjetivo.id) }
                            }

                        SupabaseClientInstance.client.from("system_events").insert(
                            SystemEventInsertDto(
                                user_id = usuarioObjetivo.id,
                                event_type = "PASSWORD_RECUPERADA",
                                description = "Contraseña restablecida con BCrypt para '${usuarioObjetivo.username}'"
                            )
                        )

                        // Reinicia el contador de fallos tras recuperar la clave
                        intentosFallidos = 0
                        segundosRestantesBloqueo = 0
                        username = usuarioObjetivo.username
                        password = ""
                        mostrarDialogoRecuperar = false
                        Toast.makeText(context, "Contraseña actualizada. Ya puedes iniciar sesión.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al recuperar contraseña: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Sistema de Seguridad IoT",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Autenticación Segura (ISO 27400)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            singleLine = true,
            enabled = !bloqueado && !isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            singleLine = true,
            enabled = !bloqueado && !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (bloqueado) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Acceso bloqueado temporalmente por intentos fallidos (${segundosRestantesBloqueo}s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        } else if (intentosFallidos > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Intentos fallidos: $intentosFallidos de $MAX_INTENTOS_FALLIDOS",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC62828),
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (bloqueado) return@Button

                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(context, "Complete todos los campos", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true
                coroutineScope.launch {
                    try {
                        val usuarios = SupabaseClientInstance.client
                            .from("users")
                            .select {
                                filter { eq("username", username.trim()) }
                            }
                            .decodeList<UserDto>()

                        val usuarioEncontrado = usuarios.firstOrNull()

                        if (usuarioEncontrado == null) {
                            registrarFalloLogin(username, "Credenciales inválidas")
                        } else if (!usuarioEncontrado.is_active) {
                            Toast.makeText(context, "Acceso denegado: Usuario desactivado", Toast.LENGTH_LONG).show()
                        } else if (BCrypt.checkpw(password, usuarioEncontrado.password_hash)) {
                            intentosFallidos = 0
                            segundosRestantesBloqueo = 0

                            SupabaseClientInstance.client.from("system_events").insert(
                                SystemEventInsertDto(
                                    user_id = usuarioEncontrado.id,
                                    event_type = "LOGIN_EXITOSO",
                                    description = "Acceso verificado con BCrypt (${usuarioEncontrado.role.uppercase()})"
                                )
                            )

                            Toast.makeText(context, "Bienvenido ${usuarioEncontrado.username}", Toast.LENGTH_SHORT).show()

                            val intent = Intent(context, MainActivity::class.java).apply {
                                putExtra("USER_ID", usuarioEncontrado.id)
                                putExtra("USERNAME", usuarioEncontrado.username)
                                putExtra("ROLE", usuarioEncontrado.role)
                            }
                            context.startActivity(intent)
                            (context as? ComponentActivity)?.finish()
                        } else {
                            registrarFalloLogin(username, "Credenciales inválidas")
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading && !bloqueado,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(if (bloqueado) "Bloqueado (${segundosRestantesBloqueo}s)" else "Iniciar Sesión")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { mostrarDialogoRecuperar = true },
            enabled = !isLoading
        ) {
            Text("¿Olvidaste tu contraseña? Recuperar")
        }
    }
}

@Composable
fun RecuperarPasswordDialog(
    usuarioInicial: String,
    onDismiss: () -> Unit,
    onConfirmarCambio: (String, String) -> Unit
) {
    var usuarioInput by remember { mutableStateOf(usuarioInicial) }
    var nuevaPassword by remember { mutableStateOf("") }
    var confirmarPassword by remember { mutableStateOf("") }
    var errorLocal by remember { mutableStateOf<String?>(null) }

    val validacionPass = remember(nuevaPassword) {
        validarPasswordSegura(nuevaPassword)
    }

    val formularioValido = usuarioInput.isNotBlank() &&
            validacionPass.isValid &&
            confirmarPassword.isNotEmpty() &&
            nuevaPassword == confirmarPassword

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recuperar Contraseña") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Ingresa tu usuario y define una nueva contraseña (se almacenará cifrada con BCrypt):",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = usuarioInput,
                    onValueChange = {
                        usuarioInput = it
                        errorLocal = null
                    },
                    label = { Text("Usuario") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = nuevaPassword,
                    onValueChange = {
                        nuevaPassword = it
                        errorLocal = null
                    },
                    label = { Text("Nueva contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (nuevaPassword.isNotEmpty() && !validacionPass.isValid) {
                    Text(
                        text = "Falta: ${validacionPass.missingRequirements.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedTextField(
                    value = confirmarPassword,
                    onValueChange = {
                        confirmarPassword = it
                        errorLocal = null
                    },
                    label = { Text("Confirmar contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (confirmarPassword.isNotEmpty() && nuevaPassword != confirmarPassword) {
                    Text(
                        text = "Las contraseñas no coinciden",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                errorLocal?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        usuarioInput.isBlank() -> errorLocal = "Ingresa el nombre de usuario"
                        !validacionPass.isValid -> errorLocal = "Falta: ${validacionPass.missingRequirements.joinToString(", ")}"
                        nuevaPassword != confirmarPassword -> errorLocal = "Las contraseñas no coinciden"
                        else -> onConfirmarCambio(usuarioInput, nuevaPassword)
                    }
                },
                enabled = formularioValido
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}