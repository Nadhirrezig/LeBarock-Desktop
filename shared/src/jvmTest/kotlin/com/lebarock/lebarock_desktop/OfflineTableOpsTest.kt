package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.offline.ConnectionMonitor
import com.lebarock.lebarock_desktop.data.offline.DeviceStateEntity
import com.lebarock.lebarock_desktop.data.offline.OfflineAccess
import com.lebarock.lebarock_desktop.data.offline.OfflineOrders
import com.lebarock.lebarock_desktop.data.offline.OfflinePins
import com.lebarock.lebarock_desktop.data.offline.OfflineStore
import com.lebarock.lebarock_desktop.data.offline.OfflineSync
import com.lebarock.lebarock_desktop.data.offline.OpenCheckEntity
import com.lebarock.lebarock_desktop.data.offline.SnapshotPartEntity
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.SendOrderItem
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Offline O4c (client, 2026-11-11): the table's operations while the caisse does not answer — move, merge, split — as
 * online (no PIN), shown on the floor and the checks at once and queued for the caisse.
 *
 * (Setup as O4b: the check's changes while the caisse does not answer — discount, cancel with its
 * reason, a sent line with no kitchen station taken off with its reason, waiter, extra charge — behind the manager's
 * payment PIN (once per check, 5 min), shown on the check at once and queued for the caisse as « Hors ligne ».
 */
class OfflineTableOpsTest {

    private val dir: File = Files.createTempDirectory("altospos-offline-tables").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer().also { it.start() }
    private val clock = AtomicLong(1_794_340_000_000)
    private val settings = SettingsStore(File(dir, "settings.json")).apply {
        update { it.copy(server = ServerAddress(false, server.hostName, server.port), printer = PrinterConfig(target = PrintTarget.DEVICE, windowsPrinter = "EPSON")) }
    }
    private val sessions = SessionHolder()
    private val monitor = ConnectionMonitor(clock::get)
    private val client = ApiClient({ sessions.token }, watch = monitor.interceptor)
    private val secrets = SecretBox(dir, windows = false)
    private val store = OfflineStore.inMemory()
    private val sync = OfflineSync(store, client, settings, sessions, secrets, monitor, scope, clock::get)
    private val printed = mutableListOf<String>()
    private val printer = TicketPrinter(settings) { TicketTransport { bytes -> printed += String(bytes, Charsets.ISO_8859_1); "ok" } }
    private val offline = OfflineOrders(store, sync, sessions, printer, client.json, clock::get)
    private val pins = OfflinePins(store, secrets, clock::get)
    private val repository = CaisseRepository(client, settings, sessions)
    private val orders = OrdersRepository(repository, settings, printer, client.json, offline, sync, pins)

    @AfterTest
    fun stop() {
        scope.cancel()
        store.close()
        runCatching { server.close() }
    }

    /** The branch's copy: Counter 1, a 10 % service charge on dine-in, two waiters, one cancel reason. */
    private val static = """{"format":2,"device":{"code":"D1"},
        "caisses":[{"id":2,"name":"Counter 1","menu_id":1,"offline_account":{"id":9,"name":"Hors ligne — Counter 1"}}],
        "restaurant":{"name":"Le Baroque","currency":{"symbol":"DT","position":"right_with_space","decimals":3}},
        "taxes":[],"charges":[{"id":5,"name":"Service","type":"percent","value":10,"order_types":["dine_in"]}],
        "menu":{"items":[{"id":11,"name":"Pizza","price":20,"menu_id":1,"kot_place_id":1},{"id":12,"name":"Citronnade","price":6,"menu_id":1}]},
        "areas":[{"id":1,"name":"Salle","tables":[{"id":1,"code":"1"},{"id":2,"code":"2"}]}],
        "stations":[{"id":1,"name":"Cuisine"}],
        "waiters":[{"id":4,"name":"Amel"},{"id":6,"name":"Sami"}],
        "cancel_reasons":[{"id":3,"reason":"Client parti"}],
        "offline":{}}"""

    /** Check #812 on table 1 from the copy: a pizza sent to the kitchen and two citronnades (no station), service 10 %. */
    private val check812 = """{"id":812,"order_number":"812","order_type":"dine_in","status":"kot","sub_total":32,"total":35.2,"amount_paid":0,
        "table":{"id":1,"code":"1"},"caisse_id":2,
        "items":[{"id":7001,"name":"Pizza","quantity":1,"price":20,"amount":20,"stock_state":"taken","fired":true},
                 {"id":7002,"name":"Citronnade","quantity":2,"price":6,"amount":12,"stock_state":"taken","fired":false}],
        "charges":[{"id":51,"name":"Service","type":"percent","value":10,"amount":3.2,"removable":true}],"payments":[]}"""

    private fun ready() = runBlocking {
        store.saveSnapshotPart(SnapshotPartEntity("static", "v1", 0, 0, static, clock.get()))
        store.replaceChecks(SnapshotPartEntity("checks", null, 0, 0, "{}", clock.get()), listOf(OpenCheckEntity(812, "812", null, 1, "kot", 35.2, check812, clock.get())), emptyList())
        store.updateDevice({ DeviceStateEntity(uuid = "pc-1") }) { it.copy(code = "D1") }
        store.saveOfflineAccesses(listOf(OfflineAccess(9, "Hors ligne — Counter 1", secrets.seal(OfflinePaymentTest.verifier("2468")), secrets.seal(OfflinePaymentTest.verifier("1357")), 2, "Counter 1", 1)))
        repository.signInOffline(pins, "2468").getOrThrow()
    }

    private fun commands() = runBlocking { store.nextOutbox(50) }

    private fun data(type: String): JsonObject = client.json.parseToJsonElement(commands().last { it.type == type }.dataJson).jsonObject

    private fun pinAsked(result: Result<*>) = (result.exceptionOrNull() as? ApiFailure)?.code == OfflineOrders.PAYMENT_PIN_REQUIRED

    private fun floor() = runBlocking { orders.tables().getOrThrow().single().tables.associateBy { it.id } }

    @Test
    fun a_check_moves_to_a_free_table_never_to_a_taken_one() = runBlocking<Unit> {
        ready()
        val change = orders.moveOrder(812, 2).getOrThrow()
        assertEquals("Commande déplacée sur la table 2 (hors ligne).", change.message)
        assertEquals(2L, change.order.table?.id)
        val sent = data("order.move")
        assertEquals("2", sent["table_id"]!!.jsonPrimitive.content)
        assertNull(sent["authorized_by"], "no PIN, as online")
        assertTrue(floor()[1L]!!.activeOrder == null && floor()[1L]!!.session == null, "table 1 is free")
        assertEquals(812L, floor()[2L]!!.activeOrder?.id)

        // A new order on table 1, then 812 cannot go back there: that is a merge.
        orders.sendOrder(SendOrderRequest(tableId = 1, items = listOf(SendOrderItem(12, quantity = 1)))).getOrThrow()
        assertEquals("La table 1 a déjà un ticket en cours : utilisez « Fusionner les tables ».", orders.moveOrder(812, 1).exceptionOrNull()!!.message)
    }

    @Test
    fun a_merge_folds_the_check_into_the_other_tables_check() = runBlocking<Unit> {
        ready()
        val other = orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(SendOrderItem(12, quantity = 1)))).getOrThrow().value.id
        assertTrue(floor()[2L]!!.session != null, "a table with an order taken offline can be merged into")

        val change = orders.mergeOrder(812, 2).getOrThrow()
        assertEquals("Table 1 fusionnée avec la table 2 (hors ligne).", change.message)
        assertEquals(other, change.order.id, "the other table's check holds everything")
        assertEquals(listOf("Citronnade", "Pizza", "Citronnade"), change.order.items.map { it.name })
        // 6 + 32 = 38, service 10 %: 41.8.
        assertEquals(41.8, change.order.total)
        assertTrue(orders.orders().getOrThrow().data.none { it.id == 812L }, "812 is folded in")
        assertNull(floor()[1L]!!.activeOrder)
        val sent = data("order.merge")
        assertEquals("812", sent["order_ref"]!!.jsonPrimitive.content)
        assertEquals("2", sent["table_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_merge_into_a_table_with_no_check_moves_it_there() = runBlocking<Unit> {
        ready()
        val change = orders.mergeOrder(812, 2).getOrThrow()
        assertEquals(812L, change.order.id)
        assertEquals(2L, change.order.table?.id)
        assertEquals("order.merge", commands().single().type)
    }

    @Test
    fun a_split_makes_a_new_order_with_its_own_number() = runBlocking<Unit> {
        ready()
        val change = orders.splitOrder(812, mapOf(7002L to 1)).getOrThrow()
        val created = change.newOrder!!
        assertTrue(created.id < 0)
        assertEquals("D1-001", created.orderNumber)
        // One citronnade 6, service 10 %: 6.6.
        assertEquals(6.6, created.total)
        assertEquals(listOf(1, 1), change.order.items.map { it.quantity }, "812 keeps the pizza and one citronnade")
        assertEquals(28.6, change.order.total)

        val newOrder = orders.orderDetail(created.id).getOrThrow()
        assertEquals(1L, newOrder.table?.id, "same table")
        assertEquals(listOf("Citronnade"), newOrder.items.map { it.name })
        assertEquals(2, floor()[1L]!!.openOrders)

        val sent = data("order.split")
        assertEquals("812", sent["order_ref"]!!.jsonPrimitive.content)
        assertEquals("D1-001", sent["offline_number"]!!.jsonPrimitive.content)
        assertEquals("[{\"line_ref\":\"7002\",\"quantity\":1}]", sent["lines"].toString())

        assertEquals("Laissez au moins un article sur le ticket d'origine.", orders.splitOrder(812, mapOf(7001L to 1, 7002L to 1)).exceptionOrNull()!!.message)
    }

    @Test
    fun a_check_that_took_money_is_not_split() = runBlocking<Unit> {
        ready()
        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        orders.pay(812, com.lebarock.lebarock_desktop.data.remote.PaymentRequest(method = "cash", amount = 5.0)).getOrThrow()
        assertEquals("Ce ticket a déjà un paiement : utilisez « Partager l'addition » au moment de payer.", orders.splitOrder(812, mapOf(7002L to 1)).exceptionOrNull()!!.message)
    }
}
