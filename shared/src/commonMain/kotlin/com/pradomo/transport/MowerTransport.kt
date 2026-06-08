package com.pradomo.transport

import kotlinx.coroutines.flow.Flow

/** A Lymow device seen while scanning. */
data class DiscoveredDevice(val id: String, val name: String, val rssi: Int)

/** Platform BLE scanner. Android/iOS provide implementations. */
interface MowerScanner {
    /** Emits the current set of matching devices as they are (re)discovered. */
    fun scan(): Flow<List<DiscoveredDevice>>
}

/**
 * Dumb GATT transport for ONE device: connect, (un)subscribe to the control
 * characteristic, and write raw values. The controller owns all protocol logic.
 */
interface MowerTransport {
    suspend fun connect()
    /** Synchronously tear down the link and release native resources. Safe to call from any thread. */
    fun close()
    suspend fun startNotify(onValue: (ByteArray) -> Unit)
    suspend fun stopNotify()
    /** Write [value] to the control characteristic (Write-Without-Response). */
    suspend fun write(value: ByteArray)
    /** Register a callback fired if the link drops unexpectedly AFTER a successful connect. */
    fun onDisconnect(callback: () -> Unit)
}
