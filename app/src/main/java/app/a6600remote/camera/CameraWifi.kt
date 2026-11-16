package app.a6600remote.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Подключение к Wi‑Fi камеры и «привязка» приложения к этой сети.
 *
 * У Wi‑Fi камеры нет интернета, и Android (особенно HyperOS) любит отправлять
 * трафик через мобильные данные. Поэтому мы явно просим систему дать нам Wi‑Fi сеть
 * без интернета и привязываем к ней весь процесс приложения (bindProcessToNetwork).
 */
class CameraWifi(context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)!!
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Режим Wi‑Fi «низкая задержка»: телефон перестаёт экономить энергию на Wi‑Fi,
     * из-за чего кадры превью иначе приходят рывками. Действует, пока приложение на экране.
     */
    private val lowLatencyLock: WifiManager.WifiLock? = try {
        context.applicationContext.getSystemService(WifiManager::class.java)!!
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "a6600remote-liveview")
            .apply { setReferenceCounted(false) }
    } catch (_: Exception) {
        null
    }

    /** Второй замок — «полная мощность»: на части телефонов работает он, а не «низкая задержка». */
    @Suppress("DEPRECATION")
    private val highPerfLock: WifiManager.WifiLock? = try {
        context.applicationContext.getSystemService(WifiManager::class.java)!!
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a6600remote-highperf")
            .apply { setReferenceCounted(false) }
    } catch (_: Exception) {
        null
    }

    /** Вызывается, если сеть камеры пропала (камера выключилась, ушли слишком далеко). */
    var onLost: (() -> Unit)? = null

    /**
     * @param ssid если указан — Android сам подключится к этой сети (покажет системный диалог
     *             при первом подключении). Если пустой — используем Wi‑Fi, к которому телефон
     *             уже подключён вручную в настройках.
     */
    suspend fun connect(ssid: String?, password: String?, timeoutMs: Long = 45_000): Network {
        release()

        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (!ssid.isNullOrBlank()) {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid.trim())
                .apply { if (!password.isNullOrEmpty()) setWpa2Passphrase(password) }
                .build()
            builder.setNetworkSpecifier(spec)
        }

        val result = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                try {
                    lowLatencyLock?.acquire()
                } catch (_: Exception) {
                }
                try {
                    highPerfLock?.acquire()
                } catch (_: Exception) {
                }
                result.complete(network)
            }

            override fun onUnavailable() {
                result.completeExceptionally(
                    IllegalStateException("Не удалось подключиться к Wi‑Fi камеры")
                )
            }

            override fun onLost(network: Network) {
                cm.bindProcessToNetwork(null)
                onLost?.invoke()
            }
        }
        callback = cb
        cm.requestNetwork(builder.build(), cb, timeoutMs.toInt())

        return withTimeout(timeoutMs + 2_000) { result.await() }
    }

    fun release() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        cm.bindProcessToNetwork(null)
        try {
            if (lowLatencyLock?.isHeld == true) lowLatencyLock.release()
        } catch (_: Exception) {
        }
        try {
            if (highPerfLock?.isHeld == true) highPerfLock.release()
        } catch (_: Exception) {
        }
    }
}
