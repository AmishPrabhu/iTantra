package com.itantra.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.itantra.transport.protocol.PacketProtocol
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bluetooth RFCOMM Serial Port Profile (SPP) Mesh Manager.
 * Operates standard low-power 2.4GHz RFCOMM sockets for peer discovery,
 * bidirectional voice/text framing, and background mesh communications.
 */
class BluetoothMeshManager(
    private val context: Context,
    private val onPacketReceived: (ByteArray) -> Unit
) {

    private val TAG = "BluetoothMeshManager"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val SERVICE_NAME = "iTantraMeshRFCOMM"

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val activeSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: BluetoothServerSocket? = null
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun getDiscoveredDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (isRunning || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }

        isRunning = true
        scope.launch {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                Log.i(TAG, "Bluetooth RFCOMM SPP Server listening on UUID: $SPP_UUID")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.i(TAG, "New incoming Bluetooth connection accepted: ${socket.remoteDevice.address}")
                    activeSockets.add(socket)
                    launchSocketReader(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket exception: ${e.message}")
                }
            }
        }

        // Periodically auto-connect to bonded devices
        scope.launch {
            while (isRunning) {
                try {
                    val bonded = getDiscoveredDevices()
                    for (dev in bonded) {
                        if (activeSockets.none { it.remoteDevice.address == dev.address }) {
                            connectToDevice(dev)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Auto-connect iteration: ${e.message}")
                }
                delay(4000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (activeSockets.any { it.remoteDevice.address == device.address && it.isConnected }) {
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "Attempting Bluetooth RFCOMM connection to ${device.name} (${device.address})...")
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()
                socket.connect()

                Log.i(TAG, "Connected successfully to ${device.address}")
                activeSockets.add(socket)
                launchSocketReader(socket)
            } catch (e: Exception) {
                Log.d(TAG, "RFCOMM connect attempt to ${device.address}: ${e.message}")
            }
        }
    }

    private fun launchSocketReader(socket: BluetoothSocket) {
        scope.launch {
            val inputStream: InputStream = socket.inputStream
            val buffer = ByteArray(2048)

            try {
                while (isRunning && socket.isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val packetBytes = buffer.copyOf(bytesRead)
                        Log.d(TAG, "Received $bytesRead bytes over Bluetooth SPP")
                        onPacketReceived(packetBytes)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth socket connection lost: ${e.message}")
            } finally {
                activeSockets.remove(socket)
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Broadcasts binary packet to all active Bluetooth mesh peers.
     */
    fun broadcastPacket(packetBytes: ByteArray): Boolean {
        if (activeSockets.isEmpty()) {
            Log.w(TAG, "No active Bluetooth peers to send packet")
            return false
        }

        scope.launch {
            for (socket in activeSockets) {
                try {
                    val out: OutputStream = socket.outputStream
                    out.write(packetBytes)
                    out.flush()
                    Log.d(TAG, "Dispatched ${packetBytes.size} bytes to ${socket.remoteDevice.address}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sending packet to socket: ${e.message}")
                }
            }
        }
        return true
    }

    fun getConnectedPeerCount(): Int = activeSockets.size

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (ignored: Exception) {}
        for (socket in activeSockets) {
            try { socket.close() } catch (ignored: Exception) {}
        }
        activeSockets.clear()
        scope.cancel()
    }
}
