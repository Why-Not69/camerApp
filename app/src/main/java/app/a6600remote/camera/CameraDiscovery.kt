package app.a6600remote.camera

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Поиск адреса API камеры.
 * 1) SSDP (так же ищет камеру Imaging Edge Mobile);
 * 2) если не ответила — пробуем известные адреса Sony напрямую.
 */
object CameraDiscovery {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SONY_ST = "urn:schemas-sony-com:service:ScalarWebAPI:1"

    private val FALLBACK_ENDPOINTS = listOf(
        "http://192.168.122.1:8080/sony/camera",
        "http://192.168.122.1:10000/sony/camera",
    )

    suspend fun findEndpoint(context: Context, log: (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val viaSsdp = try {
                ssdpSearch(context, log)
            } catch (e: Exception) {
                log("SSDP ошибка: ${e.message}")
                null
            }
            if (viaSsdp != null) return@withContext viaSsdp

            for (ep in FALLBACK_ENDPOINTS) {
                log("Пробую $ep")
                try {
                    SonyCameraApi(ep).call("getVersions", timeoutMs = 2_000)
                    return@withContext ep
                } catch (e: Exception) {
                    log("  нет ответа: ${e.message}")
                }
            }
            null
        }

    private fun ssdpSearch(context: Context, log: (String) -> Unit): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)!!
        val lock = wifi.createMulticastLock("a6600remote-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 1\r\n" +
                        "ST: $SONY_ST\r\n\r\n").toByteArray()
                val group = InetAddress.getByName(SSDP_ADDR)

                repeat(3) {
                    socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT))
                    val deadline = System.currentTimeMillis() + 1_500
                    while (System.currentTimeMillis() < deadline) {
                        val buf = ByteArray(4096)
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val text = String(packet.data, 0, packet.length)
                        val location = Regex("(?im)^LOCATION:\\s*(\\S+)")
                            .find(text)?.groupValues?.get(1) ?: continue
                        log("SSDP ответ: $location")
                        val endpoint = parseDeviceDescription(location, log)
                        if (endpoint != null) return endpoint
                    }
                }
            }
        } finally {
            lock.release()
        }
        log("SSDP: камера не ответила")
        return null
    }

    /** Достаём из XML-описания адрес сервиса "camera". */
    private fun parseDeviceDescription(location: String, log: (String) -> Unit): String? {
        val xml = try {
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            log("Не прочитал описание камеры: ${e.message}")
            return null
        }
        return findCameraServiceUrl(xml)
    }

    /** Вынесено отдельно, чтобы можно было проверить без Android. */
    fun findCameraServiceUrl(xml: String): String? {
        val blocks = xml.split("<av:X_ScalarWebAPI_Service>").drop(1)
        for (block in blocks) {
            val type = Regex("<av:X_ScalarWebAPI_ServiceType>\\s*(.*?)\\s*</av:X_ScalarWebAPI_ServiceType>")
                .find(block)?.groupValues?.get(1)
            val url = Regex("<av:X_ScalarWebAPI_ActionList_URL>\\s*(.*?)\\s*</av:X_ScalarWebAPI_ActionList_URL>")
                .find(block)?.groupValues?.get(1)
            if (type == "camera" && !url.isNullOrBlank()) {
                return url.trimEnd('/') + "/camera"
            }
        }
        return null
    }
}
