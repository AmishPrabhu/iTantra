package com.itantra.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.*
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wi-Fi Direct (Wi-Fi P2P) & Local UDP Disaster Mesh Manager.
 * Operates high-speed peer-to-peer UDP Broadcast and direct TCP streams
 * for instant voice packets, translations, and SOS alerts across all nearby devices.
 */
class WifiDirectMeshManager(
    private val context: Context,
    private val onPacketReceived: (ByteArray, String) -> Unit
) {

    private val TAG = "WifiDirectMeshManager"
    private val MESH_PORT = 8988

    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager: android.net.wifi.WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var channel: WifiP2pManager.Channel? = null

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val knownPeerIps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isRunning = false
    private var isGroupOwner = false

    private val _discoveredDevices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WifiP2pDevice>> = _discoveredDevices.asStateFlow()

    init {
        channel = p2pManager?.initialize(context, Looper.getMainLooper(), null)
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        isRunning = true
        try {
            multicastLock = wifiManager?.createMulticastLock("iTantraMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            wifiLock = wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iTantraWifiLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.i(TAG, "Acquired Wi-Fi MulticastLock & High-Perf WifiLock for UDP broadcast")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        startUdpBroadcastListener()
        startTcpServer()

        if (p2pManager != null && channel != null) {
            p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct peer discovery started successfully")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct peer discovery failed (Reason: $reason)")
                }
            })

            // Periodically poll for nearby peer changes
            scope.launch {
                while (isRunning) {
                    requestPeersUpdate()
                    delay(3000)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestPeersUpdate() {
        if (p2pManager != null && channel != null) {
            p2pManager.requestPeers(channel) { peers ->
                val list = peers?.deviceList?.toList() ?: emptyList()
                _discoveredDevices.value = list
                Log.d(TAG, "Discovered Wi-Fi Direct peers: ${list.map { it.deviceName }}")
            }
        }
    }

    private fun startUdpBroadcastListener() {
        scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                Log.i(TAG, "UDP Disaster Mesh Broadcast listening on port $MESH_PORT")

                val buffer = ByteArray(4096)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val receivedBytes = buffer.copyOf(packet.length)
                    val senderIp = packet.address?.hostAddress ?: "UDP_Peer"
                    if (senderIp != "UDP_Peer" && senderIp != "127.0.0.1" && senderIp != "localhost") {
                        knownPeerIps.add(senderIp)
                    }
                    Log.i(TAG, "Received UDP Mesh Packet from $senderIp:${packet.port} (${receivedBytes.size} bytes)")
                    onPacketReceived(receivedBytes, senderIp)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "UDP Broadcast Socket error: ${e.message}")
                }
            }
        }
    }

    private fun startTcpServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(MESH_PORT)
                Log.i(TAG, "Wi-Fi Direct TCP Server listening on port $MESH_PORT")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress?.hostAddress ?: "TCP_Peer"
                    Log.i(TAG, "Wi-Fi Direct client socket connected from $clientIp")
                    if (clientIp != "TCP_Peer") {
                        knownPeerIps.add(clientIp)
                    }
                    clientSockets.add(socket)
                    launchSocketReader(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Wi-Fi Direct Server Socket error: ${e.message}")
                }
            }
        }
    }

    fun connectToPeer(hostIp: String) {
        scope.launch {
            try {
                Log.i(TAG, "Connecting to Wi-Fi Direct peer at $hostIp:$MESH_PORT...")
                val socket = Socket()
                socket.connect(InetSocketAddress(hostIp, MESH_PORT), 5000)

                Log.i(TAG, "Connected to Wi-Fi Direct peer: $hostIp")
                knownPeerIps.add(hostIp)
                clientSockets.add(socket)
                launchSocketReader(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Failed connecting to Wi-Fi Direct peer $hostIp: ${e.message}")
            }
        }
    }

    private fun launchSocketReader(socket: Socket) {
        scope.launch {
            val buffer = ByteArray(4096)
            val remoteIp = socket.inetAddress?.hostAddress ?: "TCP_Peer"
            try {
                val inputStream: InputStream = socket.getInputStream()
                while (isRunning && !socket.isClosed) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val packet = buffer.copyOf(bytesRead)
                        Log.d(TAG, "Received $bytesRead bytes over Wi-Fi Direct TCP from $remoteIp")
                        onPacketReceived(packet, remoteIp)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Direct socket closed: ${e.message}")
            } finally {
                clientSockets.remove(socket)
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    fun broadcastPacket(packetBytes: ByteArray): Boolean {
        scope.launch {
            // 1. Dispatch via UDP Broadcast to 255.255.255.255 & all network interfaces
            try {
                val sendSocket = DatagramSocket().apply { broadcast = true }
                val broadcastAddresses = getBroadcastAddresses()
                for (bAddr in broadcastAddresses) {
                    try {
                        val dPacket = DatagramPacket(packetBytes, packetBytes.size, bAddr, MESH_PORT)
                        sendSocket.send(dPacket)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed sending to $bAddr: ${e.message}")
                    }
                }

                // 2. Direct Unicast to all discovered peer IPs (bypasses Android hotspot client multicast isolation)
                for (peerIp in knownPeerIps) {
                    try {
                        val peerAddr = InetAddress.getByName(peerIp)
                        val dPacket = DatagramPacket(packetBytes, packetBytes.size, peerAddr, MESH_PORT)
                        sendSocket.send(dPacket)
                        Log.d(TAG, "Dispatched direct unicast mesh packet to $peerIp:$MESH_PORT")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed unicast to $peerIp: ${e.message}")
                    }
                }
                sendSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "UDP Broadcast send error: ${e.message}")
            }

            // 3. Also send over any connected TCP sockets
            for (socket in clientSockets) {
                try {
                    val out: OutputStream = socket.getOutputStream()
                    out.write(packetBytes)
                    out.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "TCP socket dispatch error: ${e.message}")
                }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun connectToP2pDevice(deviceAddress: String) {
        if (p2pManager != null && channel != null) {
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                wps.setup = android.net.wifi.WpsInfo.PBC
            }
            p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct connect requested to $deviceAddress")
                    scope.launch {
                        delay(2000)
                        requestConnectionInfoUpdate()
                    }
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct connect failed ($reason)")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun requestConnectionInfoUpdate() {
        if (p2pManager != null && channel != null) {
            p2pManager.requestConnectionInfo(channel) { info ->
                if (info != null && info.groupFormed) {
                    isGroupOwner = info.isGroupOwner
                    if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                        val host = info.groupOwnerAddress.hostAddress
                        if (host != null && clientSockets.isEmpty()) {
                            connectToPeer(host)
                        }
                    }
                }
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
            // Common Android portable hotspot broadcast and gateway IPs
            try { broadcastList.add(InetAddress.getByName("192.168.43.255")) } catch (ignored: Exception) {}
            try { broadcastList.add(InetAddress.getByName("192.168.43.1")) } catch (ignored: Exception) {}
            try { broadcastList.add(InetAddress.getByName("192.168.49.255")) } catch (ignored: Exception) {}
            try { broadcastList.add(InetAddress.getByName("192.168.49.1")) } catch (ignored: Exception) {}

            // Direct unicast dispatch to hotspot client IP range (bypasses kernel broadcast blocking on tethering)
            for (i in 2..15) {
                try { broadcastList.add(InetAddress.getByName("192.168.43.$i")) } catch (ignored: Exception) {}
                try { broadcastList.add(InetAddress.getByName("192.168.49.$i")) } catch (ignored: Exception) {}
            }

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering broadcast addresses: ${e.message}")
        }
        return broadcastList.distinct()
    }

    fun getConnectedPeerCount(): Int = clientSockets.size

    fun stop() {
        isRunning = false
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Exception) {}
        try { udpSocket?.close() } catch (ignored: Exception) {}
        try { serverSocket?.close() } catch (ignored: Exception) {}
        for (socket in clientSockets) {
            try { socket.close() } catch (ignored: Exception) {}
        }
        clientSockets.clear()
        scope.cancel()
    }
}

