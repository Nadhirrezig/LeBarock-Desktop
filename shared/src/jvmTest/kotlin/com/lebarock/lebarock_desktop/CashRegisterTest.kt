package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.CashRegisterRepository
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.ExpenseRequest
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.pos.CashCount
import com.lebarock.lebarock_desktop.pos.CounterDate
import com.lebarock.lebarock_desktop.pos.parseAmount
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D6: the Z's count by value, the amounts and dates typed, and the Caisse's calls as the caisse expects them. */
class CashRegisterTest {

    @Test
    fun the_z_counts_by_value_and_shows_the_difference() {
        var c = CashCount(listOf(50, 20, 10, 5))
        c = c.plus(50).plus(20).plus(20).plus(5).plus(7)
        assertEquals(95.0, c.total, "1 × 50 + 2 × 20 + 1 × 5; a value not in the settings is ignored")
        assertEquals(-5.0, c.difference(100.0))
        assertEquals(mapOf(50 to 1, 20 to 2, 5 to 1), c.breakdown)
        c = c.minus(5).minus(5).minus(10)
        assertEquals(0, c.count(5), "never below 0")
        c = c.set(10, "12").set(20, "")
        assertEquals(170.0, c.total)
        assertEquals(mapOf(50 to 1, 10 to 12), c.breakdown, "only what was counted is sent")
    }

    @Test
    fun the_z_adds_the_rest_typed_for_the_coins() {
        var c = CashCount(listOf(50, 20)).plus(50).withRest("3,450 DT")
        assertEquals("3,450", c.rest, "digits and the separator only")
        assertEquals(3.45, c.restAmount)
        assertEquals(53.45, c.total)
        assertEquals(-0.05, c.difference(53.5))
        assertEquals(mapOf(50 to 1), c.breakdown, "the rest is not a value")
        c = c.withRest("")
        assertEquals(0.0, c.restAmount, "empty = nothing besides the values")
        assertEquals(50.0, c.total)
        c = c.withRest("1,2,3")
        assertNull(c.restAmount, "not an amount: asked again before the Z")
        assertEquals(50.0, c.total)
        assertEquals(7.5, CashCount(emptyList()).withRest("7.5").total, "no values set: the whole count can be typed")
    }

    @Test
    fun amounts_and_dates_read_as_typed_at_the_counter() {
        assertEquals(12.5, parseAmount("12,5"))
        assertEquals(12.5, parseAmount(" 12.5 "))
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
        assertEquals("2026-11-10", CounterDate.toIso("10/11/2026"))
        assertNull(CounterDate.toIso("2026-11-10"))
        assertEquals(LocalDate.now().toString(), CounterDate.toIso(CounterDate.today()))
    }

    // ── Calls to the caisse ───────────────────────────────────────────────

    private val dir: File = Files.createTempDirectory("altospos-cash").toFile()
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val sessions = SessionHolder().apply { start(Session("t0ken", UserDto(id = 4, name = "Amel"))) }
    private val client = ApiClient({ sessions.token })
    private val server = MockWebServer().also { it.start() }
    private val printed = mutableListOf<ByteArray>()
    private val cash = CashRegisterRepository(
        CaisseRepository(client, settings, sessions), settings,
        TicketPrinter(settings) { TicketTransport { bytes -> printed += bytes; "Ticket envoyé à « EPSON »." } }, client.json,
    )

    init {
        settings.update { it.copy(server = ServerAddress(false, server.hostName, server.port), printer = PrinterConfig(target = PrintTarget.DEVICE, windowsPrinter = "EPSON")) }
    }

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val state = """{"enabled":true,"enforce_session":true,"caisse":{"id":1,"name":"Caisse"},
        "session":{"id":7,"code":"AB12-3","z_number":3,"status":"active","opened_by":"Hela","opening_cash":100,"expected_cash":80},
        "kpi":{"opening_cash":100,"sales_cash":0,"cash_in":30,"safe_drops":50,"expected_cash":80,"transactions":2},
        "transactions":[{"id":2,"type":"safe_drop","label":"Prélèvement coffre","amount":50,"decreases":true,"reason":"Coffre"}],
        "count_values":[50,20,10,5],"offline":{"devices":[],"waiting":{"count":0,"amount":0}}}"""

    private val ticket = """{"restaurant":{"name":"Le Baroque"},"order":{"number":"RAPPORT Z N°3","dateTime":"10 nov. 2026 23:10"},
        "summary":[{"label":"Espèces","value":"95.000"}],"total":{"label":"TOTAL TTC","value":"95.000"}}"""

    @Test
    fun the_session_calls_carry_what_the_web_form_sends() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","message":"Session de caisse ouverte.","data":$state}"""))
        val opened = cash.open(100.0).getOrThrow()
        assertEquals("Session de caisse ouverte.", opened.message)
        assertEquals("AB12-3", opened.state.session!!.code)
        assertEquals("100.0", Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["opening_cash"].toString())

        server.enqueue(json("""{"status":"success","message":"Prélèvement coffre enregistré.","data":$state}"""))
        cash.movement("safe_drop", 50.0, " Coffre ").getOrThrow()
        val move = server.takeRequest()
        assertEquals("/api/mobile/cash-register/movements", move.url.encodedPath)
        val body = Json.parseToJsonElement(move.body!!.utf8()).jsonObject
        assertEquals("safe_drop", body["type"]!!.jsonPrimitive.content)
        assertEquals("Coffre", body["reason"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"status":"error","message":"Impossible de sortir plus d'espèces que le tiroir n'en contient : demandé 500.000 DT, disponible 80.000 DT."}""", 422))
        val refused = cash.movement("cash_out", 500.0, "Banque").exceptionOrNull() as ApiFailure
        assertTrue(refused.message!!.startsWith("Impossible de sortir plus d'espèces"))
    }

    @Test
    fun the_z_sends_the_count_and_prints_its_ticket_here() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","message":"Session fermée","data":{"id":9,"type":"z","number":"Z-3","total":95,"ticket":$ticket,
            "session":{"id":7,"code":"AB12-3","status":"pending_approval","expected_cash":100,"counted_cash":95,"discrepancy":-5},
            "stock":{"ok":true,"till_linked":true,"department":"Bar","lines":[{"name":"Coca","unit":"pcs","sold":12,"left":3,"to_refill":9}]}},
            "print":{"type":"sunmi","url":"http://localhost:9100/print","token":"","receipt":$ticket},"print_result":{"target":"device","status":"device"}}"""))
        val z = cash.close(CashCount(listOf(50, 20, 5)).plus(50).plus(20).plus(20).plus(5).breakdown, 2.5, " Fin ").getOrThrow()
        assertEquals("Z-3", z.value.number)
        assertEquals(-5.0, z.value.session!!.discrepancy)
        assertEquals(9.0, z.value.stock!!.lines.single().toRefill)
        assertTrue(z.print!!.ok)
        val paper = String(printed.single(), Charsets.ISO_8859_1)
        assertTrue(paper.contains("RAPPORT Z"), "the Z laid out and printed here")
        assertTrue("Ticket #RAPPORT" !in paper, "an X / Z keeps its title, no « Ticket # »")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("""{"50":1,"20":2,"5":1}""", body["breakdown"].toString())
        assertEquals(2.5, body["rest"]!!.jsonPrimitive.content.toDouble(), "the coins, as an amount")
        assertEquals("Fin", body["notes"]!!.jsonPrimitive.content)
        assertEquals("device", body["print_target"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"status":"success","message":"Demande envoyée au stock : Bar.","data":{"ok":true,"status":"SENT","message":"Demande envoyée au stock : Bar."}}"""))
        assertEquals("Demande envoyée au stock : Bar.", cash.stockRequest(9).getOrThrow())
        assertEquals("/api/mobile/cash-register/reports/9/stock-request", server.takeRequest().url.encodedPath)
    }

    @Test
    fun an_expense_goes_with_the_web_forms_fields() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","date":"2026-11-10","total":12,"categories":[{"id":3,"name":"Courses"}],
            "methods":[{"value":"cash","label":"Espèces"}],"data":[{"id":1,"title":"Pain","category":"Courses","amount":12,"status":"paid","method":"cash","method_label":"Espèces","from_drawer":true}]}"""))
        val list = cash.expenses().getOrThrow()
        assertEquals("Courses", list.categories.single().name)
        assertTrue(list.data.single().fromDrawer)
        server.takeRequest()

        server.enqueue(json("""{"status":"success","message":"Dépense enregistrée : sortie du tiroir de la caisse.","data":{"id":2,"title":"Glaçons","amount":8,"status":"paid","from_drawer":true}}"""))
        val (_, message) = cash.addExpense(ExpenseRequest("Glaçons", 3, 8.0, "2026-11-10", "paid", "cash")).getOrThrow()
        assertEquals("Dépense enregistrée : sortie du tiroir de la caisse.", message)
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("Glaçons", body["expense_title"]!!.jsonPrimitive.content)
        assertEquals("3", body["expense_category_id"].toString())
        assertEquals("paid", body["payment_status"]!!.jsonPrimitive.content)
        assertEquals("cash", body["payment_method"]!!.jsonPrimitive.content)
    }
}
