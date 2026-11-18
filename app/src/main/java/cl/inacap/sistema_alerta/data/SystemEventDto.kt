package cl.inacap.sistema_alerta.data

import kotlinx.serialization.Serializable

// Para insertar un evento nuevo en Supabase
@Serializable
data class SystemEventInsertDto(
    val user_id: Int?, // ID entero del usuario autenticado (o null si es evento automático)
    val event_type: String,
    val description: String
)

// Sub-modelo para leer el nombre y rol del usuario enlazado en el JOIN
@Serializable
data class UserSummaryDto(
    val username: String,
    val role: String
)

// Para leer el historial completo desde Supabase
@Serializable
data class SystemEventDetailDto(
    val id: Int,
    val event_type: String,
    val description: String,
    val created_at: String,
    val users: UserSummaryDto? = null
)