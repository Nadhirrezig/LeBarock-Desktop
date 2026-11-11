package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.LineRemoval
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
import kotlinx.serialization.json.double
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
 * Offline O4b (client, 2026-11-11): the check's changes while the caisse does not answer — discount, cancel with its
 * reason, a sent line with no kitchen station taken off with its reason, waiter, extra charge — behind the manager's
 * payment PIN (once per check, 5 min), shown on the check at once and queued for the caisse as « Hors ligne ».
 */
class OfflineCheckEditsTest {

    private val dir: File = Files.createTempDirectory("altospos-offline-edits").toFile()
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
        store.saveOfflineAccesses(listOf(OfflineAccess(9, "Hors ligne — Counter 1", secrets.seal(OfflinePaymentTest.verifier("2468")), secrets.seal(OfflinePaymentTest.verifier("1357")), 2, "Counter 1", 1,
            discountVerifierSealed = secrets.seal(OfflinePaymentTest.verifier("2580")))))
        repository.signInOffline(pins, "2468").getOrThrow()
    }

    private fun commands() = runBlocking { store.nextOutbox(50) }

    private fun data(type: String): JsonObject = client.json.parseToJsonElement(commands().last { it.type == type }.dataJson).jsonObject

    private fun pinAsked(result: Result<*>) = (result.exceptionOrNull() as? ApiFailure)?.code == OfflineOrders.PAYMENT_PIN_REQUIRED

    @Test
    fun a_discount_needs_the_discount_code_and_follows_the_webs_rules() = runBlocking<Unit> {
        ready()
        // « Code remise » (client, 2026-11-11): not the payment PIN — the discount code, each time.
        assertEquals("Le code remise compte de 4 à 6 chiffres.", orders.setDiscount(812, "percent", 10.0).exceptionOrNull()!!.message)
        assertEquals("Code remise incorrect.", orders.setDiscount(812, "percent", 10.0, "1357").exceptionOrNull()!!.message, "the payment PIN is not the discount code")
        assertTrue(commands().isEmpty())
        assertTrue(orders.checkDiscountCode("2580").isSuccess)

        val change = orders.setDiscount(812, "percent", 10.0, "2580").getOrThrow()
        assertEquals("Remise appliquée (hors ligne).", change.message)
        // 32 − 3.20 = 28.80, service 10 % of it 2.88: 31.68.
        assertEquals(3.2, change.order.discountAmount)
        assertEquals(31.68, change.order.total)
        assertEquals(2.88, change.order.charges.single().amount)
        val sent = data("order.discount")
        assertEquals("percent", sent["discount_type"]!!.jsonPrimitive.content)
        assertEquals("discount_code", sent["authorized_by"]!!.jsonPrimitive.content)
        assertEquals("812", sent["order_ref"]!!.jsonPrimitive.content)
        assertEquals(9L, commands().last().actorId, "as the « Hors ligne » account")
        assertEquals(31.68, orders.orderDetail(812).getOrThrow().total, "the check shows it")

        assertEquals("La remise ne peut pas dépasser 100 %.", orders.setDiscount(812, "percent", 120.0, "2580").exceptionOrNull()!!.message)
        // Taken off: no code.
        assertEquals(35.2, orders.setDiscount(812, null, null).getOrThrow().order.total)
        assertNull(data("order.discount")["authorized_by"])
    }

    @Test
    fun a_tip_offline_follows_the_setting_needs_no_pin_and_replaces_the_tip() = runBlocking<Unit> {
        ready()
        // The copy says the restaurant does not take tips at the POS: refused, as online.
        assertFalse(orders.paymentContext(812).getOrThrow().canAddTip)
        assertEquals("Le pourboire n'est pas autorisé.", orders.tip(812, 2.0, null).exceptionOrNull()!!.message)

        store.saveSnapshotPart(SnapshotPartEntity("static", "v2", 0, 0, static.replace("\"name\":\"Le Baroque\",", "\"name\":\"Le Baroque\",\"tip_pos\":true,"), clock.addAndGet(1_000)))
        assertTrue(orders.paymentContext(812).getOrThrow().canAddTip)

        // O4d (client, 2026-11-11): no PIN; the total follows; sent as order.tip by the « Hors ligne » account.
        assertTrue(orders.tip(812, 2.5, "  Merci ").isSuccess)
        val context = orders.paymentContext(812).getOrThrow()
        assertEquals(2.5, context.tipAmount)
        assertEquals("Merci", context.tipNote)
        assertEquals(37.7, context.total)
        val sent = data("order.tip")
        assertEquals(2.5, sent["amount"]!!.jsonPrimitive.double)
        assertEquals("Merci", sent["note"]!!.jsonPrimitive.content)
        assertNull(sent["authorized_by"], "no PIN")
        assertEquals(9L, commands().last().actorId)

        // Replaced, then taken off.
        assertTrue(orders.tip(812, 1.0, null).isSuccess)
        assertEquals(36.2, orders.orderDetail(812).getOrThrow().total)
        assertTrue(orders.tip(812, 0.0, null).isSuccess)
        assertEquals(35.2, orders.orderDetail(812).getOrThrow().total)
        assertNull(orders.paymentContext(812).getOrThrow().tipNote)
        assertEquals(3, commands().count { it.type == "order.tip" })
    }

    @Test
    fun a_cancel_needs_its_reason_and_frees_the_table() = runBlocking<Unit> {
        ready()
        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        assertEquals(listOf("Client parti"), orders.cancelReasons().getOrThrow().map { it.reason }, "from the copy")
        assertEquals("Choisissez un motif d'annulation, ou écrivez pourquoi.", orders.cancel(812, null, " ").exceptionOrNull()!!.message)

        orders.cancel(812, 3, "parti avant le plat").getOrThrow()
        val sent = data("order.cancel")
        assertEquals("3", sent["reason_id"]!!.jsonPrimitive.content)
        assertEquals("parti avant le plat", sent["reason"]!!.jsonPrimitive.content)
        assertTrue(orders.orders().getOrThrow().data.none { it.id == 812L }, "off the open checks")
        assertNull(orders.tables().getOrThrow().single().tables.first { it.id == 1L }.activeOrder, "the table is free")
        assertEquals("canceled", orders.orderDetail(812).getOrThrow().status)
    }

    @Test
    fun a_drink_is_taken_off_with_its_reason_never_a_dish_sent_to_the_kitchen() = runBlocking<Unit> {
        ready()
        assertEquals(OfflineOrders.DISH_SENT, orders.removeItem(812, 7001, 3, null).exceptionOrNull()!!.message)
        assertEquals("Choisissez un motif, ou écrivez pourquoi l'article est retiré.", orders.removeItem(812, 7002, null, null).exceptionOrNull()!!.message)
        assertTrue(pinAsked(orders.removeItem(812, 7002, null, "renversé")))

        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        assertEquals(LineRemoval.KEPT, orders.removeItem(812, 7002, null, "renversé").getOrThrow())
        val sent = data("order.remove_line")
        assertEquals("7002", sent["line_ref"]!!.jsonPrimitive.content)
        assertEquals("renversé", sent["reason"]!!.jsonPrimitive.content)
        val after = orders.orderDetail(812).getOrThrow()
        assertEquals(listOf("Pizza"), after.items.map { it.name })
        assertEquals(22.0, after.total, "20 + service 2")
    }

    @Test
    fun the_last_line_taken_off_cancels_an_order_taken_offline() = runBlocking<Unit> {
        ready()
        orders.unlockOfflinePayment(OfflineOrders.NEW_ORDER, "1357").getOrThrow()
        val id = orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(SendOrderItem(12, quantity = 1)))).getOrThrow().value.id
        val line = orders.orderDetail(id).getOrThrow().items.single()

        orders.unlockOfflinePayment(id, "1357").getOrThrow()
        assertEquals(LineRemoval.CANCELLED, orders.removeItem(id, line.id, 3, null).getOrThrow())
        val sent = data("order.remove_line")
        val create = data("order.create")
        assertEquals(create["lines"].toString().substringAfter("\"line_id\":\"").substringBefore('"'), sent["line_ref"]!!.jsonPrimitive.content, "its offline line id")
        assertTrue(orders.orders().getOrThrow().data.none { it.id == id })
    }

    @Test
    fun the_waiter_is_one_of_the_copys() = runBlocking<Unit> {
        ready()
        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        assertEquals("Ce serveur n'est pas dans les données hors ligne de ce poste.", orders.setWaiter(812, 99).exceptionOrNull()!!.message)
        val change = orders.setWaiter(812, 6).getOrThrow()
        assertEquals("Serveur : Sami (hors ligne).", change.message)
        assertEquals("Sami", change.order.waiter?.name)
        assertEquals("6", data("order.waiter")["waiter_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("Amel", "Sami"), orders.posContext().getOrThrow().waiters.map { it.name })
    }

    @Test
    fun a_charge_is_taken_off_by_the_checks_line_or_the_restaurants_charge() = runBlocking<Unit> {
        ready()
        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        val change = orders.removeCharge(812, 51).getOrThrow()
        assertEquals(32.0, change.order.total)
        assertTrue(change.order.charges.isEmpty())
        assertEquals("51", data("order.remove_charge")["order_charge_id"]!!.jsonPrimitive.content)

        // An order taken offline names the restaurant's charge.
        orders.unlockOfflinePayment(OfflineOrders.NEW_ORDER, "1357").getOrThrow()
        val id = orders.sendOrder(SendOrderRequest(tableId = 2, items = listOf(SendOrderItem(11, quantity = 1)))).getOrThrow().value.id
        assertEquals(22.0, orders.orderDetail(id).getOrThrow().total, "20 + service 10 %")
        orders.unlockOfflinePayment(id, "1357").getOrThrow()
        assertEquals(20.0, orders.removeCharge(id, 5).getOrThrow().order.total)
        assertEquals("5", data("order.remove_charge")["charge_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_new_order_takes_the_carts_discount_charges_and_waiter_behind_the_pin() = runBlocking<Unit> {
        ready()
        val request = SendOrderRequest(
            tableId = 2, waiterId = 4, items = listOf(SendOrderItem(11, quantity = 2)),
            removedChargeIds = listOf(5), discountType = "fixed", discountValue = 5.0, discountCode = "2580",
        )
        // The discount needs its code; the charge and the waiter the PIN.
        assertEquals("Code remise incorrect.", orders.sendOrder(request.copy(discountCode = "0000")).exceptionOrNull()!!.message)
        assertTrue(pinAsked(orders.sendOrder(request)), "the cart's changes ask the PIN")
        assertTrue(commands().isEmpty(), "nothing sent before")

        orders.unlockOfflinePayment(OfflineOrders.NEW_ORDER, "1357").getOrThrow()
        val id = orders.sendOrder(request).getOrThrow().value.id
        assertEquals(listOf("order.create", "order.remove_charge", "order.discount", "order.waiter"), commands().map { it.type }, "in order")
        val detail = orders.orderDetail(id).getOrThrow()
        assertEquals(35.0, detail.total, "40 − 5, no service")
        assertEquals("Amel", detail.waiter?.name)

        // A plain order (no change, the « Hors ligne » account as waiter) needs no PIN.
        val plain = orders.sendOrder(SendOrderRequest(tableId = null, orderType = "pickup", waiterId = 9, items = listOf(SendOrderItem(12, quantity = 1))))
        assertFalse(pinAsked(plain))
        assertTrue(plain.isSuccess)
    }
}
