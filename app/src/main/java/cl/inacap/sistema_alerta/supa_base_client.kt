package cl.inacap.sistema_alerta

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.Serializable

object SupabaseClientInstance {
    val client = createSupabaseClient(
        supabaseUrl = "https://bsuxatfbsjjtubfifbfb.supabase.co",
        supabaseKey = "sb_publishable_qVROrRwSzECznHzG_Ub7Dw_tJKuGKpQ"
    ) {
        install(Postgrest)
    }
}

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    val password_hash: String,
    val role: String,
    val is_active: Boolean = true
)

