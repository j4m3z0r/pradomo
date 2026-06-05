package id.au.james.lymow.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import id.au.james.lymow.protocol.LymowProtocol
import id.au.james.lymow.transport.DiscoveredDevice
import id.au.james.lymow.transport.MowerScanner
import id.au.james.lymow.transport.MowerTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

private val SERVICE = UUID.fromString(LymowProtocol.SERVICE_UUID)
private val CONTROL = UUID.fromString(LymowProtocol.CONTROL_CHAR_UUID)
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission") // callers gate on BLUETOOTH_SCAN/CONNECT before use
class AndroidMowerScanner(context: Context) : MowerScanner {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun scan(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (!name.startsWith(LymowProtocol.DEVICE_NAME_PREFIX)) return
                found[result.device.address] =
                    DiscoveredDevice(result.device.address, name, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }
        }
        val scanner = adapter.bluetoothLeScanner ?: run { close(); return@callbackFlow }
        scanner.startScan(cb)
        awaitClose { scanner.stopScan(cb) }
    }
}

@SuppressLint("MissingPermission")
class AndroidMowerTransport(
    private val context: Context,
    private val deviceId: String,
) : MowerTransport {
    private val adapter: BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var onValue: ((ByteArray) -> Unit)? = null
    private var onDisconnectCb: (() -> Unit)? = null
    private var connectCont: ((Result<Unit>) -> Unit)? = null
    @Volatile private var closing = false

    override fun onDisconnect(callback: () -> Unit) { onDisconnectCb = callback }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val cont = connectCont
                    if (cont != null) {
                        connectCont = null
                        cont(Result.failure(IllegalStateException("disconnected during connect")))
                    } else if (!closing) {
                        onDisconnectCb?.invoke()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            control = g.getService(SERVICE)?.getCharacteristic(CONTROL)
            val cont = connectCont
            connectCont = null
            if (control != null) cont?.invoke(Result.success(Unit))
            else cont?.invoke(Result.failure(IllegalStateException("control characteristic not found")))
        }

        @Deprecated("Deprecated in API 33; replaced by the (gatt, characteristic, value) overload")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (ch.uuid == CONTROL) onValue?.invoke(ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) { if (ch.uuid == CONTROL) onValue?.invoke(value) }
    }

    override suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
        closing = false
        connectCont = { result -> result.fold({ cont.resume(Unit) }, { cont.resumeWith(Result.failure(it)) }) }
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceId)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override suspend fun startNotify(onValue: (ByteArray) -> Unit) {
        this.onValue = onValue
        val g = gatt ?: error("not connected")
        val ch = control ?: error("no control characteristic")
        g.setCharacteristicNotification(ch, true)
        ch.getDescriptor(CCCD)?.let { d ->
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") run {
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
        }
    }

    override suspend fun stopNotify() {
        val g = gatt ?: return
        val ch = control ?: return
        g.setCharacteristicNotification(ch, false)
    }

    override suspend fun write(value: ByteArray) {
        val g = gatt ?: error("not connected")
        val ch = control ?: error("no control characteristic")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") run { ch.value = value; g.writeCharacteristic(ch) }
        }
    }

    override fun close() {
        closing = true
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; control = null; onValue = null
    }
}
