package com.lebarock.lebarock_desktop.data.remote

import com.lebarock.lebarock_desktop.AppInfo
import com.lebarock.lebarock_desktop.data.AppLog
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Which certificates the app trusts for the caisse's HTTPS (mkcert certificate on the LAN).
 * Java does not read the Windows certificate store by default, so the app trusts, in order:
 *  1. Java's own list (public certificates);
 *  2. the Windows store ("Windows-ROOT"): mkcert's root CA once installed in Windows
 *     (double-click rootCA.pem → Autorités de certification racines de confiance);
 *  3. root certificates imported in Réglages → Certificat (copied into `certs\` of the data folder),
 *     for a PC where nobody installed it in Windows.
 */
object TrustStore {

    /** A root certificate imported in Réglages, as the screen shows it. */
    data class Imported(val file: File, val subject: String, val validUntil: String)

    fun trustManager(certsDir: File = AppInfo.certsDir): X509TrustManager {
        val managers = buildList {
            add(managerFor(null))
            if (AppInfo.isWindows) {
                runCatching {
                    KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
                }.onSuccess { add(managerFor(it)) }
                    .onFailure { AppLog.warn("Magasin de certificats Windows illisible", it) }
            }
            importedStore(certsDir)?.let { add(managerFor(it)) }
        }
        return CompositeTrustManager(managers)
    }

    fun sslContext(manager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }

    /** The certificates imported in Réglages. */
    fun imported(certsDir: File = AppInfo.certsDir): List<Imported> =
        certsDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("pem", "crt", "cer") }
            .orEmpty().sortedBy { it.name }
            .mapNotNull { file -> readCertificates(file).firstOrNull()?.let { describe(file, it) } }

    /**
     * Copies a root certificate (rootCA.pem) into the data folder after checking it is one.
     * Returns what was imported, or fails with a French message.
     */
    fun import(source: File, certsDir: File = AppInfo.certsDir): Result<Imported> = runCatching {
        val certs = try {
            readCertificates(source)
        } catch (e: Exception) {
            throw IllegalArgumentException("Ce fichier n'est pas un certificat (choisissez rootCA.pem).", e)
        }
        val cert = certs.firstOrNull() ?: throw IllegalArgumentException("Aucun certificat dans ce fichier.")
        require(cert.basicConstraints >= 0) {
            "Ce certificat n'est pas un certificat racine (autorité). Choisissez rootCA.pem, pas le certificat du serveur."
        }
        cert.checkValidity()
        val target = File(certsDir, "root-${cert.serialNumber.toString(16)}.pem")
        target.writeText(toPem(cert), Charsets.US_ASCII)
        AppLog.info("Certificat racine importé : ${cert.subjectX500Principal.name}")
        describe(target, cert)
    }

    fun remove(imported: Imported) {
        imported.file.delete()
        AppLog.info("Certificat racine retiré : ${imported.subject}")
    }

    private fun managerFor(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun importedStore(certsDir: File): KeyStore? {
        val certs = certsDir.listFiles { f -> f.isFile }.orEmpty().flatMap { file ->
            runCatching { readCertificates(file) }.getOrDefault(emptyList())
        }
        if (certs.isEmpty()) return null
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, cert -> setCertificateEntry("imported-$i", cert) }
        }
    }

    private fun readCertificates(file: File): List<X509Certificate> =
        file.inputStream().buffered().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).filterIsInstance<X509Certificate>()
        }

    private fun describe(file: File, cert: X509Certificate): Imported {
        val subject = cert.subjectX500Principal.name.split(',')
            .firstOrNull { it.trim().startsWith("CN=") }?.substringAfter("CN=") ?: cert.subjectX500Principal.name
        return Imported(file, subject, SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(cert.notAfter))
    }

    private fun toPem(cert: X509Certificate): String {
        val body = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    /** Trusts a server when any of the lists does; the last refusal is the one reported. */
    private class CompositeTrustManager(private val managers: List<X509TrustManager>) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            var last: CertificateException? = null
            for (manager in managers) {
                try {
                    manager.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("Aucun certificat de confiance")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            managers.first().checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            managers.flatMap { it.acceptedIssuers.asList() }.distinctBy { it.serialNumber to it.issuerX500Principal }.toTypedArray()
    }
}
