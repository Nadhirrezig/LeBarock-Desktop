package com.lebarock.lebarock_desktop.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Why a call failed, so a screen can say what to do (Réglages → "Tester la connexion" above all). */
enum class FailureKind {
    /** The caisse answered with a refusal or an error (its own French message). */
    REFUSED,
    /** The session is over (401): sign in again. */
    SESSION_EXPIRED,
    /** Nothing listens at that address and port (connection refused). */
    CONNECTION_REFUSED,
    /** No answer in time: wrong IP, server PC off, other network. */
    TIMEOUT,
    /** The name could not be found. */
    UNKNOWN_HOST,
    /** No route to that address (other network, cable, Wi-Fi). */
    NO_ROUTE,
    /** HTTPS, but the certificate is not trusted on this PC. */
    CERTIFICATE_UNTRUSTED,
    /** The certificate is trusted but was made for another address. */
    CERTIFICATE_WRONG_HOST,
    CERTIFICATE_EXPIRED,
    /** HTTPS was asked from a port that speaks plain HTTP. */
    HTTPS_TO_HTTP_PORT,
    /** Plain HTTP was sent to the HTTPS port. */
    HTTP_TO_HTTPS_PORT,
    /** Something answers, but it is not the caisse's API. */
    NOT_A_CAISSE,
    SERVER_ERROR,
    OTHER,
}

/**
 * A failed call: the French [message] to show, what kind of failure it is, the HTTP status when the
 * caisse answered, and the caisse's own error [code] ("no_caisse", "stock_waiting"…) — the same
 * shape as the handheld's ApiFailure, plus [kind] and [hint] for the counter's Réglages.
 */
class ApiFailure(
    message: String,
    val kind: FailureKind,
    val httpCode: Int? = null,
    val code: String? = null,
    val hint: String? = null,
    val orderId: Long? = null,
    val canForce: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The caisse could not be reached at all (as opposed to answering with a refusal). */
    val unreachable: Boolean
        get() = kind in setOf(
            FailureKind.CONNECTION_REFUSED, FailureKind.TIMEOUT, FailureKind.UNKNOWN_HOST, FailureKind.NO_ROUTE,
        ) || httpCode in 502..504

    val sessionExpired: Boolean get() = kind == FailureKind.SESSION_EXPIRED
    val noCaisse: Boolean get() = code == "no_caisse"
    val stockWaiting: Boolean get() = code == "stock_waiting"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Turns any exception from a call into an [ApiFailure] with a French message. */
        fun from(e: Throwable, https: Boolean = true): ApiFailure = when (e) {
            is ApiFailure -> e
            is HttpException -> fromHttp(e, https)
            is SSLPeerUnverifiedException -> ApiFailure(
                "Le certificat du serveur ne correspond pas à cette adresse.",
                FailureKind.CERTIFICATE_WRONG_HOST,
                hint = "Utilisez l'adresse pour laquelle le certificat a été fait (celle du LISEZMOI), ou refaites le certificat avec cette adresse.",
                cause = e,
            )
            is SSLHandshakeException -> fromHandshake(e)
            is SSLException -> if (e.message.orEmpty().contains("plaintext", true) ||
                e.message.orEmpty().contains("Unrecognized SSL message", true) ||
                e.message.orEmpty().contains("Unsupported or unrecognized", true)
            ) {
                ApiFailure(
                    "Ce port répond en HTTP, pas en HTTPS.",
                    FailureKind.HTTPS_TO_HTTP_PORT,
                    hint = "Choisissez le port HTTPS de la caisse (443), ou le protocole HTTP pour ce port.",
                    cause = e,
                )
            } else {
                ApiFailure("Erreur de connexion sécurisée : ${e.message}", FailureKind.OTHER, cause = e)
            }
            is UnknownHostException -> ApiFailure(
                "Adresse introuvable.",
                FailureKind.UNKNOWN_HOST,
                hint = "Vérifiez l'adresse IP du serveur (par exemple 192.168.1.20).",
                cause = e,
            )
            is ConnectException -> if (e.message.orEmpty().contains("timed out", true)) {
                timeout(e)
            } else {
                ApiFailure(
                    "Le serveur refuse la connexion sur ce port.",
                    FailureKind.CONNECTION_REFUSED,
                    hint = "L'adresse répond mais rien n'écoute sur ce port : vérifiez le port (HTTPS : 443) et que la caisse (Docker) est démarrée.",
                    cause = e,
                )
            }
            is NoRouteToHostException -> ApiFailure(
                "Aucune route vers cette adresse.",
                FailureKind.NO_ROUTE,
                hint = "Ce PC n'est peut-être pas sur le même réseau que le serveur (câble, Wi-Fi).",
                cause = e,
            )
            is SocketTimeoutException -> timeout(e)
            is SerializationException, is IllegalArgumentException -> notACaisse(e)
            is IOException -> if (e.message.orEmpty().contains("timeout", true)) timeout(e) else ApiFailure(
                "Serveur injoignable (${e.message ?: e::class.java.simpleName}).",
                FailureKind.OTHER,
                hint = "Vérifiez l'adresse dans Réglages → Serveur.",
                cause = e,
            )
            else -> ApiFailure(e.message ?: "Erreur inattendue.", FailureKind.OTHER, cause = e)
        }

        private fun timeout(e: Throwable) = ApiFailure(
            "Le serveur ne répond pas (délai dépassé).",
            FailureKind.TIMEOUT,
            hint = "Vérifiez l'adresse IP, que le PC serveur est allumé et que ce PC est sur le même réseau.",
            cause = e,
        )

        private fun notACaisse(e: Throwable) = ApiFailure(
            "Ce serveur répond, mais ce n'est pas une caisse AltosPOS.",
            FailureKind.NOT_A_CAISSE,
            hint = "Vérifiez l'adresse et le port : la caisse est à l'adresse de la page web de la caisse (port 443).",
            cause = e,
        )

        private fun fromHandshake(e: SSLHandshakeException): ApiFailure {
            val causes = generateSequence<Throwable>(e) { it.cause }.toList()
            return when {
                causes.any { it is CertificateExpiredException } -> ApiFailure(
                    "Le certificat du serveur a expiré.",
                    FailureKind.CERTIFICATE_EXPIRED,
                    hint = "Refaites le certificat du serveur (mkcert) puis redémarrez la caisse.",
                    cause = e,
                )
                causes.any { it is CertPathValidatorException } ||
                    e.message.orEmpty().contains("PKIX", true) ||
                    e.message.orEmpty().contains("certification path", true) -> ApiFailure(
                    "Le certificat du serveur n'est pas reconnu sur ce PC.",
                    FailureKind.CERTIFICATE_UNTRUSTED,
                    hint = "Installez rootCA.pem dans Windows (double-clic → Installer → Autorités de certification racines de confiance), ou importez-le dans Réglages → Certificat.",
                    cause = e,
                )
                e.message.orEmpty().contains("plaintext", true) || e.message.orEmpty().contains("Unrecognized SSL", true) ||
                    e.message.orEmpty().contains("Remote host terminated", true) -> ApiFailure(
                    "Ce port répond en HTTP, pas en HTTPS.",
                    FailureKind.HTTPS_TO_HTTP_PORT,
                    hint = "Choisissez le port HTTPS de la caisse (443), ou le protocole HTTP pour ce port.",
                    cause = e,
                )
                else -> ApiFailure("Échec de la connexion sécurisée : ${e.message}", FailureKind.OTHER, cause = e)
            }
        }

        private fun fromHttp(e: HttpException, https: Boolean): ApiFailure {
            val code = e.code()
            val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val body = parseBody(raw)
            return when {
                code == 401 -> ApiFailure(
                    body.message?.takeUnless { it.equals("Unauthenticated.", true) } ?: "Session expirée : reconnectez-vous.",
                    FailureKind.SESSION_EXPIRED, code, body.code, cause = e,
                )
                code == 400 && !https && raw.contains("HTTPS port", true) -> ApiFailure(
                    "Ce port attend HTTPS.",
                    FailureKind.HTTP_TO_HTTPS_PORT, code,
                    hint = "Choisissez le protocole HTTPS.",
                    cause = e,
                )
                code == 404 && body.message == null -> ApiFailure(
                    "Ce serveur répond, mais ce n'est pas une caisse AltosPOS (page introuvable).",
                    FailureKind.NOT_A_CAISSE, code,
                    hint = "Vérifiez l'adresse et le port de la caisse.",
                    cause = e,
                )
                code == 429 -> ApiFailure(
                    "Trop de tentatives. Patientez une minute puis réessayez.",
                    FailureKind.REFUSED, code, body.code, cause = e,
                )
                code in 502..504 -> ApiFailure(
                    "La caisse ne répond pas (le serveur web est là, l'application non).",
                    FailureKind.SERVER_ERROR, code,
                    hint = "La caisse redémarre peut-être : réessayez dans une minute.",
                    cause = e,
                )
                code >= 500 -> ApiFailure(
                    body.message?.takeUnless { it.equals("Server Error", true) } ?: "Erreur du serveur de la caisse ($code).",
                    FailureKind.SERVER_ERROR, code, body.code,
                    hint = "Le détail est dans le journal de la caisse.",
                    cause = e,
                )
                else -> ApiFailure(
                    body.message ?: "La caisse a refusé la demande ($code).",
                    FailureKind.REFUSED, code, body.code,
                    orderId = body.orderId, canForce = body.canForce, cause = e,
                )
            }
        }

        private data class Body(
            val message: String? = null,
            val code: String? = null,
            val orderId: Long? = null,
            val canForce: Boolean = false,
        )

        /** A Laravel error body: the first validation message, else `message`; plus the caisse's code. */
        private fun parseBody(raw: String): Body {
            if (raw.isBlank()) return Body()
            return try {
                val obj = json.parseToJsonElement(raw).jsonObject
                Body(
                    message = obj["errors"]?.jsonObject?.values?.firstOrNull()?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                        ?: obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    code = obj["code"]?.jsonPrimitive?.contentOrNull,
                    orderId = obj["order_id"]?.jsonPrimitive?.longOrNull,
                    canForce = obj["can_force"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            } catch (_: Exception) {
                Body()
            }
        }
    }
}
