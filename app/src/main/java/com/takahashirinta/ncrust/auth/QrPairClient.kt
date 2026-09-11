package com.takahashirinta.ncrust.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 手机侧配对客户端：按 unikey UDP 广播发现平板，再把本机会话 cookie 加密回传。
 * 成功返回 true；未发现设备/连接失败/发送失败返回 false。
 */
object QrPairClient {

    suspend fun sendCookie(unikey: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        val target = discover(unikey) ?: return@withContext false
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.first, target.second), 4_000)
                socket.soTimeout = 4_000
                val payload = QrPair.encrypt(unikey, cookie)
                DataOutputStream(socket.getOutputStream()).use { out ->
                    out.writeInt(payload.size)
                    out.write(payload)
                    out.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun discover(unikey: String): Pair<String, Int>? {
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return null
        socket.broadcast = true
        socket.soTimeout = 400
        val request = QrPair.requestPayload(unikey).toByteArray(Charsets.UTF_8)
        runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()?.let { broadcast ->
            repeat(3) {
                runCatching { socket.send(DatagramPacket(request, request.size, broadcast, QrPair.UDP_PORT)) }
            }
        }
        val deadline = System.currentTimeMillis() + 3_000
        val buf = ByteArray(512)
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buf, buf.size)
            val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
            if (!received) continue
            val parsed = QrPair.parseResponse(String(packet.data, 0, packet.length))
            if (parsed != null && parsed.first == unikey) {
                runCatching { socket.close() }
                return packet.address.hostAddress to parsed.second
            }
        }
        runCatching { socket.close() }
        return null
    }
}
