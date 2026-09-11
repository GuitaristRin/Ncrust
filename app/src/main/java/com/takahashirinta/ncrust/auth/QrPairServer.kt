package com.takahashirinta.ncrust.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 平板侧配对服务：仅在展示二维码期间监听局域网。
 *  - UDP 47821：应答手机的 unikey 询问，告知自己的 IP（由包源地址得出）与 TCP 端口；
 *  - TCP：接收一次加密 cookie，解密校验含 MUSIC_U 后回调，然后停止。
 *
 * unikey 作为一次性密钥；成功后上层应调用 [stop]。
 */
class QrPairServer(
    private val unikey: String,
    private val onCookie: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var udpSocket: DatagramSocket? = null

    @Volatile private var tcpServer: ServerSocket? = null

    fun start() {
        scope.launch {
            val server = runCatching { ServerSocket(0) }.getOrNull() ?: return@launch
            tcpServer = server
            val tcpPort = server.localPort

            launch { serveUdp(tcpPort) }

            while (isActive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val delivered = runCatching { receive(client) }.getOrDefault(false)
                runCatching { client.close() }
                if (delivered) break
            }
        }
    }

    private fun serveUdp(tcpPort: Int) {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(QrPair.UDP_PORT))
            }
        }.getOrNull() ?: return
        udpSocket = socket
        val buf = ByteArray(512)
        while (scope.isActive) {
            val packet = DatagramPacket(buf, buf.size)
            if (runCatching { socket.receive(packet) }.isFailure) break
            if (QrPair.parseRequest(String(packet.data, 0, packet.length)) == unikey) {
                val resp = QrPair.responsePayload(unikey, tcpPort).toByteArray(Charsets.UTF_8)
                runCatching { socket.send(DatagramPacket(resp, resp.size, packet.address, packet.port)) }
            }
        }
    }

    private fun receive(client: Socket): Boolean {
        client.soTimeout = 5_000
        val input = DataInputStream(client.getInputStream())
        val length = input.readInt()
        if (length !in 1..65_535) return false
        val data = ByteArray(length)
        input.readFully(data)
        val cookie = QrPair.decrypt(unikey, data) ?: return false
        if (!cookie.contains("MUSIC_U=")) return false
        scope.launch(Dispatchers.Main) { onCookie(cookie) }
        return true
    }

    fun stop() {
        runCatching { udpSocket?.close() }
        runCatching { tcpServer?.close() }
        udpSocket = null
        tcpServer = null
        scope.cancel()
    }
}
