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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cl.inacap.sistema_alerta.data.*
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt

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

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

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
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(context, "Complete todos los campos", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                isLoading = true
                coroutineScope.launch {
                    try {
                        // 1. Buscar el usuario por su nombre en Supabase
                        val usuarios = SupabaseClientInstance.client
                            .from("users")
                            .select {
                                filter { eq("username", username.trim()) }
                            }
                            .decodeList<UserDto>()

                        val usuarioEncontrado = usuarios.firstOrNull()

                        // 2. Verificar estado activo (borrado lógico) y validar contraseña cifrada con BCrypt
                        if (usuarioEncontrado == null) {
                            Toast.makeText(context, "Credenciales inválidas", Toast.LENGTH_SHORT).show()
                        } else if (!usuarioEncontrado.is_active) {
                            Toast.makeText(context, "Acceso denegado: Usuario desactivado", Toast.LENGTH_LONG).show()
                        } else if (BCrypt.checkpw(password, usuarioEncontrado.password_hash)) {

                            // 3. Registrar evento de auditoría en la nube
                            SupabaseClientInstance.client.from("system_events").insert(
                                SystemEventInsertDto(
                                    user_id = usuarioEncontrado.id,
                                    event_type = "LOGIN_EXITOSO",
                                    description = "Acceso verificado con BCrypt (${usuarioEncontrado.role.uppercase()})"
                                )
                            )

                            Toast.makeText(context, "Bienvenido ${usuarioEncontrado.username}", Toast.LENGTH_SHORT).show()

                            // 4. Navegar a MainActivity enviando ID, nombre y rol
                            val intent = Intent(context, MainActivity::class.java).apply {
                                putExtra("USER_ID", usuarioEncontrado.id)
                                putExtra("USERNAME", usuarioEncontrado.username)
                                putExtra("ROLE", usuarioEncontrado.role)
                            }
                            context.startActivity(intent)
                            (context as? ComponentActivity)?.finish()
                        } else {
                            Toast.makeText(context, "Credenciales inválidas", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading,
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
                Text("Iniciar Sesión")
            }
        }
    }
}