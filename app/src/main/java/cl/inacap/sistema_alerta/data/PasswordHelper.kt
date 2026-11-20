package cl.inacap.sistema_alerta.data

import cl.inacap.sistema_alerta.SupabaseClientInstance
import io.github.jan.supabase.postgrest.query.Columns

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.Serializable

data class PasswordValidationResult(
    val isValid: Boolean,
    val missingRequirements: List<String>
)

fun validarPasswordSegura(password: String): PasswordValidationResult {
    val faltantes = mutableListOf<String>()

    if (password.length < 8) {
        faltantes.add("mínimo 8 caracteres")
    }
    if (!password.any { it.isUpperCase() }) {
        faltantes.add("al menos una mayúscula")
    }
    if (!password.any { !it.isLetterOrDigit() }) {
        faltantes.add("al menos un carácter especial (ej. !@#\$%)")
    }

    return PasswordValidationResult(
        isValid = faltantes.isEmpty(),
        missingRequirements = faltantes
    )
}



@Serializable
private data class UserIdDto(val id: Int)
suspend fun validarExistencia(table: String, username: String): Boolean {
    val encontrados = SupabaseClientInstance.client
        .from(table)
        .select(columns = Columns.raw("id")) {
            filter { eq("username", username.trim()) }
            limit(1)
        }
        .decodeList<UserIdDto>()
    return encontrados.isNotEmpty()
}

