package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.CategoryDto
import com.lebarock.lebarock_desktop.data.remote.ChargeDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.ModifierGroupDto
import com.lebarock.lebarock_desktop.data.remote.ModifierOptionDto
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.remote.TaxDto
import com.lebarock.lebarock_desktop.data.remote.VariationDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.data.settings.PrintTarget
import com.lebarock.lebarock_desktop.data.settings.PrinterConfig
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.pos.Cart
import com.lebarock.lebarock_desktop.pos.CartMath
import com.lebarock.lebarock_desktop.pos.ModifierRules
import com.lebarock.lebarock_desktop.pos.OrderType
import com.lebarock.lebarock_desktop.pos.searchItems
import com.lebarock.lebarock_desktop.pos.sectionsOf
import com.lebarock.lebarock_desktop.print.PrintFailure
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3: the cart counts like the web POS (`Pos::calculateTotal`), amounts are written like the caisse
 * (`currency_format_exact`), and the orders calls print where Réglages says, against a local server
 * answering like the caisse.
 */
class PosDataTest {

    // ── Amounts ────────────────────────────────────────────────────────────

    private val dinar = CurrencyInfo(symbol = "DT", position = "right_with_space", decimals = 3)

    @Test
    fun amounts_follow_the_caisse_currency_settings() {
        assertEquals("8.630 DT", Money.format(8.63, dinar))
        assertEquals("1234.630 DT", Money.format(1234.63, dinar), "no thousand separator unless set")
        assertEquals("1 234,630 DT", Money.format(1234.63, dinar.copy(decimalSeparator = ",", thousandSeparator = " ")))
        assertEquals("DT8.630", Money.format(8.63, dinar.copy(position = "left")))
        assertEquals("€ 12.50", Money.format(12.5, CurrencyInfo(symbol = "€", position = "left_with_space", decimals = 0)), "at least 2 decimals")
        assertEquals("1.001", Money.plain(1.0005, dinar), "half up, as PHP's number_format")
        assertEquals("-2.500", Money.plain(-2.5, dinar))
        assertEquals("1 234 567.000", Money.plain(1234567.0, dinar.copy(thousandSeparator = " ")))
    }

    // ── The cart ──────────────────────────────────────────────────────────

    private val coca = MenuItemDto(id = 1, name = "Coca", price = 3.5, category = CategoryDto(10, "Boissons fraîches", 2))
    private val rhum = MenuItemDto(
        id = 2, name = "Rhum", price = 0.0, category = CategoryDto(11, "Spiritueux", 1),
        variations = listOf(VariationDto(21, "5 cl", 12.5), VariationDto(22, "1 L", 180.0)),
    )
    private val citron = ModifierOptionDto(31, "Citron", 0.5)
    private val glace = ModifierOptionDto(32, "Glaçons", 0.0)
    private val sardines = MenuItemDto(id = 3, name = "Sardines grillées", price = 3.333, category = CategoryDto(12, "Plats", null))

    @Test
    fun the_same_choice_adds_to_its_line_and_minus_stops_at_one() {
        var cart = Cart()
            .add(coca, null, emptyList())
            .add(coca, null, emptyList())
            .add(coca, null, listOf(citron))
            .add(rhum, rhum.variations[0], emptyList())
            .add(coca, null, listOf(citron))
        assertEquals(listOf(2, 2, 1), cart.lines.map { it.quantity })
        assertEquals(4.0, cart.lines[1].unitPrice)

        val first = cart.lines[0].id
        cart = cart.minus(first).minus(first)
        assertEquals(1, cart.lines[0].quantity, "« − » stops at 1; the bin takes the line off")
        cart = cart.note(first, "  bien frais ").remove(cart.lines[2].id)
        assertEquals(2, cart.lines.size)
        assertEquals("bien frais", cart.lines[0].note)

        val sent = cart.requestItems()
        assertEquals("bien frais", sent[0].note)
        assertNull(sent[1].note, "no note sends none")
        assertEquals(listOf(31L), sent[1].modifierOptionIds)
    }

    @Test
    fun totals_are_counted_like_the_web_pos() {
        val cart = Cart()
            .add(rhum, rhum.variations[0], emptyList()).add(rhum, rhum.variations[0], emptyList())
            .add(sardines, null, emptyList())
        val tva = TaxDto(1, "TVA", 7.0)
        val service = ChargeDto(5, "Service", "percent", 10.0, listOf("dine_in"))
        val emporter = ChargeDto(6, "Emballage", "fixed", 0.5, listOf("pickup"))

        val dineIn = CartMath.chargesFor(listOf(service, emporter), OrderType.DINE_IN, emptySet())
        val totals = CartMath.totals(cart.lines, listOf(tva), dineIn)
        assertEquals(2, totals.articleCount, "the web counts lines (« Total · 2 art. »)")
        assertEquals(28.33, totals.subTotal, "each line rounded to 2 decimals: 25 + 3.33")
        assertEquals(1.98, totals.taxes.single().amount)
        assertEquals(2.83, totals.charges.single().amount)
        assertEquals(33.14, totals.total)

        assertEquals(listOf(6L), CartMath.chargesFor(listOf(service, emporter), OrderType.PICKUP, emptySet()).map { it.id })
        assertTrue(CartMath.chargesFor(listOf(service), OrderType.DINE_IN, setOf(5L)).isEmpty(), "a charge taken off is not counted")
        assertTrue(CartMath.totals(emptyList(), listOf(tva), dineIn).charges.isEmpty(), "no charge on an empty cart")
    }

    @Test
    fun sections_search_and_options_follow_the_web() {
        assertEquals(listOf("Plats", "Spiritueux", "Boissons fraîches"), sectionsOf(listOf(coca, rhum, sardines)).map { it.name },
            "Menu → Tri order, sections not sorted first")
        assertEquals(listOf("Sardines grillées"), searchItems(listOf(coca, rhum, sardines), "GRILLEES").map { it.name })

        val group = ModifierGroupDto(7, "Accompagnement", isRequired = true, allowMultipleSelection = true, minSelections = 1, maxSelections = 1, options = listOf(citron, glace))
        val item = coca.copy(modifierGroups = listOf(group))
        assertEquals(group, ModifierRules.missing(item, emptyMap()))
        val picked = ModifierRules.toggle(group, 31, emptySet())
        assertEquals(setOf(31L), ModifierRules.toggle(group, 32, picked), "the maximum is kept")
        assertNull(ModifierRules.missing(item, mapOf(7L to picked)))
    }

    // ── Calls to the caisse ───────────────────────────────────────────────

    private val dir: File = Files.createTempDirectory("altospos-pos").toFile()
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val sessions = SessionHolder().apply { start(Session("t0ken", UserDto(id = 4, name = "Amel"))) }
    private val client = ApiClient({ sessions.token })
    private val server = MockWebServer().also { it.start() }
    private val printed = mutableListOf<ByteArray>()
    private var printerDown = false
    private val ticketPrinter = TicketPrinter(settings) {
        TicketTransport { bytes ->
            if (printerDown) throw PrintFailure("Imprimante « EPSON » introuvable dans Windows.")
            printed += bytes
            "Ticket envoyé à « EPSON »."
        }
    }
    private val orders = OrdersRepository(CaisseRepository(client, settings, sessions), settings, ticketPrinter, client.json)

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    private fun configure(target: PrintTarget) = settings.update {
        it.copy(server = ServerAddress(false, server.hostName, server.port), printer = PrinterConfig(target = target, windowsPrinter = "EPSON TM-T20III"))
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val summary = """{"id":42,"order_number":"42","status":"billed","total":71,"sub_total":71,"amount_paid":0,"table_id":3}"""
    private val receipt = """{"type":"sunmi","url":"http://localhost:9100/print","token":"x","receipt":{"restaurant":{"name":"Le Baroque"},
        "order":{"number":"Commande n° 42","dateTime":"08/11/2026 15:49"},"items":[{"qty":"2","name":"Sardines grillées","price":"18.000","amount":"36.000"}],
        "total":{"label":"Total","value":"71.000 DT"}}}"""

    @Test
    fun imprimer_ticket_on_this_device_prints_the_caisse_ticket_here() = runBlocking<Unit> {
        configure(PrintTarget.DEVICE)
        server.enqueue(json("""{"status":"success","data":$summary,"print":$receipt,"print_result":{"target":"device","status":"device"}}"""))

        val sent = orders.sendOrder(SendOrderRequest(tableId = 3, numberOfPax = 2, waiterId = 7, note = "allergie", action = "bill_print",
            items = Cart().add(sardines, null, emptyList()).requestItems(), removedChargeIds = listOf(5))).getOrThrow()

        assertEquals(42L, sent.value.id)
        assertTrue(sent.print!!.ok)
        assertEquals("Ticket envoyé à « EPSON ».", sent.print!!.message)
        assertTrue(String(printed.single(), Charsets.ISO_8859_1).contains("Sardines"), "the caisse's ticket, laid out here")

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("device", body["print_target"]!!.jsonPrimitive.content)
        assertEquals("7", body["waiter_id"].toString())
        assertEquals("[5]", body["removed_charge_ids"].toString())
        assertEquals("allergie", body["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_ticket_that_did_not_come_out_still_leaves_the_bill_made() = runBlocking<Unit> {
        configure(PrintTarget.DEVICE)
        printerDown = true
        server.enqueue(json("""{"status":"success","data":$summary,"print":$receipt}"""))
        val billed = orders.bill(42, print = true).getOrThrow()
        assertEquals("billed", billed.value.status)
        assertFalse(billed.print!!.ok)
        assertTrue(billed.print!!.message.contains("introuvable"))

        configure(PrintTarget.CAISSE)
        server.enqueue(json("""{"status":"success","data":$summary,"print":null,"print_result":{"target":"caisse","status":"failed","code":"print_unavailable","message":"Aucune imprimante pour la caisse « Caisse »."}}"""))
        val viaCaisse = orders.bill(42, print = true).getOrThrow()
        assertFalse(viaCaisse.print!!.ok)
        assertEquals("Aucune imprimante pour la caisse « Caisse ».", viaCaisse.print!!.message)
        server.takeRequest()
        assertEquals("caisse", Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["print_target"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_round_the_stock_holds_back_comes_back_with_what_to_do() = runBlocking<Unit> {
        configure(PrintTarget.DEVICE)
        server.enqueue(json("""{"status":"error","code":"stock_waiting","message":"Stock insuffisant — Bar : Coca (il faut 2 pcs, reste 1 pcs).","order_id":42,"can_force":true}""", 422))

        val failure = orders.addItems(42, com.lebarock.lebarock_desktop.data.remote.AddItemsRequest(items = Cart().add(coca, null, emptyList()).requestItems())).exceptionOrNull() as ApiFailure

        assertTrue(failure.stockWaiting)
        assertEquals(42L, failure.orderId)
        assertTrue(failure.canForce)
        assertTrue(failure.message!!.startsWith("Stock insuffisant"))
    }

    @Test
    fun the_pos_context_brings_the_currency_as_set_now() = runBlocking<Unit> {
        configure(PrintTarget.DEVICE)
        server.enqueue(json("""{"status":"success","data":{"order_types":{"dine_in":true,"pickup":true},"next_order_number":"43",
            "currency":{"code":"TND","symbol":"DT","position":"right_with_space","decimals":3,"decimal_separator":",","thousand_separator":" "},
            "taxes":[{"id":1,"name":"TVA","percent":7}],"charges":[{"id":5,"name":"Service","type":"percent","value":10,"order_types":["dine_in"]}],
            "waiters":[{"id":7,"name":"Karim"}]}}"""))

        val context = orders.posContext().getOrThrow()

        assertTrue(context.orderTypes.pickup)
        assertEquals("43", context.nextOrderNumber)
        assertEquals("Service", context.charges.single().name)
        assertEquals(",", settings.current.currency.decimalSeparator, "kept for the screens that do not read the context")
        assertEquals("8,630 DT", Money.format(8.63, orders.currency))
    }

    @Test
    fun cancel_sends_the_reason_and_a_refused_reprint_says_why() = runBlocking<Unit> {
        configure(PrintTarget.CAISSE)
        server.enqueue(json("""{"status":"success"}"""))
        orders.cancel(42, reasonId = 3, reason = "  parti sans manger ").getOrThrow()
        val cancel = server.takeRequest()
        assertEquals("/api/mobile/orders/42/cancel", cancel.url.encodedPath)
        assertEquals("""{"reason_id":3,"reason":"parti sans manger"}""", cancel.body!!.utf8())

        server.enqueue(json("""{"status":"error","code":"print_unavailable","message":"Aucune imprimante pour la caisse « Caisse ».","print_result":{"status":"failed"}}""", 422))
        val refused = orders.reprint(42).exceptionOrNull() as ApiFailure
        assertEquals("Aucune imprimante pour la caisse « Caisse ».", refused.message)

        server.enqueue(json("""{"status":"success","data":null,"closed":true}"""))
        assertEquals(com.lebarock.lebarock_desktop.data.LineRemoval.DELETED, orders.removeItem(42, 9).getOrThrow(), "the last waiting line gone: the check is gone")
    }
}
