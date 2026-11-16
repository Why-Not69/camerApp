package app.a6600remote.camera

data class WifiCredentials(val ssid: String, val password: String)

/**
 * Разбор QR-кода, который камера показывает в «Упр. со смартфона → Подключение».
 *
 * Формат Sony:  W01:S:JAC4;P:e2hDfgho;C:DSC-RX100M7;M:D44DA4A65459
 *   S — часть имени сети, P — пароль, C — модель, M — MAC.
 *   Имя сети = "DIRECT-" + S + ":" + C   (например DIRECT-JAC4:ILCE-6600)
 *
 * На всякий случай понимаем и стандартный Wi‑Fi QR:  WIFI:T:WPA;S:имя;P:пароль;;
 */
object CameraQr {

    fun parse(raw: String): WifiCredentials? {
        val text = raw.trim()
        return when {
            text.startsWith("W01:", ignoreCase = true) -> parseSony(text.substring(4))
            text.startsWith("WIFI:", ignoreCase = true) -> parseStandard(text.substring(5))
            else -> null
        }
    }

    private fun parseSony(body: String): WifiCredentials? {
        val fields = body.split(';')
            .mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i).uppercase() to part.substring(i + 1)
            }
            .toMap()
        val s = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
        val p = fields["P"] ?: return null
        val model = fields["C"].orEmpty()
        val ssid = if (model.isNotBlank()) "DIRECT-$s:$model" else "DIRECT-$s"
        return WifiCredentials(ssid, p)
    }

    private fun parseStandard(body: String): WifiCredentials? {
        // Поля разделены ';', спецсимволы экранируются обратной косой чертой.
        val fields = mutableMapOf<String, String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in body) {
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == ';' -> {
                    val part = current.toString()
                    val i = part.indexOf(':')
                    if (i > 0) fields[part.substring(0, i).uppercase()] = part.substring(i + 1)
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        val ssid = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
        return WifiCredentials(ssid, fields["P"].orEmpty())
    }
}
