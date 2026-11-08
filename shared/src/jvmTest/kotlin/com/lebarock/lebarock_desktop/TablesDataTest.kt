package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.ActiveOrderDto
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.CustomerRequest
import com.lebarock.lebarock_desktop.data.remote.OrderChargeDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrderItemDto
import com.lebarock.lebarock_desktop.data.remote.OrderRefDto
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.remote.TableSessionDto
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.session.Rights
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import com.lebarock.lebarock_desktop.ui.check.CheckAction
import com.lebarock.lebarock_desktop.ui.check.FloorState
import com.lebarock.lebarock_desktop.ui.check.canMergeInto
import com.lebarock.lebarock_desktop.ui.check.canMoveTo
import com.lebarock.lebarock_desktop.ui.tables.since
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D4: the floor reads like the web's (state, colours, who can take a check), the check's actions
 * follow the web's rights, and the table / check calls go to the caisse as it expects them.
 */
class TablesDataTest {

    // ── The floor ─────────────────────────────────────────────────────────

    private fun table(id: Long, status: String = "available", session: TableSessionDto? = null, open: Long? = null, openOrders: Int = 0) =
        TableDto(id = id, code = "$id", seats = 4, status = status, session = session,
            activeOrder = open?.let { ActiveOrderDto(id = it, orderNumber = "$it", total = 20.0) }, openOrders = openOrders)

    @Test
    fun a_tile_takes_the_web_floor_state() {
        assertEquals(FloorState.FREE, FloorState.of(table(1)))
        assertEquals(FloorState.BUSY, FloorState.of(table(2, "running", TableSessionDto(pax = 2), open = 7, openOrders = 1)))
        assertEquals(FloorState.BILL, FloorState.of(table(3, "running", TableSessionDto(pax = 2, billRequested = true), open = 8, openOrders = 1)))
        assertEquals(FloorState.RESERVED, FloorState.of(table(4, "reserved")))
        assertEquals("Addition demandée", FloorState.BILL.label(table(3)))
        val merged = table(5, "running", TableSessionDto(pax = 2, mergedInto = OrderRefDto(2, code = "2")))
        assertEquals("Avec 2", FloorState.of(merged).label(merged), "« Avec 2 », as the web tile")
    }

    @Test
    fun a_check_moves_to_a_free_table_and_merges_into_a_party() {
        val free = table(1)
        val openedNoOrder = table(2, "running", TableSessionDto(pax = 3))
        val busy = table(3, "running", TableSessionDto(pax = 2), open = 9, openOrders = 1)
        val merged = table(4, "running", TableSessionDto(pax = 2, mergedInto = OrderRefDto(3, code = "3")))
        val from = 6L

        assertTrue(canMoveTo(free, from))
        assertTrue(canMoveTo(openedNoOrder, from), "a party seated with nothing ordered: the check joins it")
        assertFalse(canMoveTo(busy, from), "an occupied table is a merge")
        assertFalse(canMoveTo(merged, from))
        assertFalse(canMoveTo(table(6), from), "not its own table")

        assertTrue(canMergeInto(busy, from))
        assertTrue(canMergeInto(openedNoOrder, from))
        assertFalse(canMergeInto(free, from), "no party to join")
        assertFalse(canMergeInto(merged, from))
    }

    @Test
    fun time_seated_reads_like_the_web() {
        val now = OffsetDateTime.parse("2026-11-08T20:30:00+01:00")
        assertEquals("25 min", since("2026-11-08T19:05:00Z", now))
        assertEquals("1 h 05", since("2026-11-08T18:25:00Z", now))
        assertEquals(null, since(null, now))
    }

    // ── The check's actions ───────────────────────────────────────────────

    private val waiter = Rights(orders = true, showOrders = true)
    private val manager = Rights(orders = true, showOrders = true, takePayment = true, cancelOrder = true, assignWaiter = true)

    private fun check(status: String = "kot", type: String = "dine_in", qty: Int = 2, review: Boolean = false, charge: Boolean = false) = OrderDetailDto(
        id = 42, orderNumber = "42", orderType = type, status = status,
        table = if (type == "dine_in") OrderRefDto(6, code = "6") else null,
        items = listOf(OrderItemDto(id = 1, name = "Couscous", quantity = qty, kotId = 3, stockState = "taken", fired = true)),
        needsReview = review,
        charges = if (charge) listOf(OrderChargeDto(id = 9, name = "Service", removable = status == "kot" || status == "billed")) else emptyList(),
    )

    @Test
    fun the_actions_follow_the_web_rights() {
        assertEquals(
            listOf(CheckAction.MOVE, CheckAction.MERGE, CheckAction.SPLIT, CheckAction.CUSTOMER),
            CheckAction.available(check(), waiter),
            "a waiter: no other waiter's name, no cancel",
        )
        assertEquals(
            listOf(CheckAction.MOVE, CheckAction.MERGE, CheckAction.SPLIT, CheckAction.WAITER, CheckAction.CUSTOMER, CheckAction.CHARGES, CheckAction.REVIEWED, CheckAction.CANCEL),
            CheckAction.available(check(review = true, charge = true), manager),
        )
        assertFalse(CheckAction.SPLIT in CheckAction.available(check(qty = 1), manager), "one article: nothing to split")
        assertEquals(listOf(CheckAction.WAITER, CheckAction.CUSTOMER, CheckAction.CANCEL), CheckAction.available(check(type = "pickup"), manager), "take-away: no table")
        assertEquals(listOf(CheckAction.CUSTOMER, CheckAction.CANCEL), CheckAction.available(check(status = "paid", charge = true), manager), "settled: its charges stay")
        assertEquals(emptyList(), CheckAction.available(check(status = "canceled"), manager))
    }

    @Test
    fun a_line_comes_off_by_its_state() {
        val order = check()
        val waiting = OrderItemDto(id = 2, name = "Coca", stockState = "waiting")
        val drink = OrderItemDto(id = 3, name = "Bière", stockState = "taken", fired = false)
        val cooking = OrderItemDto(id = 4, name = "Couscous", stockState = "taken", fired = true)

        assertTrue(CheckAction.canRemove(order, waiting, waiter), "not sent yet: anyone taking orders")
        assertFalse(CheckAction.canRemove(order, drink, waiter), "sent: the caisse's right")
        assertTrue(CheckAction.canRemove(order, drink, manager))
        assertFalse(CheckAction.canRemove(order, cooking, manager), "in a kitchen: through its kitchen ticket")
        assertFalse(CheckAction.canRemove(check(status = "paid"), drink, manager))
    }

    // ── Calls to the caisse ───────────────────────────────────────────────

    private val dir: File = Files.createTempDirectory("altospos-tables").toFile()
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val sessions = SessionHolder().apply { start(Session("t0ken", UserDto(id = 4, name = "Amel"))) }
    private val client = ApiClient({ sessions.token })
    private val server = MockWebServer().also { it.start() }
    private val orders = OrdersRepository(CaisseRepository(client, settings, sessions), settings, TicketPrinter(settings) { TicketTransport { "ok" } }, client.json)

    init {
        settings.update { it.copy(server = ServerAddress(false, server.hostName, server.port)) }
    }

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val detail = """{"id":42,"order_number":"42","order_type":"dine_in","status":"kot","total":40,"table":{"id":7,"code":"7"},
        "items":[{"id":1,"name":"Couscous","quantity":2,"amount":40,"kot_id":3,"stock_state":"taken","fired":true}],
        "charges":[{"id":9,"name":"Service","type":"percent","value":10,"amount":4,"removable":true}],
        "payments":[{"id":5,"method":"cash","amount":10,"balance":0}],
        "customer_info":{"id":3,"name":"Leila","phone":"22111333","email":null}}"""

    @Test
    fun the_floor_reads_what_the_web_tile_shows() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","data":[{"id":1,"name":"Salle","tables":[{"id":6,"code":"5","seats":4,"status":"running",
            "session":{"opened_at":"2026-11-08T19:00:00+01:00","pax":3,"bill_requested":false,"merged_into":null},"pending_qr_rounds":1,
            "active_order":{"id":42,"order_number":"42","status":"kot","total":40,"kot_count":2,"items_count":3},
            "open_orders":2,"open_total":55.5,"ready_tickets":1,"stock_waiting":true,
            "next_reservation":{"at":"2026-11-08T21:00:00+01:00","name":"Sami","party_size":4}}]}]}"""))

        val t = orders.tables().getOrThrow().single().tables.single()
        assertEquals(2, t.openOrders)
        assertEquals(55.5, t.openTotal)
        assertEquals(1, t.readyTickets)
        assertTrue(t.stockWaiting)
        assertEquals(4, t.nextReservation!!.partySize)
        assertEquals(3, t.session!!.pax)
    }

    @Test
    fun a_check_operation_answers_the_check_and_the_caisse_message() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","message":"La commande est maintenant sur la table 7.","data":$detail}"""))
        val moved = orders.moveOrder(42, 7).getOrThrow()
        assertEquals("La commande est maintenant sur la table 7.", moved.message)
        assertEquals(7L, moved.order.table!!.id)
        assertEquals("Service", moved.order.charges.single().name)
        assertEquals("cash", moved.order.payments.single().method)
        assertEquals("Leila", moved.order.customerInfo!!.name)
        val request = server.takeRequest()
        assertEquals("/api/mobile/orders/42/table", request.url.encodedPath)
        assertEquals("7", Json.parseToJsonElement(request.body!!.utf8()).jsonObject["table_id"].toString())

        server.enqueue(json("""{"status":"success","message":"Nouveau ticket #43 créé (12.000 DT).","data":$detail,"new_order":{"id":43,"order_number":"43","total":12}}"""))
        val split = orders.splitOrder(42, mapOf(1L to 1)).getOrThrow()
        assertEquals("43", split.newOrder!!.orderNumber)
        assertEquals("""[{"id":1,"quantity":1}]""", Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["lines"].toString())

        server.enqueue(json("""{"status":"success","message":"Client : Leila.","data":$detail}"""))
        orders.setCustomer(42, CustomerRequest(customerId = 3, name = "Leila", phone = "22111333")).getOrThrow()
        val customer = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("3", customer["customer_id"].toString())
        assertEquals("Leila", customer["name"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"status":"success","message":"Paiement corrigé : carte.","data":$detail}"""))
        orders.changePaymentMethod(42, 5, "card").getOrThrow()
        val fix = server.takeRequest()
        assertEquals("/api/mobile/orders/42/payments/5/method", fix.url.encodedPath)
        assertEquals("card", Json.parseToJsonElement(fix.body!!.utf8()).jsonObject["method"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"status":"success","message":"Table 5 libérée."}"""))
        assertEquals("Table 5 libérée.", orders.releaseTable(6).getOrThrow())
        assertEquals("/api/mobile/tables/6/release", server.takeRequest().url.encodedPath)
    }

    @Test
    fun a_refusal_says_what_the_caisse_says() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"error","message":"La table 3 a déjà un ticket en cours : utilisez « Fusionner les tables »."}""", 422))
        val refused = orders.moveOrder(42, 3).exceptionOrNull() as ApiFailure
        assertEquals("La table 3 a déjà un ticket en cours : utilisez « Fusionner les tables ».", refused.message)

        server.enqueue(json("""{"status":"error","message":"Ce compte n'a pas le droit de faire cela."}""", 403))
        assertTrue(orders.setWaiter(42, 9).isFailure)
    }
}
