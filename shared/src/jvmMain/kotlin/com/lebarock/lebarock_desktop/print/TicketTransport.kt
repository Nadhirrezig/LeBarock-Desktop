package com.lebarock.lebarock_desktop.print

import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.PrinterConnection
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.print.DocFlavor
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.JobName

/** Where the ticket bytes go. A failure carries the French message the screens show. */
fun interface TicketTransport {
    /** @return a French sentence saying where the ticket went. */
    fun send(bytes: ByteArray): String

    companion object {
        fun of(config: PrinterConfig): TicketTransport = when (config.connection) {
            PrinterConnection.USB -> WindowsPrinterTransport(config.windowsPrinter)
            PrinterConnection.NETWORK -> NetworkTransport(config.host, config.port)
        }
    }
}

class PrintFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * USB (or any printer installed in Windows): the bytes go raw to the Windows spooler under the
 * printer's name, as ESC/POS (`DocFlavor.BYTE_ARRAY.AUTOSENSE`), so the printer's own driver is
 * only a pipe. Works with the maker's driver and with "Generic / Text Only".
 */
class WindowsPrinterTransport(private val printerName: String) : TicketTransport {
    override fun send(bytes: ByteArray): String {
        if (printerName.isBlank()) throw PrintFailure("Aucune imprimante choisie : Réglages → Imprimante de ce poste.")
        val service = PrintServiceLookup.lookupPrintServices(null, null).firstOrNull { it.name.equals(printerName, ignoreCase = true) }
            ?: throw PrintFailure("Imprimante « $printerName » introuvable dans Windows. Est-elle branchée et installée ?")
        try {
            val attributes = HashPrintRequestAttributeSet().apply { add(JobName("AltosPOS", null)) }
            service.createPrintJob().print(SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), attributes)
        } catch (e: Exception) {
            throw PrintFailure("Windows a refusé le ticket pour « $printerName » : ${e.message ?: e::class.java.simpleName}", e)
        }
        return "Ticket envoyé à « ${service.name} »."
    }

    companion object {
        /** The printers installed in Windows, default first. */
        fun names(): List<String> {
            val default = runCatching { PrintServiceLookup.lookupDefaultPrintService()?.name }.getOrNull()
            val all = runCatching { PrintServiceLookup.lookupPrintServices(null, null).map { it.name } }.getOrDefault(emptyList())
            return all.sortedWith(compareBy<String>({ it != default }, { it.lowercase() })).distinct()
        }
    }
}

/** Ethernet: the bytes straight to the printer's raw port (9100), as the caisse prints on its network printers. */
class NetworkTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 4_000,
    private val writeTimeoutMs: Int = 8_000,
) : TicketTransport {
    override fun send(bytes: ByteArray): String {
        if (host.isBlank()) throw PrintFailure("Aucune adresse d'imprimante : Réglages → Imprimante de ce poste.")
        val address = "$host:$port"
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = writeTimeoutMs
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
        } catch (e: UnknownHostException) {
            throw PrintFailure("Adresse d'imprimante introuvable : $host.", e)
        } catch (e: SocketTimeoutException) {
            throw PrintFailure("L'imprimante ne répond pas ($address). Est-elle allumée et sur le réseau ?", e)
        } catch (e: ConnectException) {
            throw PrintFailure("L'imprimante refuse la connexion ($address). Vérifiez son adresse IP et le port (9100).", e)
        } catch (e: NoRouteToHostException) {
            throw PrintFailure("Aucune route vers l'imprimante ($address) : autre réseau ?", e)
        } catch (e: IOException) {
            throw PrintFailure("Impression interrompue ($address) : ${e.message}", e)
        }
        return "Ticket imprimé sur $address."
    }
}
