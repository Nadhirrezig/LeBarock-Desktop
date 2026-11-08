package com.lebarock.lebarock_desktop.data.settings

import kotlinx.serialization.Serializable

/**
 * Where the caisse server is on the LAN, typed in Réglages → Serveur like on the handheld:
 * protocol, IP address (or name), port. The deployment serves the caisse in HTTPS on 443
 * (mkcert certificate).
 */
@Serializable
data class ServerAddress(
    val https: Boolean = true,
    val host: String = "",
    val port: Int = 443,
) {
    val isConfigured: Boolean get() = host.isNotBlank()

    /** e.g. `https://192.168.1.20/`, `http://192.168.1.20:8000/` (the default port is left out). */
    val baseUrl: String
        get() {
            if (host.isBlank()) return ""
            val scheme = if (https) "https" else "http"
            val defaultPort = if (https) 443 else 80
            val authority = if (port == defaultPort) host.trim() else "${host.trim()}:$port"
            return "$scheme://$authority/"
        }

    /** What the screens show: `192.168.1.20:443 (HTTPS)`. */
    val label: String get() = if (host.isBlank()) "—" else "${host.trim()}:$port (${if (https) "HTTPS" else "HTTP"})"

    companion object {
        /** Error shown under the field, or null when the input is usable. */
        fun hostError(host: String): String? {
            val value = host.trim()
            return when {
                value.isEmpty() -> "Saisissez l'adresse IP du serveur (par exemple 192.168.1.20)."
                value.any { it.isWhitespace() } -> "L'adresse ne peut pas contenir d'espace."
                !HOST.matches(value) -> "Adresse invalide : saisissez seulement l'adresse IP ou le nom (sans « / »)."
                IPV4_LIKE.matches(value) && value.split('.').any { it.toInt() > 255 } ->
                    "Adresse IP invalide : chaque nombre va de 0 à 255."
                else -> null
            }
        }

        fun portError(port: String): String? {
            val value = port.trim().toIntOrNull()
            return if (value == null || value !in 1..65535) "Le port est un nombre de 1 à 65535 (HTTPS : 443)." else null
        }

        /**
         * Reads what someone pasted into the address field — `https://192.168.1.20:8443/api` — into its
         * parts, so a copied URL fills protocol and port too. Null when it is not a URL.
         */
        fun parsePasted(text: String): ServerAddress? {
            val match = URL.matchEntire(text.trim()) ?: return null
            val https = match.groupValues[1].lowercase() == "https"
            val host = match.groupValues[2]
            val port = match.groupValues[3].toIntOrNull() ?: if (https) 443 else 80
            return ServerAddress(https = https, host = host, port = port)
        }

        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
        private val IPV4_LIKE = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
        private val URL = Regex("^(https?)://([A-Za-z0-9.-]+)(?::(\\d{1,5}))?(?:/.*)?$", RegexOption.IGNORE_CASE)
    }
}
