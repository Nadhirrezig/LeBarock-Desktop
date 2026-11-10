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
import com.lebarock.lebarock_desktop.data.remote.PaymentRequest
import com.lebarock.lebarock_desktop.data.remote.SendOrderItem
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.remote.SplitPartDto
import com.lebarock.lebarock_desktop.data.remote.SplitPartItemDto
import com.lebarock.lebarock_desktop.data.remote.SplitPaymentDto
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import com.lebarock.lebarock_desktop.ui.offline.PinEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline mode, desktop step 3 (client, 2026-11-10): the « Hors ligne » sign-in with the Caisse's offline PIN, and
 * offline payments (cash with change, card, the bill divided) behind the manager's payment PIN, typed once per check
 * for 5 minutes; the receipt on this PC's printer.
 */
class OfflinePaymentTest {

    companion object {
        /** A verifier the caisse's way (App\Support\Sync\OfflinePin): pbkdf2-sha256$<iterations>$<salt>$<hash>. */
        fun verifier(pin: String, iterations: Int = 2_000): String {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded
            val b64 = Base64.getEncoder()
            return "pbkdf2-sha256$$iterations$${b64.encodeToString(salt)}$${b64.encodeToString(hash)}"
        }
    }

    private val dir: File = Files.createTempDirectory("altospos-offline-pay").toFile()
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

    private val static = """{"device":{"code":"D1"},"caisse":{"id":2,"name":"Counter 1","menu_id":1},
        "restaurant":{"name":"Le Baroque","currency":{"symbol":"DT","position":"right_with_space","decimals":3},"labels":{"cash":"Espèces","card":"Carte"}},
        "taxes":[],"charges":[],
        "menu":{"items":[{"id":11,"name":"Pizza","price":20,"menu_id":1,"kot_place_id":1},{"id":12,"name":"Citronnade","price":6,"menu_id":1}]},
        "areas":[{"id":1,"name":"Salle","tables":[{"id":1,"code":"1"},{"id":2,"code":"2"}]}],
        "stations":[{"id":1,"name":"Cuisine"}],
        "offline":{"account":{"id":9,"name":"Hors ligne — Counter 1"}}}"""

    private val check812 = """{"id":812,"order_number":"812","order_type":"dine_in","status":"billed","sub_total":12,"total":12,"amount_paid":0,
        "table":{"id":1,"code":"1"},"items":[{"id":7001,"name":"Citronnade","quantity":2,"price":6,"amount":12}]}"""

    private fun ready(paymentPin: Boolean = true) = runBlocking {
        store.saveSnapshotPart(SnapshotPartEntity("static", "v1", 2, 4, static, clock.get()))
        store.replaceChecks(SnapshotPartEntity("checks", null, 2, 4, "{}", clock.get()), listOf(OpenCheckEntity(812, "812", null, 1, "billed", 12.0, check812, clock.get())), emptyList())
        store.updateDevice({ DeviceStateEntity(uuid = "pc-1") }) { it.copy(code = "D1") }
        store.saveOfflineAccess(
            OfflineAccess(9, "Hors ligne — Counter 1", secrets.seal(verifier("2468")), if (paymentPin) secrets.seal(verifier("1357")) else null, 2, "Counter 1", 1),
        )
    }

    private fun commands() = runBlocking { store.nextOutbox(50) }

    private fun data(type: String): JsonObject = client.json.parseToJsonElement(commands().last { it.type == type }.dataJson).jsonObject

    @Test
    fun the_offline_pin_signs_in_as_the_caisses_hors_ligne_account() = runBlocking<Unit> {
        assertFalse(pins.signInAvailable(), "no copy yet: no offline sign-in")
        assertEquals(OfflinePins.NONE, repository.signInOffline(pins, "2468").exceptionOrNull()!!.message)
        ready()
        assertTrue(pins.signInAvailable())

        assertEquals("Le code PIN compte de 4 à 6 chiffres.", repository.signInOffline(pins, "12").exceptionOrNull()!!.message)
        assertEquals(OfflinePins.WRONG, repository.signInOffline(pins, "1111").exceptionOrNull()!!.message)
        assertNull(sessions.current)

        val session = repository.signInOffline(pins, "2468").getOrThrow()
        assertTrue(session.offline)
        assertEquals(9L, session.user.id)
        assertEquals("Hors ligne — Counter 1", session.user.name)
        assertEquals(2L, session.caisse?.id)
        assertNull(sessions.token, "no token: nothing of it goes to the caisse")
        val rights = session.rights
        assertTrue(rights.orders && rights.showOrders && rights.takePayment && rights.updateOrder)
        assertFalse(rights.cashRegister || rights.cancelOrder || rights.applyDiscount || rights.giveCredit || rights.assignWaiter || rights.manageSettings)

        // The screens work from the copy, whatever the caisse does; the account is never asked of the caisse.
        assertTrue(offline.active())
        assertEquals(listOf("Pizza", "Citronnade"), orders.menuItems(1).getOrThrow().map { it.name })
        assertEquals(session, repository.refreshSession().getOrThrow())
        assertTrue(repository.terminals().exceptionOrNull()!!.message!!.startsWith("Session « Hors ligne » : "))
        repository.logout("test")
        assertNull(sessions.current)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun five_wrong_pins_lock_the_check_for_thirty_seconds() = runBlocking<Unit> {
        ready()
        repeat(4) { assertEquals(OfflinePins.WRONG, pins.signIn("0000").exceptionOrNull()!!.message) }
        assertEquals("Code PIN incorrect. Trop d'essais : réessayez dans 30 s.", pins.signIn("0000").exceptionOrNull()!!.message)
        assertEquals("Trop d'essais : réessayez dans 30 s.", pins.signIn("2468").exceptionOrNull()!!.message, "even the right one waits")
        clock.addAndGet(30_000)
        assertTrue(pins.signIn("2468").isSuccess)
    }

    @Test
    fun a_cash_payment_asks_the_payment_pin_once_then_prints_the_receipt() = runBlocking<Unit> {
        ready()
        repository.signInOffline(pins, "2468").getOrThrow()
        val sent = orders.sendOrder(SendOrderRequest(tableId = 2, action = "bill", items = listOf(SendOrderItem(11, quantity = 1), SendOrderItem(12, quantity = 1)))).getOrThrow()
        val id = sent.value.id

        val context = orders.paymentContext(id).getOrThrow()
        assertEquals(26.0, context.due)
        assertEquals(listOf("cash", "card"), context.paymentMethods)
        assertFalse(context.canGiveCredit || context.canAddTip)
        assertEquals("Counter 1", context.caisse?.name)

        // No PIN yet: the window asks it; a wrong one is refused; nothing recorded.
        assertEquals(OfflineOrders.PAYMENT_PIN_REQUIRED, (orders.pay(id, PaymentRequest(method = "cash", amount = 30.0)).exceptionOrNull() as ApiFailure).code)
        assertEquals(OfflinePins.WRONG, orders.unlockOfflinePayment(id, "2468").exceptionOrNull()!!.message, "the sign-in PIN is not the payment PIN")
        assertEquals(0, commands().count { it.type.startsWith("payment.") })

        assertTrue(orders.unlockOfflinePayment(id, "1357").isSuccess)
        printed.clear()
        val paid = orders.pay(id, PaymentRequest(method = "cash", amount = 30.0)).getOrThrow()
        assertEquals("paid", paid.value.status)
        assertEquals(4.0, paid.value.change)
        assertEquals(0.0, paid.value.due)
        assertEquals("Ticket imprimé sur ce poste.", paid.print!!.message)
        assertTrue(printed.single().contains("Rendu") && printed.single().contains("4.000"))

        val tender = data("payment.tender")
        assertEquals("cash", tender["method"]!!.jsonPrimitive.content)
        assertEquals(30.0, tender["amount"]!!.jsonPrimitive.content.toDouble(), "what was handed over")
        assertEquals(4.0, tender["change"]!!.jsonPrimitive.content.toDouble())
        assertEquals("payment_pin", tender["authorized_by"]!!.jsonPrimitive.content)
        assertEquals(commands().first { it.type == "order.create" }.orderRef, tender["order_ref"]!!.jsonPrimitive.content)
        assertEquals(9L, commands().last().actorId, "as the « Hors ligne » account")
        assertTrue(orders.orders().getOrThrow().data.none { it.id == id }, "settled: off the open checks")

        // « Réimprimer »: the receipt again, with the payment.
        printed.clear()
        assertEquals("Ticket imprimé sur ce poste.", orders.reprint(id).getOrThrow())
        assertTrue(printed.single().contains("Rendu"))
    }

    @Test
    fun the_pin_holds_five_minutes_for_one_check_only() = runBlocking<Unit> {
        ready()
        repository.signInOffline(pins, "2468").getOrThrow()
        orders.unlockOfflinePayment(812, "1357").getOrThrow()

        // Card: the whole due, at once; a second guest of the same check pays without the PIN.
        val card = orders.pay(812, PaymentRequest(method = "cash", amount = 5.0)).getOrThrow()
        assertEquals(7.0, card.value.due)
        assertEquals("billed", card.value.status)
        assertNull(card.print, "the receipt waits for the check to be settled")
        clock.addAndGet(4 * 60_000)
        val rest = orders.pay(812, PaymentRequest(method = "card")).getOrThrow()
        assertEquals("paid", rest.value.status)
        assertEquals(7.0, client.json.parseToJsonElement(commands().last().dataJson).jsonObject["amount"]!!.jsonPrimitive.content.toDouble(), "card: the whole due")

        // Another check, or the same after 5 minutes: the PIN again.
        val other = orders.sendOrder(SendOrderRequest(tableId = 2, action = "bill", items = listOf(SendOrderItem(12, quantity = 1)))).getOrThrow().value.id
        assertEquals(OfflineOrders.PAYMENT_PIN_REQUIRED, (orders.pay(other, PaymentRequest(method = "card")).exceptionOrNull() as ApiFailure).code)
        orders.unlockOfflinePayment(other, "1357").getOrThrow()
        clock.addAndGet(5 * 60_000 + 1)
        assertEquals(OfflineOrders.PAYMENT_PIN_REQUIRED, (orders.pay(other, PaymentRequest(method = "card")).exceptionOrNull() as ApiFailure).code)
    }

    @Test
    fun the_bill_divided_goes_in_one_command() = runBlocking<Unit> {
        ready()
        repository.signInOffline(pins, "2468").getOrThrow()
        val id = orders.sendOrder(SendOrderRequest(tableId = 2, action = "bill", items = listOf(SendOrderItem(11, quantity = 1), SendOrderItem(12, quantity = 1)))).getOrThrow().value.id
        orders.unlockOfflinePayment(id, "1357").getOrThrow()

        // By article: the pizza in cash, the drink by card; the caisse finds the lines by their offline ids.
        val items = orders.paymentContext(id).getOrThrow().items
        assertEquals(listOf(20.0, 6.0), items.map { it.unitPrice })
        val split = SplitPaymentDto("items", listOf(
            SplitPartDto("cash", items = listOf(SplitPartItemDto(items[0].orderItemId, 1, 20.0))),
            SplitPartDto("card", items = listOf(SplitPartItemDto(items[1].orderItemId, 1, 6.0))),
        ))
        val paid = orders.pay(id, PaymentRequest(split = split)).getOrThrow()
        assertEquals("paid", paid.value.status)
        val d = data("payment.split")
        assertEquals("items", d["type"]!!.jsonPrimitive.content)
        val parts = d["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("cash", "card"), parts.map { it["method"]!!.jsonPrimitive.content })
        assertEquals(listOf(20.0, 6.0), parts.map { it["amount"]!!.jsonPrimitive.content.toDouble() })
        val ref = parts[0]["items"]!!.jsonArray.single().jsonObject["order_item_id"]!!.jsonPrimitive.content
        assertEquals(36, ref.length, "the line's offline id (a uuid), not the screen's negative id")
        assertEquals("payment_pin", d["authorized_by"]!!.jsonPrimitive.content)

        // Equal parts on the copy's check, by its own line ids; more than the due is refused.
        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        val tooMuch = orders.pay(812, PaymentRequest(split = SplitPaymentDto("custom", listOf(SplitPartDto("cash", 10.0), SplitPartDto("card", 5.0))))).exceptionOrNull()!!
        assertTrue(tooMuch.message!!.startsWith("Les parts dépassent le reste à payer"))
        assertTrue(orders.pay(812, PaymentRequest(split = SplitPaymentDto("equal", listOf(SplitPartDto("cash", 6.0), SplitPartDto("card", 6.0))))).isSuccess)
        assertEquals("equal", data("payment.split")["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun credit_and_a_missing_payment_pin_are_refused() = runBlocking<Unit> {
        ready(paymentPin = false)
        sessions.start(Session("t0ken", UserDto(id = 4, name = "Amel")))
        monitor.failed(); clock.addAndGet(10_000); monitor.failed()
        assertEquals(OfflinePins.NO_PAYMENT_PIN, orders.unlockOfflinePayment(812, "1357").exceptionOrNull()!!.message)

        // A cashier signed in before the outage pays offline too, behind the same PIN (recorded as « Hors ligne »).
        store.saveOfflineAccess(OfflineAccess(9, "Hors ligne — Counter 1", secrets.seal(verifier("2468")), secrets.seal(verifier("1357")), 2, "Counter 1", 1))
        orders.unlockOfflinePayment(812, "1357").getOrThrow()
        val credit = orders.pay(812, PaymentRequest(method = "due", customerName = "Sami", customerPhone = "22000000")).exceptionOrNull()!!
        assertEquals("Hors ligne, le paiement se fait en espèces ou par carte (pas de paiement à crédit).", credit.message)
        assertTrue(orders.pay(812, PaymentRequest(method = "card")).isSuccess)
        assertEquals(9L, commands().last().actorId)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun the_pin_entry_takes_four_to_six_digits() {
        var e = PinEntry()
        "1234567x".forEach { e = e.digit(it) }
        assertEquals("123456", e.pin)
        assertEquals("12345", e.backspace().pin)
        assertEquals("", e.cleared().pin)
        assertEquals("123456", e.copy(busy = true).cleared().pin, "nothing changes while it is checked")
    }
}
