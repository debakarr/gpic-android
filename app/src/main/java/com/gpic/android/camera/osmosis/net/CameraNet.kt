package com.gpic.android.camera.osmosis.net

import android.net.Network
import java.net.DatagramSocket
import java.net.Socket

/**
 * GPic addition (not in upstream osmosis). Osmosis binds the whole process to the camera's
 * internet-less WiFi; GPic must keep reaching Google at the same time, so only camera sockets are
 * bound — to the [network] set by the WiFi join. Null = default routing (tests).
 */
object CameraNet {
    @Volatile var network: Network? = null

    fun bind(sock: DatagramSocket) {
        network?.bindSocket(sock)
    }

    fun tcpSocket(): Socket = network?.socketFactory?.createSocket() ?: Socket()
}
