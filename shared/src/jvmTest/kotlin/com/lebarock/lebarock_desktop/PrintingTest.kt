package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.PrinterConnection
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.EscPosEncoder
import com.lebarock.lebarock_desktop.print.NetworkTransport
import com.lebarock.lebarock_desktop.print.PrintFailure
import com.lebarock.lebarock_desktop.print.PrintOp
import com.lebarock.lebarock_desktop.print.ReceiptFooter
import com.lebarock.lebarock_desktop.print.ReceiptItem
import com.lebarock.lebarock_desktop.print.ReceiptOrder
import com.lebarock.lebarock_desktop.print.ReceiptPayload
import com.lebarock.lebarock_desktop.print.ReceiptPayment
import com.lebarock.lebarock_desktop.print.ReceiptRemaining
import com.lebarock.lebarock_desktop.print.ReceiptRenderer
import com.lebarock.lebarock_desktop.print.ReceiptRestaurant
import com.lebarock.lebarock_desktop.print.ReceiptRow
import com.lebarock.lebarock_desktop.print.TicketCharset
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The counter's ticket printing (D2): layout, ESC/POS bytes, Ethernet, one ticket at a time. */
class PrintingTest {

    private fun ByteArray.indexOf(part: ByteArray): Int =
        (0..size - part.size).firstOrNull { i -> part.indices.all { this[i + it] == part[it] } } ?: -1

    private fun ByteArray.contains(part: ByteArray) = indexOf(part) >= 0

    // ── ESC/POS ────────────────────────────────────────────────────────────

    @Test
    fun accents_print_in_the_chosen_code_page() {
        val cp858 = EscPosEncoder(TicketCharset.CP858)
        val bytes = cp858.encode(listOf(PrintOp.Text("Café 8.630 €")))
        assertContentEquals(byteArrayOf(0x1B, '@'.code.toByte()), bytes.copyOfRange(0, 2), "initialise first")
        assertTrue(bytes.contains(byteArrayOf(0x1B, 't'.code.toByte(), 19)), "PC858 selected")
        assertContentEquals(byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0x82.toByte()), cp858.text("Café"))
        assertContentEquals(byteArrayOf(0xD5.toByte()), cp858.text("€"))

        val cp1252 = EscPosEncoder(TicketCharset.CP1252)
        assertTrue(cp1252.encode(emptyList()).contains(byteArrayOf(0x1B, 't'.code.toByte(), 16)))
        assertContentEquals(byteArrayOf(0xE9.toByte()), cp1252.text("é"))

        val ascii = EscPosEncoder(TicketCharset.ASCII)
        assertFalse(ascii.encode(emptyList()).contains(byteArrayOf(0x1B, 't'.code.toByte())))
        assertEquals("Creme brulee a Noel", String(ascii.text("Crème brûlée à Noël"), Charsets.US_ASCII))
    }

    @Test
    fun characters_a_printer_lacks_are_written_plainly() {
        val enc = EscPosEncoder(TicketCharset.CP858)
        // The caisse's formats can use narrow spaces, dashes and French quotes.
        assertEquals("1 234.630 DT - \"OK\" ...", String(enc.text("1 234.630 DT — «OK» …"), Charsets.ISO_8859_1))
        // Something with no place in the code page: its letter without the accent, else "?".
        assertEquals("S?", String(enc.text("Ş✓"), Charsets.ISO_8859_1).replace('Ş', 'S'))
    }

    @Test
    fun styles_are_switched_only_when_they_change_and_reset_at_the_end() {
        val bytes = EscPosEncoder().encode(
            listOf(
                PrintOp.Text("A", bold = true, big = true),
                PrintOp.Text("B", bold = true, big = true),
                PrintOp.Feed(2),
                PrintOp.Cut,
            ),
        )
        val boldOn = byteArrayOf(0x1B, 'E'.code.toByte(), 1)
        assertEquals(bytes.indexOf(boldOn), (bytes.size - 1 downTo 0).first { i -> i + 2 < bytes.size && bytes[i] == 0x1B.toByte() && bytes[i + 1] == 'E'.code.toByte() && bytes[i + 2] == 1.toByte() })
        assertTrue(bytes.contains(byteArrayOf(0x1D, '!'.code.toByte(), 0x11)))
        assertTrue(bytes.contains(byteArrayOf(0x1B, 'd'.code.toByte(), 2)))
        assertTrue(bytes.contains(EscPosEncoder.CUT))
        assertTrue(bytes.contains(byteArrayOf(0x1B, 'E'.code.toByte(), 0)), "bold off again")
        assertTrue(bytes.contains(byteArrayOf(0x1D, '!'.code.toByte(), 0)), "normal size again")
    }

    @Test
    fun a_qr_code_carries_its_length() {
        val bytes = EscPosEncoder().encode(listOf(PrintOp.Qr("https://caisse/pay/42")))
        val store = byteArrayOf(0x1D, '('.code.toByte(), 'k'.code.toByte(), (21 + 3).toByte(), 0, 49, 80, 48)
        assertTrue(bytes.contains(store + "https://caisse/pay/42".toByteArray()))
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    /** A bill as the caisse sends it (SunmiReceiptBuilder), partly paid. */
    private val bill = ReceiptPayload(
        restaurant = ReceiptRestaurant("Le Baroque", listOf("Avenue Habib Bourguiba, Tunis", "Tél: 71 000 000")),
        order = ReceiptOrder("42", "08 nov. 2026 21:05"),
        details = listOf(ReceiptRow("Table", "T12"), ReceiptRow("Serveur", "Karim Ben Ali")),
        items = listOf(
            ReceiptItem("2", "Couscous filet de daurade royale aux légumes du marché", "38.000", "76.000"),
            ReceiptItem("1", "Absolut", "", "12.500", variation = "5 cl"),
            ReceiptItem("3", "Café", "3.500", "10.500", modifiers = listOf("Sans sucre")),
        ),
        summary = listOf(ReceiptRow("Sous-total", "99.000")),
        total = ReceiptRow("Total", "99.000 DT"),
        footer = ReceiptFooter(
            message = "Merci de votre visite !",
            payments = listOf(ReceiptPayment("50.000", "Espèces")),
            change = null,
            remainingDue = ReceiptRemaining("Reste dû", "49.000"),
        ),
    )

    @Test
    fun no_line_is_wider_than_the_paper() {
        for (mm in listOf(58, 80)) {
            val cols = ReceiptRenderer.columnsFor(mm)
            val ops = ReceiptRenderer(cols).render(bill)
            ops.filterIsInstance<PrintOp.Text>().forEach { t ->
                val max = if (t.big) cols / 2 else cols
                assertTrue(t.text.length <= max, "$mm mm: «${t.text}» (${t.text.length} > $max)")
            }
        }
    }

    @Test
    fun the_bill_reads_like_the_caisse_ticket() {
        val lines = ReceiptRenderer(48).render(bill).filterIsInstance<PrintOp.Text>().map { it.text.trimEnd() }
        assertTrue(lines.any { it.startsWith("Ticket #42") && it.endsWith("08 nov. 2026 21:05") })
        assertTrue(lines.any { it.startsWith("1x Absolut") && it.endsWith("12.500") })
        assertTrue(lines.any { it.trim() == "@ 38.000" }, "unit price when more than one")
        assertTrue(lines.any { it.contains("• 5 cl") } && lines.any { it.contains("- Sans sucre") })
        assertTrue(lines.any { it.startsWith("Payé · Espèces") && it.endsWith("50.000") })
        assertTrue(lines.any { it.startsWith("Reste dû") && it.endsWith("49.000") }, "the partly paid check says what is left")
        val total = ReceiptRenderer(48).render(bill).filterIsInstance<PrintOp.Text>().first { it.text.startsWith("TOTAL") }
        assertTrue(total.big && total.bold)
    }

    @Test
    fun the_x_and_z_print_through_the_same_layout() {
        // SunmiReportBuilder: a summary row with no value is a section title.
        val report = json.decodeFromString(
            ReceiptPayload.serializer(),
            """{"restaurant":{"name":"Le Baroque","lines":[]},"order":{"number":"Z-12","dateTime":"08/11/2026 23:59"},
               "details":[{"label":"Caisse","value":"Caisse"}],"items":[],
               "summary":[{"label":"— VENTES —","value":""},{"label":"CA net TTC","value":"1 234.630 DT"}],
               "total":{"label":"TOTAL TTC","value":"1 234.630 DT"},
               "footer":{"message":"Clôture définitive — à conserver (NF525). Ticket résumé : détail sur la caisse.","payments":[]}}""",
        )
        val ops = ReceiptRenderer(48).render(report).filterIsInstance<PrintOp.Text>()
        assertTrue(ops.first { it.text == "— VENTES —" }.bold)
        assertTrue(ops.any { it.text.startsWith("CA net TTC") && it.text.endsWith("1 234.630 DT") })
        assertTrue(ops.filter { it.text.isNotBlank() }.all { it.text.length <= 48 })
    }

    @Test
    fun the_test_ticket_has_a_line_from_edge_to_edge() {
        for (cols in listOf(32, 48)) {
            val ops = ReceiptRenderer(cols).renderTest("Le Baroque", listOf("Imprimante" to "EPSON (USB)"))
            assertTrue(ops.filterIsInstance<PrintOp.Text>().any { it.text.length == cols && it.text.endsWith("|") })
            assertEquals(PrintOp.Cut, ops.last())
        }
    }

    // ── Sending ────────────────────────────────────────────────────────────

    private fun store(config: PrinterConfig): SettingsStore =
        SettingsStore(File(Files.createTempDirectory("altospos-print").toFile(), "settings.json")).also { s -> s.update { it.copy(printer = config) } }

    @Test
    fun an_ethernet_printer_receives_the_ticket_on_its_port() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val received = ByteArrayOutputStream()
            val reader = thread { server.accept().use { it.getInputStream().copyTo(received) } }
            val printer = TicketPrinter(store(PrinterConfig(connection = PrinterConnection.NETWORK, host = "127.0.0.1", port = server.localPort)))
            val result = printer.printReceipt(bill)
            reader.join(5_000)
            assertEquals("Ticket imprimé sur 127.0.0.1:${server.localPort}.", result.getOrThrow())
            val bytes = received.toByteArray()
            assertTrue(bytes.contains("Le Baroque".toByteArray()))
            assertTrue(bytes.contains(EscPosEncoder.CUT))
        }
    }

    @Test
    fun a_printer_that_does_not_answer_says_so() {
        val port = ServerSocket(0).use { it.localPort }
        val failure = runCatching { NetworkTransport("127.0.0.1", port).send(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(failure is PrintFailure && failure.message!!.contains("refuse la connexion"), failure?.message)
    }

    @Test
    fun nothing_is_sent_before_the_printer_is_chosen() = runBlocking<Unit> {
        var sent = 0
        val printer = TicketPrinter(store(PrinterConfig(connection = PrinterConnection.USB, windowsPrinter = ""))) { TicketTransport { sent++; "ok" } }
        val result = printer.printTest("Le Baroque")
        assertEquals("Choisissez l'imprimante Windows du comptoir.", result.exceptionOrNull()?.message)
        assertEquals(0, sent)
    }

    @Test
    fun a_caisse_job_gets_its_cut_once_and_tickets_never_mix() = runBlocking<Unit> {
        val sent = Collections.synchronizedList(mutableListOf<ByteArray>())
        val inside = java.util.concurrent.atomic.AtomicInteger()
        var overlapped = false
        val printer = TicketPrinter(store(PrinterConfig(windowsPrinter = "EPSON TM-T20III"))) {
            TicketTransport { bytes ->
                if (inside.incrementAndGet() > 1) overlapped = true
                Thread.sleep(30)
                sent += bytes
                inside.decrementAndGet()
                "ok"
            }
        }
        printer.printRaw("TICKET\n".toByteArray(), cut = true).getOrThrow()
        assertTrue(sent.last().contains(EscPosEncoder.CUT), "a cut added when the job asks and has none")
        printer.printRaw("TICKET\n".toByteArray() + EscPosEncoder.CUT, cut = true).getOrThrow()
        assertEquals(1, (0 until sent.last().size - 1).count { sent.last()[it] == 0x1D.toByte() && sent.last()[it + 1] == 'V'.code.toByte() })

        (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { printer.printRaw("T$it".toByteArray(), cut = false) } }.awaitAll()
        assertFalse(overlapped, "one ticket at a time")
    }
}
