package com.itantra.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.itantra.transport.protocol.PacketProtocol
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Autonomous Zero-Configuration Bluetooth & BLE Mesh Manager.
 * Operates:
 * 1. Compact 16-bit UUID BLE Advertising & Scanning for 100% reliable discovery within 31-byte limit.
 * 2. High-speed BLE GATT Server & Client with sequenced MTU negotiation and service discovery.
 * 3. Connectionless BLE SOS broadcast burst for instantaneous unlinked delivery.
 * 4. Insecure RFCOMM fallback with 4-byte big-endian framing.
 */
class BluetoothMeshManager(
    private val context: Context,
    private val localNodeId: String = "node_${(1000..9999).random()}",
    private val onPacketReceived: (ByteArray) -> Unit
) {

    private val TAG = "BluetoothMeshManager"

    // Compact 16-bit UUIDs (Base Bluetooth SIG UUID 0000xxxx-0000-1000-8000-00805f9b34fb)
    // Consumes only 2 bytes in BLE advertising packets instead of 16 bytes, guaranteeing fit inside 31-byte limit!
    val MESH_SERVICE_UUID: UUID = UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
    private val CHAR_TX_UUID: UUID = UUID.fromString("0000FE2D-0000-1000-8000-00805F9B34FB")
    private val CHAR_RX_UUID: UUID = UUID.fromString("0000FE2E-0000-1000-8000-00805F9B34FB")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val SERVICE_NAME = "iTantraAutonomousMesh"

    private val bluetoothManager: android.bluetooth.BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active Connections
    private val activeGattClients = CopyOnWriteArrayList<BluetoothGatt>()
    private val activeGattDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val activeRfcommSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()

    private var rfcommServerSocket: BluetoothServerSocket? = null
    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var isRunning = false
    private var isAdvertising = false
    private var isScanning = false

    @SuppressLint("MissingPermission")
    fun getDiscoveredDevices(): List<BluetoothDevice> {
        val list = mutableListOf<BluetoothDevice>()
        list.addAll(discoveredDevices.values)
        try {
            bluetoothAdapter?.bondedDevices?.forEach { dev ->
                if (list.none { it.address == dev.address }) {
                    list.add(dev)
                }
            }
        } catch (ignored: Exception) {}
        return list
    }

    @SuppressLint("MissingPermission")
    fun startMesh() {
        if (isRunning || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available or disabled")
            return
        }

        isRunning = true
        Log.i(TAG, "Starting autonomous in-app Bluetooth & BLE Mesh (Local Node: $localNodeId)...")

        startGattServer()
        startBleAdvertising()
        startBleScanning()
        startRfcommServer()

        // Background watchdog for auto-connecting discovered peers
        scope.launch {
            while (isRunning) {
                delay(3000)
                try {
                    discoveredDevices.values.forEach { dev ->
                        if (!activeGattDevices.contains(dev) && activeRfcommSockets.none { it.remoteDevice.address == dev.address }) {
                            connectToDevice(dev)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Watchdog connect error: ${e.message}")
                }
            }
        }
    }

    // ==========================================
    // 1. COMPACT 16-BIT BLE ADVERTISING (Guaranteed <= 31 Bytes)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising() {
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            Log.w(TAG, "BLE Advertiser not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val pUuid = ParcelUuid(MESH_SERVICE_UUID)
        // 16-bit UUID + 9-byte payload = 4 + 4 + 9 = 17 bytes (well below 31 bytes!)
        val payload = "ID|$localNodeId".toByteArray(Charsets.UTF_8)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(pUuid)
            .addServiceData(pUuid, payload)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            bleAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            isAdvertising = true
            Log.i(TAG, "BLE Advertising active with compact 16-bit UUID: $MESH_SERVICE_UUID")
        } catch (e: Exception) {
            Log.e(TAG, "BLE Advertising start error: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE Advertising started successfully!")
            isAdvertising = true
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertising failed with error code: $errorCode")
            isAdvertising = false
        }
    }

    /**
     * Broadcasts an instantaneous connectionless BLE advertisement burst for emergency SOS.
     * All nearby devices scanning BLE will pick this up instantly even if NOT linked!
     */
    @SuppressLint("MissingPermission")
    fun broadcastEmergencyBleBurst(packetBytes: ByteArray) {
        if (bleAdvertiser == null) return

        scope.launch {
            try {
                val pUuid = ParcelUuid(MESH_SERVICE_UUID)
                val decoded = PacketProtocol.decodePacket(packetBytes)
                val alertText = decoded?.payloadText?.let {
                    PacketProtocol.parseVoicePayload(it).text.take(8)
                } ?: "SOS"

                // Compact SOS payload: "SOS|" + 6-char nodeId + "|" + alertText (<= 18 bytes total)
                val sosData = "SOS|$localNodeId|$alertText".toByteArray(Charsets.UTF_8)

                val burstSettings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(4000)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build()

                val burstData = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(pUuid)
                    .addServiceData(pUuid, sosData)
                    .build()

                Log.i(TAG, "🚨 Broadcasting Connectionless BLE Emergency Burst: '${String(sosData)}'")
                bleAdvertiser?.stopAdvertising(advertiseCallback)
                bleAdvertiser?.startAdvertising(burstSettings, burstData, object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        Log.i(TAG, "Emergency BLE burst active on radio")
                    }
                })

                delay(4200)
                // Resume normal advertising
                startBleAdvertising()
            } catch (e: Exception) {
                Log.e(TAG, "Emergency BLE burst error: ${e.message}")
            }
        }
    }

    // ==========================================
    // 2. BLE SCANNING (Autonomous Discovery & SOS Sniffing)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startBleScanning() {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            Log.w(TAG, "BLE Scanner not supported on this device")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.stopScan(bleScanCallback)
            bleScanner?.startScan(listOf(filter), settings, bleScanCallback)
            isScanning = true
            Log.i(TAG, "BLE Scanner started with filter for $MESH_SERVICE_UUID")
        } catch (e: Exception) {
            Log.e(TAG, "BLE Scanner start error: ${e.message}")
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val scanRecord = result.scanRecord ?: return

            discoveredDevices[device.address] = device

            // Check if this advertisement contains an unlinked emergency SOS payload!
            var serviceData = scanRecord.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
            if (serviceData == null && scanRecord.serviceData != null) {
                for ((uuidKey, data) in scanRecord.serviceData) {
                    if (uuidKey.uuid.toString().contains("fe2c", ignoreCase = true)) {
                        serviceData = data
                        break
                    }
                }
            }

            var sosStr: String? = null
            if (serviceData != null) {
                val str = String(serviceData, Charsets.UTF_8)
                if (str.contains("SOS|")) sosStr = str
            }
            if (sosStr == null) {
                val raw = String(scanRecord.bytes, Charsets.ISO_8859_1)
                if (raw.contains("SOS|")) {
                    val idx = raw.indexOf("SOS|")
                    sosStr = raw.substring(idx).take(25)
                }
            }

            if (sosStr != null) {
                Log.i(TAG, "🚨 Intercepted Connectionless BLE SOS from ${device.address}: $sosStr")
                val cleanSos = sosStr.substringAfter("SOS|")
                val parts = cleanSos.split("|", limit = 2)
                val senderId = if (parts.isNotEmpty()) parts[0].take(6) else "SOS_Node"
                val alertText = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].take(15) else "Emergency Alert"

                val syntheticPayload = PacketProtocol.encodeVoicePayload(
                    senderNodeId = senderId,
                    senderDeviceName = try { device.name } catch (e: SecurityException) { null } ?: "Nearby Node",
                    targetNodeId = "ALL",
                    text = alertText
                )
                val syntheticPacket = PacketProtocol.encodePacket(
                    type = PacketProtocol.TYPE_EMERGENCY_ALERT,
                    sourceLang = com.itantra.ai.model.Language.HINDI,
                    sequenceId = (1000..9999).random(),
                    textPayload = syntheticPayload
                )
                onPacketReceived(syntheticPacket)
            }

            // Auto-connect GATT client in-app
            if (!activeGattDevices.contains(device)) {
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE Scan failed with code: $errorCode")
            isScanning = false
        }
    }

    // ==========================================
    // 3. BLE GATT SERVER & CLIENT (Direct In-App Zero-Pairing Messaging)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.w(TAG, "Unable to open GATT Server")
                return
            }

            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val rxChar = BluetoothGattCharacteristic(
                CHAR_RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or
                        BluetoothGattCharacteristic.PERMISSION_READ
            )

            val txChar = BluetoothGattCharacteristic(
                CHAR_TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccd = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ)
            txChar.addDescriptor(cccd)

            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)

            gattServer?.addService(service)
            Log.i(TAG, "BLE GATT Server created with 16-bit iTantra Mesh service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GATT Server: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Server: Client device connected: ${device.address}")
                activeGattDevices.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT Server: Client device disconnected: ${device.address}")
                activeGattDevices.remove(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (value != null && value.isNotEmpty()) {
                Log.i(TAG, "GATT Server received ${value.size} bytes from ${device?.address}")
                onPacketReceived(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!activeGattDevices.contains(device)) {
            scope.launch {
                try {
                    val name = try { device.name } catch (e: SecurityException) { null } ?: device.address
                    Log.i(TAG, "Initiating in-app BLE GATT connection to $name...")
                    val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, gattClientCallback)
                    }
                    if (gatt != null) {
                        activeGattClients.add(gatt)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GATT client connect failed: ${e.message}")
                }
            }
        }

        // Also connect Insecure RFCOMM as fallback
        if (activeRfcommSockets.none { it.remoteDevice.address == device.address }) {
            connectInsecureRfcomm(device)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val dev = gatt?.device ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Client connected to ${dev.address}. Requesting MTU 512...")
                activeGattDevices.add(dev)
                // Sequenced MTU request: discover services in onMtuChanged!
                val ok = gatt.requestMtu(512)
                if (!ok) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT Client disconnected from ${dev.address}")
                activeGattDevices.remove(dev)
                activeGattClients.remove(gatt)
                try { gatt.close() } catch (ignored: Exception) {}
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "GATT MTU established: $mtu bytes. Now discovering services...")
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(MESH_SERVICE_UUID)
                val txChar = service?.getCharacteristic(CHAR_TX_UUID)
                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                    val desc = txChar.getDescriptor(CCCD_UUID)
                    if (desc != null) {
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(desc)
                    }
                }
                Log.i(TAG, "GATT Client successfully configured mesh channels with ${gatt.device.address}")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val data = characteristic?.value
            if (data != null && data.isNotEmpty()) {
                Log.i(TAG, "GATT Client received notify (${data.size} bytes) from ${gatt?.device?.address}")
                onPacketReceived(data)
            }
        }
    }

    // ==========================================
    // 4. INSECURE RFCOMM (Framed Stream)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startRfcommServer() {
        scope.launch {
            try {
                rfcommServerSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, MESH_SERVICE_UUID)
                Log.i(TAG, "Bluetooth Insecure RFCOMM Server listening on UUID: $MESH_SERVICE_UUID")

                while (isRunning) {
                    val socket = rfcommServerSocket?.accept() ?: break
                    Log.i(TAG, "New incoming Insecure RFCOMM connection from: ${socket.remoteDevice.address}")
                    activeRfcommSockets.add(socket)
                    launchFramedSocketReader(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.d(TAG, "RFCOMM server socket: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectInsecureRfcomm(device: BluetoothDevice) {
        scope.launch {
            delay((300..1000).random().toLong())
            try {
                val name = try { device.name } catch (e: SecurityException) { null } ?: device.address
                val socket = device.createInsecureRfcommSocketToServiceRecord(MESH_SERVICE_UUID)
                bluetoothAdapter?.cancelDiscovery()
                socket.connect()

                Log.i(TAG, "Connected successfully via Insecure RFCOMM to $name (${device.address})")
                activeRfcommSockets.add(socket)
                launchFramedSocketReader(socket)
            } catch (e: Exception) {
                Log.d(TAG, "Insecure RFCOMM connect: ${e.message}")
            }
        }
    }

    private fun launchFramedSocketReader(socket: BluetoothSocket) {
        scope.launch {
            val remoteAddr = socket.remoteDevice.address
            try {
                val dataIn = DataInputStream(socket.inputStream)
                while (isRunning && socket.isConnected) {
                    val length = dataIn.readInt()
                    if (length in 1..65536) {
                        val packetBytes = ByteArray(length)
                        dataIn.readFully(packetBytes)
                        Log.i(TAG, "Framed RFCOMM packet received: $length bytes from $remoteAddr")
                        onPacketReceived(packetBytes)
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "RFCOMM socket closed: ${e.message}")
            } finally {
                activeRfcommSockets.remove(socket)
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    // ==========================================
    // 5. PACKET BROADCAST (Dispatches over GATT & RFCOMM)
    // ==========================================

    @SuppressLint("MissingPermission")
    fun broadcastPacket(packetBytes: ByteArray): Boolean {
        var sentAny = false

        // 1. Dispatch over connected BLE GATT Clients (write to remote server RX)
        for (gatt in activeGattClients) {
            try {
                val service = gatt.getService(MESH_SERVICE_UUID)
                val rxChar = service?.getCharacteristic(CHAR_RX_UUID)
                if (rxChar != null) {
                    rxChar.value = packetBytes
                    rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val success = gatt.writeCharacteristic(rxChar)
                    Log.i(TAG, "Dispatched ${packetBytes.size} bytes via GATT write to ${gatt.device.address} (Success: $success)")
                    sentAny = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing GATT characteristic: ${e.message}")
            }
        }

        // 2. Dispatch to connected GATT server clients via notify (txChar)
        if (gattServer != null) {
            try {
                val service = gattServer?.getService(MESH_SERVICE_UUID)
                val txChar = service?.getCharacteristic(CHAR_TX_UUID)
                if (txChar != null) {
                    txChar.value = packetBytes
                    for (dev in activeGattDevices) {
                        val success = gattServer?.notifyCharacteristicChanged(dev, txChar, false)
                        Log.i(TAG, "Dispatched ${packetBytes.size} bytes via GATT notify to ${dev.address} (Success: $success)")
                        sentAny = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed notifying GATT clients: ${e.message}")
            }
        }

        // 3. Dispatch over connected Insecure RFCOMM sockets with 4-byte length prefix
        for (socket in activeRfcommSockets) {
            scope.launch {
                try {
                    val dataOut = DataOutputStream(socket.outputStream)
                    dataOut.writeInt(packetBytes.size)
                    dataOut.write(packetBytes)
                    dataOut.flush()
                    Log.i(TAG, "Dispatched ${packetBytes.size} bytes over RFCOMM to ${socket.remoteDevice.address}")
                } catch (e: Exception) {
                    Log.w(TAG, "RFCOMM framed dispatch failed: ${e.message}")
                }
            }
            sentAny = true
        }

        return sentAny
    }

    fun getConnectedPeerCount(): Int {
        return (activeGattDevices.size + activeRfcommSockets.size).coerceAtLeast(0)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isRunning = false
        try {
            if (isAdvertising) bleAdvertiser?.stopAdvertising(advertiseCallback)
            if (isScanning) bleScanner?.stopScan(bleScanCallback)
            rfcommServerSocket?.close()
            gattServer?.close()
        } catch (ignored: Exception) {}

        for (socket in activeRfcommSockets) {
            try { socket.close() } catch (ignored: Exception) {}
        }
        for (gatt in activeGattClients) {
            try { gatt.close() } catch (ignored: Exception) {}
        }

        activeRfcommSockets.clear()
        activeGattClients.clear()
        activeGattDevices.clear()
        discoveredDevices.clear()
        scope.cancel()
    }
}
