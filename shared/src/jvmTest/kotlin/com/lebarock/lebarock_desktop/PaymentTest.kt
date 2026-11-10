package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.PaymentContextDto
import com.lebarock.lebarock_desktop.data.remote.PaymentCustomerDto
import com.lebarock.lebarock_desktop.data.remote.SplitItemDto
import com.lebarock.lebarock_desktop.data.remote.TaxDto
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.pos.Cart
import com.lebarock.lebarock_desktop.pos.CartMath
import com.lebarock.lebarock_desktop.pos.Discount
import com.lebarock.lebarock_desktop.pos.PayMethod
import com.lebarock.lebarock_desktop.pos.PaymentForm
import com.lebarock.lebarock_desktop.pos.SplitType
import com.lebarock.lebarock_desktop.pos.TipForm
import com.lebarock.lebarock_desktop.print.TicketPrinter
import com.lebarock.lebarock_desktop.print.TicketTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D5 (client, 2026-11-10: "follow the web logic"): the cart's « Remise » with the web's arithmetic, the web's payment
 * window (methods, keypad, change, credit, the three splits, the tip) and the calls it makes to the caisse.
 */
class PaymentTest {

    // ── « Remise » in the cart (web `Pos::calculateTotal`) ─────────────────

    private val plat = MenuItemDto(id = 1, name = "Couscous", price = 40.0)
    private val tva = TaxDto(id = 1, name = "TVA", percent = 19.0)

    @Test
    fun the_carts_discount_comes_off_the_subtotal_before_taxes() {
        val lines = Cart().add(plat, null, emptyList()).plus(1).lines // 2 × 40 = 80
        val percent = CartMath.totals(lines, listOf(tva), emptyList(), Discount(Discount.PERCENT, 10.0))
        assertEquals(8.0, percent.discount)
        assertEquals(CartMath.round2(72.0 + 72.0 * 0.19), percent.total, "taxes on 72, not 80")

        val fixed = CartMath.totals(lines, emptyList(), emptyList(), Discount(Discount.FIXED, 500.0))
        assertEquals(80.0, fixed.discount, "a fixed discount is capped at the sub-total")
        assertEquals(0.0, fixed.total)

        assertEquals(0.0, CartMath.totals(emptyList(), emptyList(), emptyList(), Discount(Discount.FIXED, 5.0)).discount, "nothing without lines")
        assertEquals(CartMath.round2(80 * 1.19), CartMath.totals(lines, listOf(tva), emptyList()).total)
    }

    // ── The window ───────────────────────────────────────────────────────

    private fun context(due: Double = 45.5, credit: Boolean = false, items: List<SplitItemDto> = emptyList(), paid: Double = 0.0) = PaymentContextDto(
        orderId = 42, orderNumber = "42", status = "billed", total = due + paid, subTotal = due + paid, amountPaid = paid, due = due,
        paymentMethods = if (credit) listOf("cash", "card", "due") else listOf("cash", "card"), canGiveCredit = credit, items = items,
    )

    @Test
    fun cash_starts_on_the_due_and_the_keypad_types_a_new_amount() {
        var f = PaymentForm(context())
        assertEquals("45.5", f.amountText)
        assertEquals(45.5, f.amount)
        assertEquals(0.0, f.change)

        f = f.key('5').key('0')
        assertEquals("50", f.amountText, "the first key replaces the amount filled in")
        assertEquals(4.5, f.change, "change given back")
        assertEquals(0.0, f.balance)

        f = f.key('.').key('2').key('5').key('9')
        assertEquals("50.25", f.amountText, "two decimals at most")
        f = f.backspace().clearAmount().key('2').key('0')
        assertEquals(20.0, f.amount)
        assertEquals(25.5, f.balance, "a partial payment: the rest stays to pay")
        assertEquals(PayMethod.CASH.api, f.request().method)
        assertEquals(20.0, f.request().amount)

        assertEquals(100.0, f.quick(100.0).amount)
        assertEquals(45.5, f.quick(100.0).exact().amount, "« Montant exact »")
    }

    @Test
    fun the_tpe_and_the_credit_take_the_whole_due() {
        val card = PaymentForm(context()).key('1').choose(PayMethod.CARD)
        assertTrue(card.amountLocked)
        assertEquals(45.5, card.amount)
        assertEquals(card, card.key('9'), "the keypad is greyed")
        assertNull(card.request().amount, "the caisse records the whole due on the TPE")

        val noRight = PaymentForm(context())
        assertEquals(listOf(PayMethod.CASH, PayMethod.CARD), noRight.methods)
        assertEquals(PayMethod.CASH, noRight.choose(PayMethod.CREDIT).method, "« À crédit » needs « Give Credit »")

        val credit = PaymentForm(context(credit = true)).choose(PayMethod.CREDIT)
        assertEquals("Pour un paiement à crédit, indiquez le client (nom et téléphone).", credit.problem)
        val typed = credit.creditChanged(credit.credit.copy(name = "Karim", phone = "22333444"))
        assertNull(typed.problem)
        assertEquals("Karim", typed.request().customerName)
        assertEquals("due", typed.request().method)

        val known = PaymentForm(context(credit = true).copy(customer = PaymentCustomerDto(9, "Leila", "22111333"))).choose(PayMethod.CREDIT)
        assertEquals(9L, known.request().customerId, "the check's customer is offered first")
    }

    @Test
    fun an_equal_split_puts_the_cents_on_the_first_part() {
        val f = PaymentForm(context(due = 100.0)).splitMode(true).splitType(SplitType.EQUAL).addEqualPart().equalMethod(1, PayMethod.CARD)
        assertEquals(listOf(33.34, 33.33, 33.33), f.equalAmounts)
        val split = f.request().split!!
        assertEquals("equal", split.type)
        assertEquals(listOf("cash", "card", "cash"), split.splits.map { it.paymentMethod })
        assertEquals(100.0, CartMath.round2(split.splits.sumOf { it.amount!! }))
        assertEquals(2, f.addEqualPart().removeEqualPart(0).removeEqualPart(0).equalParts.size, "two parts at least")
    }

    @Test
    fun custom_amounts_must_make_the_due() {
        var f = PaymentForm(context(due = 60.0)).splitMode(true)
        assertEquals("Choisissez comment partager l'addition.", f.problem)
        f = f.splitType(SplitType.CUSTOM).customAmount(0, "20").customAmount(1, "30")
        assertEquals(10.0, f.customBalance)
        assertEquals("La somme des parts ne correspond pas au montant dû.", f.problem)
        f = f.customAmount(1, "40").customMethod(1, PayMethod.CARD)
        assertNull(f.problem)
        assertEquals(listOf(20.0, 40.0), f.request().split!!.splits.map { it.amount })
    }

    @Test
    fun a_split_by_article_shares_each_line_once() {
        val items = listOf(SplitItemDto(11, "Couscous", 2, 23.8), SplitItemDto(12, "Coca", 1, 4.17))
        var f = PaymentForm(context(due = 51.77, items = items)).splitMode(true).splitType(SplitType.ITEMS)
        f = f.addToPart(items[0], 1).addToPart(items[1], 5)
        assertEquals(1, f.activePart!!.lines[12], "never more than the line has")
        f = f.addItemsPart().addToPart(items[0], 5)
        assertEquals(1, f.activePart!!.lines[11], "what the first part took is gone")
        assertEquals(0, f.remaining(items[0]))
        assertEquals(CartMath.round2(23.8 + 4.17), f.partTotal(f.itemParts.first()))
        f = f.itemsMethod(2, PayMethod.CARD)
        val split = f.request().split!!
        assertEquals("items", split.type)
        assertEquals(listOf("cash", "card"), split.splits.map { it.paymentMethod })
        assertEquals(23.8, split.splits[1].items!!.single().price)
        f = f.takeFromPart(11)
        assertEquals(1, f.remaining(items[0]))
        assertEquals(1, f.removeItemsPart(2).itemParts.size)
    }

    @Test
    fun the_tip_window_works_on_the_total_before_the_tip() {
        val t = TipForm(baseTotal = 80.0).quick(10.0)
        assertEquals(8.0, t.amount)
        assertEquals(88.0, t.newTotal)
        assertEquals(5.0, t.copy(percentMode = false).quick(5.0).amount)
        assertEquals(0.0, t.typed("").amount, "0 takes the tip off")
    }

    // ── Calls to the caisse ───────────────────────────────────────────────

    private val dir: File = Files.createTempDirectory("altospos-pay").toFile()
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val sessions = SessionHolder().apply { start(Session("t0ken", UserDto(id = 4, name = "Amel"))) }
    private val client = ApiClient({ sessions.token })
    private val server = MockWebServer().also { it.start() }
    private val printed = mutableListOf<ByteArray>()
    private val orders = OrdersRepository(
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

    private val receipt = """{"type":"sunmi","url":"http://localhost:9100/print","token":"x","receipt":{"restaurant":{"name":"Le Baroque"},
        "order":{"number":"Commande n° 42"},"items":[{"qty":"1","name":"Couscous","price":"45.500","amount":"45.500"}],
        "total":{"label":"Total","value":"45.500 DT"}}}"""

    @Test
    fun a_payment_that_settles_prints_the_receipt_here() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","data":{"order_id":42,"status":"paid","total":45.5,"amount_paid":45.5,"due":0,"change":4.5},"print":$receipt,"print_result":{"target":"device","status":"device"}}"""))
        val paid = orders.pay(42, PaymentForm(context()).key('5').key('0').request()).getOrThrow()
        assertEquals("paid", paid.value.status)
        assertEquals(4.5, paid.value.change)
        assertTrue(paid.print!!.ok)
        assertTrue(String(printed.single(), Charsets.ISO_8859_1).contains("Couscous"), "the receipt, laid out here")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("cash", body["method"]!!.jsonPrimitive.content)
        assertEquals("50.0", body["amount"].toString())
        assertEquals("device", body["print_target"]!!.jsonPrimitive.content, "Réglages → « Impression des tickets »")

        // A part paid: the check stays open, no receipt.
        server.enqueue(json("""{"status":"success","data":{"order_id":42,"status":"billed","total":45.5,"amount_paid":20,"due":25.5,"change":0},"print":null}"""))
        val part = orders.pay(42, PaymentForm(context()).splitMode(true).splitType(SplitType.CUSTOM).customAmount(0, "20").customAmount(1, "25.5").request()).getOrThrow()
        assertNull(part.print)
        val split = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["split"]!!.jsonObject
        assertEquals("custom", split["type"]!!.jsonPrimitive.content)
        assertEquals(2, split["splits"]!!.jsonArray.size)
    }

    @Test
    fun the_tip_and_the_discount_go_to_their_calls_and_a_refusal_reads_as_written() = runBlocking<Unit> {
        server.enqueue(json("""{"status":"success","data":{"order_id":42,"total":49.5,"tip_amount":4}}"""))
        orders.tip(42, 4.0, " Merci ").getOrThrow()
        val tip = server.takeRequest()
        assertEquals("/api/mobile/orders/42/tip", tip.url.encodedPath)
        assertEquals("Merci", Json.parseToJsonElement(tip.body!!.utf8()).jsonObject["note"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"status":"success","message":"Remise appliquée.","data":{"id":42,"order_number":"42","status":"kot","discount_type":"percent","discount_value":10,"discount_amount":4}}"""))
        val change = orders.setDiscount(42, Discount.PERCENT, 10.0).getOrThrow()
        assertEquals("Remise appliquée.", change.message)
        assertEquals(4.0, change.order.discountAmount)
        val d = server.takeRequest()
        assertEquals("/api/mobile/orders/42/discount", d.url.encodedPath)
        assertEquals("percent", Json.parseToJsonElement(d.body!!.utf8()).jsonObject["type"]!!.jsonPrimitive.content)

        server.enqueue(json("""{"status":"success","message":"Remise retirée.","data":{"id":42,"order_number":"42","status":"kot"}}"""))
        orders.setDiscount(42, null, null).getOrThrow()
        val off = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse(off.containsKey("value") && off["value"].toString() != "null")

        server.enqueue(json("""{"status":"error","message":"Le paiement par carte ne peut pas dépasser le reste à payer (12.000 DT)."}""", 422))
        val refused = orders.pay(42, PaymentForm(context()).choose(PayMethod.CARD).request()).exceptionOrNull() as ApiFailure
        assertEquals("Le paiement par carte ne peut pas dépasser le reste à payer (12.000 DT).", refused.message)
    }
}
