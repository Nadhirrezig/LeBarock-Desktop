package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.offline.ConnectionMonitor
import com.lebarock.lebarock_desktop.data.offline.DeviceStateEntity
import com.lebarock.lebarock_desktop.data.offline.OfflineOrders
import com.lebarock.lebarock_desktop.data.offline.OfflinePins
import com.lebarock.lebarock_desktop.data.offline.OfflineStore
import com.lebarock.lebarock_desktop.data.offline.OfflineSync
import com.lebarock.lebarock_desktop.data.offline.OpenCheckEntity
import com.lebarock.lebarock_desktop.data.offline.OrderRefEntity
import com.lebarock.lebarock_desktop.data.offline.SnapshotPartEntity
import com.lebarock.lebarock_desktop.data.offline.TableStateEntity
import com.lebarock.lebarock_desktop.data.remote.AddItemsRequest
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.SendOrderItem
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.KitchenTicketLine
import com.lebarock.lebarock_desktop.print.KitchenTicketPrint
import com.lebarock.lebarock_desktop.print.PrintOp
import com.lebarock.lebarock_desktop.print.ReceiptRenderer
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline mode, desktop step 2 (client, 2026-11-10): orders taken on this PC while the caisse does not answer — the
 * POS, the floor and Commandes from the copy, new orders « D1-001 », rounds, the bill, kitchen tickets and the bill on
 * this PC's printer, everything else refused in French, recorded as the Caisse's « Hors ligne » account.
 */
class OfflineOrdersTest {

    private val dir: File = Files.createTempDirectory("altospos-offline-orders").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer().also { it.start() }
    private val clock = AtomicLong(1_794_340_000_000)
    private val settings = SettingsStore(File(dir, "settings.json")).apply {
        update { it.copy(server = ServerAddress(false, server.hostName, server.port), printer = PrinterConfig(target = PrintTarget.CAISSE, windowsPrinter = "EPSON")) }
    }
    private val sessions = SessionHolder().apply { start(Session("t0ken", UserDto(id = 4, name = "Amel"))) }
    private val monitor = ConnectionMonitor(clock::get)
    private val client = ApiClient({ sessions.token }, watch = monitor.interceptor)
    private val store = OfflineStore.inMemory()
    private val sync = OfflineSync(store, client, settings, sessions, SecretBox(dir, windows = false), monitor, scope, clock::get)
    private val printed = mutableListOf<String>()
    private var printerDown = false
    private val printer = TicketPrinter(settings) {
        TicketTransport { bytes ->
            if (printerDown) throw com.lebarock.lebarock_desktop.print.PrintFailure("imprimante éteinte")
            printed += String(bytes, Charsets.ISO_8859_1); "Ticket envoyé à « EPSON »."
        }
    }
    private val offline = OfflineOrders(store, sync, sessions, printer, client.json, clock::get)
    private val orders = OrdersRepository(CaisseRepository(client, settings, sessions), settings, printer, client.json, offline, sync)

    @AfterTest
    fun stop() {
        scope.cancel()
        store.close()
        runCatching { server.close() }
    }

    private val static = """{"device":{"code":"D1"},"caisse":{"id":2,"name":"Counter 1","menu_id":1},
        "restaurant":{"name":"Le Baroque","lines":["Avenue Habib Bourguiba"],"timezone":"Africa/Tunis",
          "currency":{"code":"TND","symbol":"DT","position":"right_with_space","decimals":3,"thousand_separator":"","decimal_separator":"."},
          "receipt":{"show_table_number":true,"show_total_guest":true,"show_waiter":true},
          "labels":{"total":"Total","sub_total":"Sous-total","kot_table":"Table :table","kot_takeaway":"À emporter","kot_number":"Ticket :number","kot_total_qty":"Quantité totale : :count","table_number":"Table"}},
        "taxes":[{"id":1,"name":"TVA","percent":7}],
        "charges":[{"id":5,"name":"Service","type":"percent","value":10,"order_types":["dine_in"]}],
        "menu":{"items":[
          {"id":11,"name":"Pizza Margherita","price":18,"menu_id":1,"kot_place_id":1,"category":{"id":3,"name":"Pizzas"},
           "variations":[{"id":21,"name":"Grande","price":24}],
           "modifier_groups":[{"id":31,"name":"Extras","options":[{"id":41,"name":"Burrata","price":4.5}]}]},
          {"id":12,"name":"Citronnade","price":6,"menu_id":1,"kot_place_id":2,"category":{"id":4,"name":"Boissons"}},
          {"id":13,"name":"Eau minérale","price":2.5,"menu_id":1,"category":{"id":4,"name":"Boissons"}}]},
        "menus":[{"id":1,"name":"Carte Counter 1","items_count":3}],
        "areas":[{"id":1,"name":"Salle","tables":[{"id":1,"code":"1","seats":4},{"id":2,"code":"2","seats":2}]}],
        "stations":[{"id":1,"name":"Cuisine"},{"id":2,"name":"Bar"}],
        "offline":{"account":{"id":9,"name":"Hors ligne — Counter 1"}}}"""

    private val check812 = """{"id":812,"order_number":"812","order_type":"dine_in","status":"kot","number_of_pax":2,"sub_total":12,"total":14.16,
        "amount_paid":0,"items_count":1,"table":{"id":1,"code":"1"},"waiter":{"id":4,"name":"Amel"},
        "items":[{"id":7001,"name":"Citronnade","quantity":2,"price":6,"amount":12,"kot_id":501,"stock_state":"taken"}],
        "kots":[{"id":501,"number":"KOT-77","status":"Nouveau","items_count":1}]}"""

    /** A copy taken while signed in, the PC known as « D1 », and the caisse gone quiet for 10 s. */
    private fun offlineWithCopy(account: Boolean = true, register: Boolean = true) = runBlocking {
        store.saveSnapshotPart(SnapshotPartEntity("static", "v1", 2, 4, if (account) static else static.replace("\"account\":{\"id\":9,\"name\":\"Hors ligne — Counter 1\"}", "\"account\":null"), clock.get()))
        store.replaceChecks(
            SnapshotPartEntity("checks", null, 2, 4, "{}", clock.get()),
            listOf(OpenCheckEntity(812, "812", null, 1, "kot", 14.16, check812, clock.get())),
            listOf(TableStateEntity(1, "running", """{"id":1,"status":"running","session":{"pax":2,"opened_at":"2026-11-10T19:30:00+00:00"}}"""), TableStateEntity(2, "available", """{"id":2,"status":"available"}""")),
        )
        if (register) store.updateDevice({ DeviceStateEntity(uuid = "pc-1") }) { it.copy(code = "D1") }
        monitor.failed()
        clock.addAndGet(10_000)
        monitor.failed()
    }

    private fun commands() = runBlocking { store.nextOutbox(50) }

    private fun data(i: Int): JsonObject = client.json.parseToJsonElement(commands()[i].dataJson).jsonObject

    private fun pizza(qty: Int = 1) = SendOrderItem(11, menuItemVariationId = 21, quantity = qty, modifierOptionIds = listOf(41), note = "bien cuite")

    @Test
    fun the_pos_reads_the_copy_while_the_caisse_is_down() = runBlocking<Unit> {
        offlineWithCopy()
        assertEquals(listOf("Carte Counter 1"), orders.menus().getOrThrow().map { it.name })
        val items = orders.menuItems(1).getOrThrow()
        assertEquals(listOf("Pizza Margherita", "Citronnade", "Eau minérale"), items.map { it.name })
        assertEquals("Pizzas", items[0].category?.name, "the sections")
        assertEquals(24.0, items[0].variations.single().price)

        val context = orders.posContext().getOrThrow()
        assertEquals("D1-001", context.nextOrderNumber, "the next temporary number, not taken yet")
        assertEquals(listOf("TVA"), context.taxes.map { it.name })
        assertEquals(listOf(5L), context.charges.map { it.id })
        assertEquals(3, context.currency?.decimals)
        assertTrue(context.waiters.isEmpty(), "no waiter to pick: everything is recorded as « Hors ligne »")
        assertTrue(context.orderTypes.dineIn && context.orderTypes.pickup)
        assertEquals("D1-001", orders.posContext().getOrThrow().nextOrderNumber, "reading it twice takes nothing")

        // The floor: table 1 has check 812 and its party; table 2 is free.
        val tables = orders.tables().getOrThrow().single().tables
        assertEquals(listOf("running", "available"), tables.map { it.status })
        assertEquals(812L, tables[0].activeOrder?.id)
        assertEquals(2, tables[0].session?.pax)
        assertEquals(14.16, tables[0].openTotal)
        assertEquals(0, server.requestCount, "nothing asked of the caisse")
    }

    @Test
    fun a_new_order_is_kept_queued_and_its_kitchen_tickets_print_here() = runBlocking<Unit> {
        offlineWithCopy()
        val sent = orders.sendOrder(
            SendOrderRequest(tableId = 2, numberOfPax = 3, waiterId = 4, note = "Allergie noix", items = listOf(pizza(2), SendOrderItem(12, quantity = 1), SendOrderItem(13, quantity = 1))),
        ).getOrThrow()
        assertEquals("D1-001", sent.value.orderNumber)
        assertTrue(sent.value.id < 0, "the screens' id of an order taken offline")
        assertEquals(2L, sent.value.tableId)

        // Queued as the Caisse's « Hors ligne » account, with what the caisse needs to make the check.
        val create = commands().single()
        assertEquals("order.create", create.type)
        assertEquals(9L, create.actorId)
        assertEquals("Commande D1-001 · T2", create.label)
        val d = data(0)
        assertEquals("D1-001", d["offline_number"]!!.jsonPrimitive.content)
        assertEquals("2", d["table_id"]!!.jsonPrimitive.content)
        assertEquals("dine_in", d["order_type"]!!.jsonPrimitive.content)
        assertEquals("3", d["pax"]!!.jsonPrimitive.content)
        assertEquals("Allergie noix", d["note"]!!.jsonPrimitive.content)
        val line = d["lines"]!!.jsonArray[0].jsonObject
        assertEquals("21", line["variation_id"]!!.jsonPrimitive.content)
        assertEquals("[41]", line["modifier_option_ids"].toString())
        assertEquals(28.5, line["unit_price"]!!.jsonPrimitive.content.toDouble() + 4.5, "the size's price, the option on top")
        assertEquals(57.0, line["amount"]!!.jsonPrimitive.content.toDouble(), "2 × (24 + 4.5)")
        assertEquals(create.orderRef, d["order_ref"]!!.jsonPrimitive.content)

        // One ticket per station on this PC's printer (« Imprimante de la caisse » in Réglages: the caisse is down).
        assertEquals("2 tickets cuisine imprimés sur ce poste.", sent.print!!.message)
        assertEquals(2, printed.size, "the water has no kitchen")
        assertTrue(printed[0].contains("CUISINE") && printed[0].contains("HORS LIGNE") && printed[0].contains("Table 2") && printed[0].contains("Ticket D1-001"))
        assertTrue(printed[0].contains("Pizza Margherita") && printed[0].contains("x2") && printed[0].contains("- Grande") && printed[0].contains("- Burrata") && printed[0].contains("- bien cuite"))
        assertTrue(printed[1].contains("BAR") && printed[1].contains("Citronnade"))

        // The floor and Commandes show it.
        val detail = orders.orderDetail(sent.value.id).getOrThrow()
        assertEquals(3, detail.items.size)
        assertEquals(65.5, detail.subTotal)
        assertEquals(76.635, detail.total, 0.006) // 65.5 + 7 % + 10 % service
        assertEquals("Hors ligne — Counter 1", detail.waiter?.name)
        assertEquals("Imprimé hors ligne", detail.kots.single().status)
        val table2 = orders.tables().getOrThrow().single().tables[1]
        assertEquals("running", table2.status)
        assertEquals("D1-001", table2.activeOrder?.orderNumber)
        val list = orders.orders().getOrThrow()
        assertEquals(listOf("812", "D1-001"), list.data.map { it.orderNumber })
        assertEquals(2, list.counts.kot)

        // A second « Envoyer » on table 2 joins its check: a round, not a new order.
        orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(SendOrderItem(12, quantity = 2)))).getOrThrow()
        assertEquals(listOf("order.create", "order.round"), commands().map { it.type })
        assertEquals(create.orderRef, commands()[1].orderRef)
        assertEquals(2, orders.orderDetail(sent.value.id).getOrThrow().kots.size)
        assertEquals("D1-002", orders.posContext().getOrThrow().nextOrderNumber)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a_round_on_a_check_of_the_copy_then_its_bill() = runBlocking<Unit> {
        offlineWithCopy()
        orders.addItems(812, AddItemsRequest(items = listOf(SendOrderItem(12, quantity = 1)))).getOrThrow()
        val round = commands().single()
        assertEquals("order.round", round.type)
        assertEquals("812", data(0)["order_ref"]!!.jsonPrimitive.content, "the copy's check, by its id")

        val detail = orders.orderDetail(812).getOrThrow()
        assertEquals(listOf("Citronnade", "Citronnade"), detail.items.map { it.name })
        assertEquals(18.0, detail.subTotal)
        assertEquals(2, detail.kots.size)

        printed.clear()
        val billed = orders.bill(812, print = true).getOrThrow()
        assertEquals("billed", billed.value.status)
        assertEquals("Addition imprimée sur ce poste.", billed.print!!.message)
        assertEquals(listOf("order.round", "order.bill"), commands().map { it.type })
        assertEquals("Addition · Commande n° 812 · T1", commands()[1].label)
        val bill = printed.single()
        assertTrue(bill.contains("Le Baroque") && bill.contains("Citronnade") && bill.contains("Sous-total") && bill.contains("DT"))
        assertEquals(listOf("billed"), orders.orders(toPay = true).getOrThrow().data.map { it.status })

        // « Imprimer » again: the bill.
        printed.clear()
        assertEquals("Addition imprimée sur ce poste.", orders.reprint(812).getOrThrow())
        assertEquals(1, printed.size)
    }

    @Test
    fun what_waits_for_the_caisse_says_so_in_french() = runBlocking<Unit> {
        offlineWithCopy()
        // O4c: move, merge and split work offline now (OfflineTableOpsTest).
        val refusals = listOf(
            orders.tip(812, 2.0, null),
            orders.releaseTable(2),
            orders.customers("Sami"),
        ).map { it.exceptionOrNull() as ApiFailure }
        assertEquals("Hors ligne : ajouter un pourboire n'est pas possible tant que la caisse ne répond pas.", refusals[0].message)
        assertEquals("Hors ligne : libérer une table n'est pas possible tant que la caisse ne répond pas.", refusals[1].message)
        assertTrue(refusals.all { it.code == OfflineOrders.OFFLINE_CODE && it.message!!.startsWith("Hors ligne : ") })

        // O4b: cancel and the cart's charges ask the manager's PIN instead.
        val pinAsked = listOf(
            orders.cancel(812, null, "test"),
            orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(pizza()), removedChargeIds = listOf(5))),
        ).map { (it.exceptionOrNull() as ApiFailure).code }
        assertEquals(List(2) { OfflineOrders.PAYMENT_PIN_REQUIRED }, pinAsked)
        // « Code remise »: a discount asks the discount code (none on this PC: no PIN checks here).
        assertEquals(OfflinePins.NO_DISCOUNT_CODE, orders.setDiscount(812, "percent", 10.0, "2580").exceptionOrNull()!!.message)
        assertEquals(OfflinePins.NO_DISCOUNT_CODE, orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(pizza()), discountType = "percent", discountValue = 10.0, discountCode = "2580")).exceptionOrNull()!!.message)
        assertEquals(0, store.outboxSize(), "nothing recorded")
        assertEquals(0, printed.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun take_away_and_a_printer_that_does_not_answer() = runBlocking<Unit> {
        offlineWithCopy()
        printerDown = true
        val sent = orders.sendOrder(SendOrderRequest(orderType = "pickup", items = listOf(pizza()))).getOrThrow()
        assertEquals("pickup", data(0)["order_type"]!!.jsonPrimitive.content)
        assertNull(data(0)["table_id"])
        assertFalse(sent.print!!.ok)
        assertEquals("Ticket cuisine non imprimé (imprimante éteinte) : la commande est enregistrée, réimprimez-le depuis la commande.", sent.print!!.message)
        assertEquals(1, store.outboxSize(), "the order is kept whatever the printer does")

        printerDown = false
        assertEquals("Ticket cuisine imprimé sur ce poste.", orders.reprint(sent.value.id).getOrThrow(), "the kitchen ticket again, before the bill")
        assertTrue(printed.single().contains(" emporter"), "« À emporter » (the accent in the printer's code page)")

        // No printer chosen on this PC: said, the order still kept.
        settings.update { it.copy(printer = PrinterConfig(target = PrintTarget.CAISSE)) }
        val second = orders.sendOrder(SendOrderRequest(orderType = "pickup", items = listOf(pizza()))).getOrThrow()
        assertEquals("D1-002", second.value.orderNumber)
        assertTrue(second.print!!.message.startsWith("Ticket cuisine non imprimé (Choisissez l'imprimante Windows du comptoir.)"))
    }

    @Test
    fun without_its_offline_pin_or_its_copy_nothing_is_taken() = runBlocking<Unit> {
        offlineWithCopy(account = false)
        val noPin = orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(pizza()))).exceptionOrNull()!!
        assertEquals(OfflineOrders.NOT_CONFIGURED, noPin.message)
        assertEquals(0, store.outboxSize())
        assertEquals(0, store.localOrders().size)

        store.clearCopy()
        assertEquals(OfflineOrders.NO_COPY, orders.menus().exceptionOrNull()!!.message)
    }

    @Test
    fun online_again_an_order_taken_offline_opens_as_its_real_check() = runBlocking<Unit> {
        offlineWithCopy()
        val sent = orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(SendOrderItem(12, quantity = 1)))).getOrThrow()
        val command = commands().single()

        // The caisse took it as check 901; the outbox is empty; the caisse answers again.
        store.settle(command.id, null, OrderRefEntity(command.orderRef!!, "D1-001", 901, "901", clock.get()))
        monitor.answered()
        assertFalse(offline.active())
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("""{"status":"success","data":{"id":901,"order_number":"901","status":"kot","offline_number":"D1-001"}}""").build(),
        )
        val detail = orders.orderDetail(sent.value.id).getOrThrow()
        assertEquals("901", detail.orderNumber)
        assertEquals("/api/mobile/orders/901", server.takeRequest().url.encodedPath)

        // While something still waits to be sent, the screens stay on the copy even with the caisse answering.
        orders.sendOrder(SendOrderRequest(tableId = 1, items = listOf(SendOrderItem(13, quantity = 1)))).let { assertTrue(it.isFailure, "online: the caisse is asked") }
        store.insertOutbox(command.copy(id = "late", seq = 99))
        assertTrue(offline.active())
        assertEquals("812", orders.orderDetail(812).getOrThrow().orderNumber)
    }

    @Test
    fun the_kitchen_ticket_layout() {
        val ops = ReceiptRenderer(48).renderKitchen(
            KitchenTicketPrint(
                station = "Cuisine", left = "Table 5", right = "Ticket D1-007",
                items = listOf(KitchenTicketLine("Pizza Margherita", 2, listOf("Grande", "Burrata"))),
                totalQuantity = "Quantité totale : 2", note = null, dateTime = "10/11/2026 20:00",
            ),
        )
        val texts = ops.filterIsInstance<PrintOp.Text>()
        assertEquals(PrintOp.Text("CUISINE", com.lebarock.lebarock_desktop.print.Align.CENTER, bold = true, big = true), texts[0])
        assertEquals("HORS LIGNE", texts[1].text)
        assertEquals(48, texts[2].text.length, "table left, number right")
        assertTrue(texts[2].text.startsWith("Table 5") && texts[2].text.endsWith("Ticket D1-007"))
        assertTrue(texts.any { it.big && it.text.startsWith("Pizza") && it.text.endsWith("x2") })
        assertTrue(texts.any { it.text == "   - Grande" })
        assertEquals(PrintOp.Cut, ops.last())
    }
}
