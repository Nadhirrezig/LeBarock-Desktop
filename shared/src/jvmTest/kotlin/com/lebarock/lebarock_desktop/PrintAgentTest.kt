package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.settings.AgentConfig
import com.lebarock.lebarock_desktop.data.settings.AgentPrinter
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.AgentClient
import com.lebarock.lebarock_desktop.print.AgentState
import com.lebarock.lebarock_desktop.print.EscPosEncoder
import com.lebarock.lebarock_desktop.print.PrintAgent
import com.lebarock.lebarock_desktop.print.PrintFailure
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Impression « Directe » (D2): the counter PC collects the tickets the caisse queued for its
 * printer, prints them and reports back, against a local server answering like the caisse.
 */
class PrintAgentTest {

    private val dir: File = Files.createTempDirectory("altospos-agent").toFile()
    private val secrets = SecretBox(dir, windows = false)
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val printed = Collections.synchronizedList(mutableListOf<ByteArray>())
    private var printerFails = false

    private val printer = TicketPrinter(settings) {
        TicketTransport { bytes ->
            if (printerFails) throw PrintFailure("Imprimante « EPSON » introuvable dans Windows.")
            printed += bytes
            "Ticket envoyé à « EPSON »."
        }
    }
    private val agent = PrintAgent(settings, secrets, printer, AgentClient { OkHttpClient() }, pollMillis = 50, retryMillis = 100)

    @AfterTest
    fun stop() {
        scope.cancel()
        runCatching { server.close() }
    }

    private fun configure(enabled: Boolean = true) {
        settings.update {
            it.copy(
                server = ServerAddress(false, server.hostName, server.port),
                printer = PrinterConfig(windowsPrinter = "EPSON TM-T20III"),
                agent = AgentConfig(
                    enabled = enabled,
                    keySealed = secrets.seal("branch-key-20"),
                    branchName = "Le Baroque",
                    printerId = 7,
                    printers = listOf(AgentPrinter(7, "Comptoir USB", "directPrint")),
                ),
            )
        }
    }

    private fun job(id: Long, bytes: ByteArray, withBase64: Boolean = true) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json")
        .body(
            if (withBase64) {
                """{"id":$id,"status":"printing","printer_id":7,"payload":{"text":"x","base64":"${Base64.getEncoder().encodeToString(bytes)}","cutPaper":true}}"""
            } else {
                """{"id":$id,"status":"printing","printer_id":7,"payload":{"text":"${String(bytes, Charsets.ISO_8859_1)}","cutPaper":false}}"""
            },
        ).build()

    private fun noJob() = MockResponse.Builder().code(204).build()
    private fun ok() = MockResponse.Builder().code(200).body("""{"status":"success"}""").build()

    private fun waitFor(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what (status ${agent.status.value})")
            Thread.sleep(20)
        }
    }

    @Test
    fun a_queued_ticket_is_printed_with_its_exact_bytes_and_reported_done() {
        val bytes = "\u001b@CAF\u0082 Nø 1\n".toByteArray(Charsets.ISO_8859_1) + EscPosEncoder.CUT
        server.enqueue(job(41, bytes))
        server.enqueue(ok())
        repeat(20) { server.enqueue(noJob()) }
        configure()
        agent.start(scope)

        waitFor("the ticket") { printed.isNotEmpty() }
        assertContentEquals(bytes, printed.first(), "the caisse's bytes, untouched (they already cut)")

        val pull = server.takeRequest()
        assertEquals("/api/print-jobs/pull", pull.url.encodedPath)
        assertEquals("7", pull.url.queryParameter("printer_id"))
        assertEquals("branch-key-20", pull.headers["X-AltosPos-KEY"])
        assertNull(pull.headers["Authorization"], "never a signed-in account's token")

        val report = server.takeRequest()
        assertEquals("PATCH", report.method)
        assertEquals("/api/print-jobs/41", report.url.encodedPath)
        val body = Json.parseToJsonElement(report.body!!.utf8()).jsonObject
        assertEquals("\"done\"", body["status"].toString())
        assertTrue(body["printer"].toString().contains("EPSON TM-T20III"))
        waitFor("the count") { agent.status.value.printedCount == 1 }
        assertEquals(AgentState.WAITING, agent.status.value.state)
        assertNotNull(agent.status.value.lastPrintedAt)
    }

    @Test
    fun an_older_caisse_job_without_base64_still_prints() {
        val job = AgentClient.parseJob(Json.parseToJsonElement("""{"id":5,"payload":{"text":"Café\n","cutPaper":true}}""").jsonObject)
        assertEquals(5L, job.id)
        assertContentEquals("Café\n".toByteArray(Charsets.ISO_8859_1), job.bytes)
        assertTrue(job.cut)
    }

    @Test
    fun a_printer_failure_is_reported_failed_and_shown() {
        printerFails = true
        server.enqueue(job(42, "TICKET\n".toByteArray()))
        server.enqueue(ok())
        repeat(20) { server.enqueue(noJob()) }
        configure()
        agent.start(scope)

        waitFor("the error") { agent.status.value.state == AgentState.PRINTER_ERROR }
        assertTrue(agent.status.value.message.contains("Ticket #42 non imprimé"))
        server.takeRequest()
        val report = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"failed\"", report["status"].toString())
        assertTrue(report["error"].toString().contains("introuvable"))
    }

    @Test
    fun a_refused_key_stops_the_agent_until_it_is_set_up_again() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"message":"Invalid authentication key"}""").build())
        configure()
        agent.start(scope)
        waitFor("the refusal") { agent.status.value.state == AgentState.KEY_REFUSED }
        Thread.sleep(300)
        assertEquals(1, server.requestCount, "no more calls with a refused key")
        assertTrue(printed.isEmpty())
    }

    @Test
    fun a_caisse_that_does_not_answer_is_tried_again() {
        configure()
        server.close()
        agent.start(scope)
        waitFor("the retry state") { agent.status.value.state == AgentState.CAISSE_DOWN }
        assertTrue(agent.status.value.message.contains("Caisse injoignable"))
    }

    @Test
    fun switched_off_or_without_a_printer_it_does_nothing() {
        configure(enabled = false)
        agent.start(scope)
        Thread.sleep(200)
        assertEquals(AgentState.OFF, agent.status.value.state)
        assertEquals(0, server.requestCount)

        settings.update { it.copy(agent = it.agent.copy(enabled = true, printerId = null)) }
        waitFor("not set") { agent.status.value.state == AgentState.NOT_SET }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun the_key_is_kept_sealed() {
        val sealed = secrets.seal("branch-key-20")
        assertFalse(sealed.contains("branch-key-20"))
        assertEquals("branch-key-20", secrets.open(sealed))
        assertNull(secrets.open("aes:bm9wZQ==:bm9wZQ=="), "damaged: unreadable, no crash")
        assertNull(SecretBox(Files.createTempDirectory("other-pc").toFile(), windows = false).open(sealed), "another PC's key file cannot open it")
        configure()
        assertFalse(File(dir, "settings.json").readText().contains("branch-key-20"))
    }
}
