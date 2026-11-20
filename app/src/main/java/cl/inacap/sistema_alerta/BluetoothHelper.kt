package cl.inacap.sistema_alerta

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

// UUID estándar Serial Port Profile (SPP) usado por módulos como HC-05
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

sealed class BluetoothCheckResult {
    data class Vinculado(val detalle: String) : BluetoothCheckResult()
    data class Error(val mensaje: String) : BluetoothCheckResult()
}

fun validarYConectarBluetooth(
    context: Context,
    nombreEsperado: String,
    intentarSocketReal: Boolean = false
): BluetoothCheckResult {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    if (adapter == null) {
        return BluetoothCheckResult.Error("Este dispositivo no soporta Bluetooth")
    }

    if (!adapter.isEnabled) {
        return BluetoothCheckResult.Error("Activa el Bluetooth del teléfono antes de vincular")
    }

    // En Android 12 (API 31) o superior es obligatorio verificar BLUETOOTH_CONNECT en tiempo de ejecución
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val tienePermiso = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        if (!tienePermiso) {
            return BluetoothCheckResult.Error("Falta el permiso de Dispositivos cercanos (Bluetooth)")
        }
    }

    val emparejados: Set<BluetoothDevice> = try {
        adapter.bondedDevices ?: emptySet()
    } catch (e: SecurityException) {
        return BluetoothCheckResult.Error("Permiso Bluetooth denegado por el sistema")
    }

    val dispositivoObjetivo = emparejados.firstOrNull {
        it.name.equals(nombreEsperado, ignoreCase = true) ||
                it.name?.contains("HC-05", ignoreCase = true) == true
    }

    if (dispositivoObjetivo == null) {
        // Bluetooth está encendido y con permisos OK, pero no hay HC-05 emparejado aún
        return BluetoothCheckResult.Error("Bluetooth activo, pero '$nombreEsperado' no está emparejado en Ajustes")
    }

    if (intentarSocketReal) {
        return try {
            // Cancelar discovery mejora la estabilidad al intentar conectar por RFCOMM
            adapter.cancelDiscovery()
            val socket = dispositivoObjetivo.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect() // Bloqueante: debe llamarse dentro de corrutina / Dispatchers.IO
            socket.close()
            BluetoothCheckResult.Vinculado("Conectado a ${dispositivoObjetivo.name} (${dispositivoObjetivo.address})")
        } catch (e: Exception) {
            BluetoothCheckResult.Error("Emparejado con ${dispositivoObjetivo.name}, pero sin respuesta RFCOMM (¿módulo apagado?)")
        }
    }

    return BluetoothCheckResult.Vinculado("Listo con ${dispositivoObjetivo.name} (${dispositivoObjetivo.address})")
}