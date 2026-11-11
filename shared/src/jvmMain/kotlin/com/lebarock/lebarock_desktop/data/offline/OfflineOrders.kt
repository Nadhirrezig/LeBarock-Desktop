package com.lebarock.lebarock_desktop.data.offline

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.FailureKind
import com.lebarock.lebarock_desktop.data.PrintOutcome
import com.lebarock.lebarock_desktop.data.Printed
import com.lebarock.lebarock_desktop.data.remote.ActiveOrderDto
import com.lebarock.lebarock_desktop.data.remote.AddItemsRequest
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.CaisseDto
import com.lebarock.lebarock_desktop.data.remote.PaymentContextDto
import com.lebarock.lebarock_desktop.data.remote.PaymentRequest
import com.lebarock.lebarock_desktop.data.remote.PaymentResultDto
import com.lebarock.lebarock_desktop.data.remote.SplitItemDto
import com.lebarock.lebarock_desktop.data.remote.KotDto
import com.lebarock.lebarock_desktop.data.remote.MenuDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.ModifierOptionDto
import com.lebarock.lebarock_desktop.data.remote.OrderActionDto
import com.lebarock.lebarock_desktop.data.remote.OrderCountsDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.OrderDto
import com.lebarock.lebarock_desktop.data.remote.OrderItemDto
import com.lebarock.lebarock_desktop.data.remote.OrderRefDto
import com.lebarock.lebarock_desktop.data.remote.OrderTypesDto
import com.lebarock.lebarock_desktop.data.remote.OrdersResponse
import com.lebarock.lebarock_desktop.data.remote.PosContextDto
import com.lebarock.lebarock_desktop.data.remote.SendOrderItem
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.remote.TableSessionDto
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.print.KitchenTicketLine
import com.lebarock.lebarock_desktop.print.KitchenTicketPrint
import com.lebarock.lebarock_desktop.print.ReceiptFooter
import com.lebarock.lebarock_desktop.print.ReceiptItem
import com.lebarock.lebarock_desktop.print.ReceiptOrder
import com.lebarock.lebarock_desktop.print.ReceiptPayment
import com.lebarock.lebarock_desktop.print.ReceiptPayload
import com.lebarock.lebarock_desktop.print.ReceiptRestaurant
import com.lebarock.lebarock_desktop.print.ReceiptRow
import com.lebarock.lebarock_desktop.print.TicketPrinter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** One line taken offline, with the outbox action that sends it to the caisse (the handheld's LocalLine). */
@Serializable
data class LocalLine(
    @SerialName("line_id") val lineId: String,
    val round: Int,
    @SerialName("command_id") val commandId: String,
    @SerialName("menu_item_id") val menuItemId: Long,
    @SerialName("variation_id") val variationId: Long? = null,
    val name: String,
    val variation: String? = null,
    val quantity: Int,
    val price: Double,
    val amount: Double,
    val modifiers: List<ModifierOptionDto> = emptyList(),
    val note: String? = null,
    @SerialName("station_id") val stationId: Long? = null,
    val at: Long,
)

/** One payment taken offline (step 3): what stays in the drawer ([amount]) and the change given back. */
@Serializable
data class LocalPayment(
    @SerialName("command_id") val commandId: String,
    val method: String,
    val amount: Double,
    val change: Double = 0.0,
    @SerialName("split_type") val splitType: String? = null,
    val at: Long,
)

/**
 * This PC's own orders while the caisse does not answer (offline step 2), the handheld's OfflineOrders.
 *
 * The screens keep calling OrdersRepository; while offline, or while actions still wait to be sent (outbox first),
 * it answers from here:
 *  - reads come from the copy (menu, tables, open checks) with the rounds taken offline added on top;
 *  - a new order gets its temporary number (« D1-007 »), a round joins the table's open check (from the copy, or one
 *    taken offline), the bill is made; each becomes an outbox action, recorded as the Caisse's « Hors ligne » account
 *    (client, 2026-11-10: as on the handheld), sent to the caisse when it answers;
 *  - kitchen tickets always print on this PC's printer (Réglages), one per station, whatever the Automatique /
 *    Manuel setting and whatever Réglages says about bills (client, 2026-11-10); the bill prints there too.
 *  - payments (step 3): cash with change, card, the bill divided (equal, amounts, by article), behind the manager's
 *    payment PIN typed once per check for 5 minutes (client, 2026-11-10); the receipt prints here once settled.
 * Everything else waits for the caisse with a French message (client, 2026-11-10): credit, tip, discount, charges,
 * waiter, table operations, cancel, the Caisse page.
 */
class OfflineOrders(
    private val store: OfflineStore,
    private val sync: OfflineSync,
    private val sessions: SessionHolder,
    private val printer: TicketPrinter,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val linesSerializer = ListSerializer(LocalLine.serializer())
    private val paymentsSerializer = ListSerializer(LocalPayment.serializer())

    @Volatile
    private var cached: Triple<Long, Long?, OfflineCatalog>? = null

    companion object {
        const val NOT_CONFIGURED =
            "Mode hors ligne non configuré : le gérant doit définir le code PIN hors ligne de cette caisse (Paramètres → Mode hors ligne sur la caisse), puis un compte doit se connecter une fois sur ce poste."
        const val NO_COPY =
            "Aucune donnée hors ligne sur ce poste : connectez-vous une fois quand la caisse répond pour les télécharger."
        const val NOT_REGISTERED =
            "Ce poste n'est pas encore enregistré par la caisse : connectez-vous une fois quand la caisse répond."
        const val NOT_FOUND = "Commande introuvable dans les données hors ligne de ce poste."
        const val STOCK_WAITING =
            "Des articles de cette commande attendent le stock : l'addition attendra le retour de la caisse."

        /** The code of every refusal made here: the screens can tell it from the caisse's own refusals. */
        const val OFFLINE_CODE = "offline"

        /** The payment window asks the payment PIN when it gets this code (step 3). */
        const val PAYMENT_PIN_REQUIRED = "payment_pin_required"

        /** The payment PIN opens payments on a check for 5 minutes (client, 2026-11-10: as on the handheld). */
        const val PAYMENT_UNLOCK_MS = 5 * 60_000L

        /** Payments within a cent of the due settle it (the caisse's OrderPayments). */
        const val TOLERANCE = 0.0101

        /** The id the screens use for an order taken offline: negative, never −1 (the screens' « no order »). */
        fun screenId(localId: Long): Long = -(localId + 1)

        fun localIdOf(screenId: Long): Long = -screenId - 1

        fun refusalOf(message: String) = ApiFailure(message, FailureKind.REFUSED, code = OFFLINE_CODE)

        /** « Hors ligne : … n'est pas possible tant que la caisse ne répond pas. » (client, 2026-11-10). */
        fun waitsForCaisse(what: String) = refusalOf("Hors ligne : $what n'est pas possible tant que la caisse ne répond pas.")
    }

    /**
     * The screens work from here: offline, or while earlier actions still wait to be sent (outbox first, so the
     * caisse gets everything in order). Step 3 adds the « Hors ligne » sign-in.
     */
    suspend fun active(): Boolean {
        val session = sessions.current ?: return false
        // Signed in « Hors ligne »: always from here (no token to call the caisse with).
        if (session.offline) return true
        if (session.rights.kitchenOnly) return false
        return sync.mustQueue()
    }

    /** Payments opened on a check by the payment PIN, and until when. */
    @Volatile
    private var paymentUnlocked: Pair<Long, Long>? = null

    /** The payment PIN was right: payments on [orderId] are open for [PAYMENT_UNLOCK_MS]. */
    fun unlockPayments(orderId: Long) {
        paymentUnlocked = orderId to (now() + PAYMENT_UNLOCK_MS)
    }

    private fun paymentsOpen(orderId: Long): Boolean =
        paymentUnlocked?.let { (id, until) -> id == orderId && now() < until } == true

    fun <T> unavailable(what: String): Result<T> = Result.failure(waitsForCaisse(what))

    private fun refusal(message: String): Nothing = throw refusalOf(message)

    private suspend fun <T> offline(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ApiFailure) {
        Result.failure(e)
    } catch (e: Exception) {
        AppLog.warn("Hors ligne : action impossible", e)
        Result.failure(refusalOf("Hors ligne : ${e.message ?: "erreur"}"))
    }

    private suspend fun catalog(): OfflineCatalog {
        val part = store.snapshotPart("static") ?: refusal(NO_COPY)
        // O4a: the branch's copy; the session's Caisse picks its menu and its « Hors ligne » account.
        val caisseId = sessions.current?.caisse?.id
        cached?.let { (savedAt, forCaisse, catalog) -> if (savedAt == part.savedAt && forCaisse == caisseId) return catalog }
        val catalog = OfflineCatalog(json.parseToJsonElement(part.json).jsonObject, json, caisseId)
        cached = Triple(part.savedAt, caisseId, catalog)
        return catalog
    }

    // ---------------------------------------------------------------- reads

    suspend fun menus(): Result<List<MenuDto>> = offline { catalog().menus }

    suspend fun menuItems(menuId: Long?): Result<List<MenuItemDto>> = offline {
        catalog().items.filter { menuId == null || it.menuId == menuId }
    }

    /**
     * The cart's make-up offline: the copy's taxes, charges and currency; the order types as the caisse last said
     * them; no waiter to pick (everything is recorded as « Hors ligne »); the next temporary number.
     */
    suspend fun posContext(): Result<PosContextDto> = offline {
        val catalog = catalog()
        val last = store.snapshotPart(OfflineSync.POS_CONTEXT_PART)?.let { part ->
            runCatching { json.decodeFromString(PosContextDto.serializer(), part.json) }.getOrNull()
        }
        val device = store.device()
        PosContextDto(
            orderTypes = last?.orderTypes ?: OrderTypesDto(dineIn = true, pickup = true),
            nextOrderNumber = device?.code?.let { "%s-%03d".format(it, device.nextOfflineNumber) },
            currency = catalog.currencyDto,
            taxes = catalog.taxes,
            charges = catalog.charges,
            waiters = emptyList(),
        )
    }

    /** The floor offline: the copy's tables and parties, with the checks as this PC sees them now. */
    suspend fun tables(): Result<List<AreaDto>> = offline {
        val catalog = catalog()
        val states = store.tableStates().associateBy { it.id }
        val orders = openOrders(catalog)

        catalog.areas.map { area ->
            AreaDto(
                id = area.id,
                name = area.name,
                tables = area.tables.map { table ->
                    val onTable = orders.filter { it.table?.id == table.id }
                    val order = onTable.firstOrNull()
                    val state = states[table.id]
                    val session = state?.let { runCatching { json.parseToJsonElement(it.json).jsonObject["session"] }.getOrNull() }
                        ?.let { element -> runCatching { json.decodeFromJsonElement(TableSessionDto.serializer(), element) }.getOrNull() }
                    TableDto(
                        id = table.id,
                        code = table.code,
                        seats = table.seats,
                        status = if (order != null) "running" else state?.status?.takeIf { it != "running" } ?: "available",
                        session = session,
                        activeOrder = order?.let {
                            ActiveOrderDto(
                                id = it.id, orderNumber = it.orderNumber, status = it.status, total = it.total,
                                kotCount = it.kots.size, itemsCount = it.items.size, numberOfPax = it.numberOfPax,
                            )
                        },
                        openOrders = onTable.size,
                        openTotal = OfflineCatalog.round2(onTable.sumOf { it.total }),
                        stockWaiting = onTable.any { it.stockWaiting != null },
                    )
                },
            )
        }
    }

    /** Commandes offline: the open checks only (the copy has no paid or cancelled ones). */
    suspend fun orders(status: String?, toPay: Boolean): Result<OrdersResponse> = offline {
        val all = openOrders(catalog())
        val list = all.filter {
            when {
                toPay -> it.status == "billed" || it.status == "payment_due"
                status.isNullOrBlank() -> true
                else -> it.status == status
            }
        }.map { it.summary() }
        OrdersResponse(
            counts = OrderCountsDto(
                all = all.size,
                kot = all.count { it.status == "kot" },
                billed = all.count { it.status == "billed" },
                toPay = all.count { it.status == "billed" || it.status == "payment_due" },
            ),
            data = list,
        )
    }

    suspend fun orderDetail(id: Long): Result<OrderDetailDto> = offline {
        detail(catalog(), id) ?: refusal(NOT_FOUND)
    }

    /** The real check at the caisse of an order taken offline (its negative id), once the caisse has it. */
    suspend fun syncedIdOf(screenId: Long): Long? = if (screenId >= 0) screenId else store.localOrder(localIdOf(screenId))?.syncedOrderId

    /** The open checks as the screens see them offline: the copy with the rounds taken offline, and the new orders. */
    private suspend fun openOrders(catalog: OfflineCatalog): List<OrderDetailDto> {
        val pending = store.outboxIds().toSet()
        val locals = store.localOrders()
        val copies = copyChecks()

        val merged = copies.map { check ->
            val local = locals.firstOrNull { (it.baseOrderId ?: it.syncedOrderId) == check.id }
            if (local == null) check else merge(catalog, check, local, pendingLines(local, pending), pending)
        }
        val standalone = locals
            .filter { it.baseOrderId == null && (it.syncedOrderId == null || copies.none { c -> c.id == it.syncedOrderId }) }
            .filter { it.syncedOrderId == null || pendingLines(it, pending).isNotEmpty() }
            .map { standalone(catalog, it, lines(it)) }

        // Open = not settled or cancelled (the caisse's Order::OPEN_STATUSES).
        return (merged + standalone).filter { it.status !in setOf("paid", "canceled", "payment_due") }
    }

    private suspend fun detail(catalog: OfflineCatalog, id: Long): OrderDetailDto? {
        val pending = store.outboxIds().toSet()
        val copies = copyChecks()

        if (id < 0) {
            val local = store.localOrder(localIdOf(id)) ?: return null
            val base = (local.baseOrderId ?: local.syncedOrderId)?.let { baseId -> copies.firstOrNull { it.id == baseId } }
            return if (base != null) merge(catalog, base, local, pendingLines(local, pending), pending) else standalone(catalog, local, lines(local))
        }

        val base = copies.firstOrNull { it.id == id }
        val local = store.localOrders().firstOrNull { (it.baseOrderId ?: it.syncedOrderId) == id }
        return when {
            base != null && local != null -> merge(catalog, base, local, pendingLines(local, pending), pending)
            base != null -> base
            local != null -> standalone(catalog, local, lines(local))
            else -> null
        }
    }

    /**
     * The copy's open checks: every table's, and the « À emporter » of this session's Caisse only (O4a: the branch's
     * copy carries every Caisse's, each with its `caisse_id`).
     */
    private suspend fun copyChecks(): List<OrderDetailDto> {
        val caisseId = sessions.current?.caisse?.id
        return store.openChecks().mapNotNull { row ->
            val check = runCatching { json.decodeFromString(OrderDetailDto.serializer(), row.json) }.getOrNull() ?: return@mapNotNull null
            val owner = runCatching { json.parseToJsonElement(row.json).jsonObject["caisse_id"]?.jsonPrimitive?.longOrNull }.getOrNull()
            check.takeIf { it.table != null || owner == null || caisseId == null || owner == caisseId }
        }
    }

    private fun lines(local: LocalOrderEntity): List<LocalLine> =
        runCatching { json.decodeFromString(linesSerializer, local.linesJson) }.getOrDefault(emptyList())

    private fun payments(local: LocalOrderEntity): List<LocalPayment> =
        local.paymentsJson?.let { runCatching { json.decodeFromString(paymentsSerializer, it) }.getOrNull() }.orEmpty()

    /** The local lines the caisse does not have yet (their action still in the outbox). */
    private fun pendingLines(local: LocalOrderEntity, pending: Set<String>): List<LocalLine> = lines(local).filter { it.commandId in pending }

    private fun merge(catalog: OfflineCatalog, base: OrderDetailDto, local: LocalOrderEntity, lines: List<LocalLine>, pending: Set<String>): OrderDetailDto {
        // What was paid offline and is not at the caisse yet (step 3).
        val paid = payments(local).filter { it.commandId in pending }.sumOf { it.amount }
        if (lines.isEmpty() && paid == 0.0 && local.status == base.status) return base
        val subTotal = base.subTotal + lines.sumOf { it.amount }
        val totals = catalog.totals(subTotal, base.discountType, base.discountValue, base.tipAmount ?: 0.0, base.orderType)
        return base.copy(
            status = local.status,
            subTotal = totals.subTotal,
            total = if (lines.isEmpty()) base.total else totals.total,
            items = base.items + lines.map { line -> line.toItem(local, indexOf(local, line)) },
            kots = base.kots + rounds(local, lines),
            itemsCount = base.items.size + lines.size,
            offlineNumber = base.offlineNumber ?: local.offlineNumber,
            amountPaid = OfflineCatalog.round2(base.amountPaid + paid),
        )
    }

    private fun standalone(catalog: OfflineCatalog, local: LocalOrderEntity, lines: List<LocalLine>): OrderDetailDto {
        val totals = catalog.totals(lines.sumOf { it.amount }, null, null, 0.0, local.orderType)
        return OrderDetailDto(
            id = screenId(local.localId),
            orderNumber = local.syncedNumber ?: local.offlineNumber.orEmpty(),
            orderType = local.orderType,
            status = local.status,
            orderStatus = "served",
            dateTime = Instant.ofEpochMilli(local.createdAt).toString(),
            numberOfPax = local.pax,
            subTotal = totals.subTotal,
            total = totals.total,
            amountPaid = OfflineCatalog.round2(payments(local).sumOf { it.amount }),
            itemsCount = lines.size,
            table = local.tableId?.let { OrderRefDto(it, local.tableCode) },
            waiter = catalog.offlineAccountId?.let { OrderRefDto(it, name = catalog.offlineAccountName) },
            terminal = catalog.caisseName,
            items = lines.mapIndexed { i, line -> line.toItem(local, i) },
            kots = rounds(local, lines),
            offlineNumber = local.offlineNumber,
        )
    }

    /** A local line's place among all its order's lines: its item id stays the same whatever is shown. */
    private fun indexOf(local: LocalOrderEntity, line: LocalLine): Int = lines(local).indexOfFirst { it.lineId == line.lineId }.coerceAtLeast(0)

    private fun LocalLine.toItem(local: LocalOrderEntity, index: Int) = OrderItemDto(
        id = -(local.localId * 10_000 + index + 1),
        name = name,
        variation = variation,
        quantity = quantity,
        price = price,
        amount = amount,
        note = note,
        kotId = -(local.localId * 100 + round),
        modifiers = modifiers,
        stockState = "taken",
        fired = stationId != null,
    )

    /** Each round taken offline reads as one kitchen round, « Imprimé hors ligne ». */
    private fun rounds(local: LocalOrderEntity, lines: List<LocalLine>): List<KotDto> =
        lines.groupBy { it.round }.toSortedMap().map { (round, roundLines) ->
            KotDto(
                id = -(local.localId * 100 + round),
                number = "${local.offlineNumber ?: local.ref} · tour $round",
                status = "Imprimé hors ligne",
                itemsCount = roundLines.size,
                createdAt = Instant.ofEpochMilli(roundLines.first().at).toString(),
            )
        }

    private fun OrderDetailDto.summary() = OrderDto(
        id = id, uuid = uuid, orderNumber = orderNumber, orderType = orderType, status = status, orderStatus = orderStatus,
        dateTime = dateTime, numberOfPax = numberOfPax, subTotal = subTotal, discountAmount = discountAmount, total = total,
        itemsCount = itemsCount, table = table, waiter = waiter, customer = customer, terminal = terminal,
        offlineNumber = offlineNumber, needsReview = needsReview,
    )

    // ---------------------------------------------------------------- writes

    /**
     * POS « Envoyer » / « Encaisser Ticket » / « Imprimer Ticket » offline: a new order, or a round on the table's open
     * check. [Printed.print] says what the counter's printer did (kitchen tickets, and the bill when asked).
     */
    suspend fun sendOrder(request: SendOrderRequest): Result<Printed<OrderActionDto>> = offline {
        if (request.discountType != null) refusal("Hors ligne : la remise n'est pas possible tant que la caisse ne répond pas. Retirez-la pour envoyer.")
        if (request.removedChargeIds.isNotEmpty()) refusal("Hors ligne : retirer un frais n'est pas possible tant que la caisse ne répond pas. Remettez-le pour envoyer.")
        val catalog = catalog()
        val tableId = request.tableId.takeIf { request.orderType == "dine_in" }
        val existing = tableId?.let { id -> openOrders(catalog).firstOrNull { it.table?.id == id } }
        val (orderId, kitchen) = round(catalog, existing?.id, tableId, request.orderType, request.numberOfPax ?: 1, request.items, request.note)

        var bill: PrintOutcome? = null
        if (request.action == "bill" || request.action == "bill_print") bill = bill(catalog, orderId, request.action == "bill_print")
        Printed(action(catalog, orderId), joined(kitchen, bill))
    }

    /** « Nouveau ticket » on an open check offline. */
    suspend fun addItems(orderId: Long, request: AddItemsRequest): Result<Printed<OrderActionDto>> = offline {
        val catalog = catalog()
        val (id, kitchen) = round(catalog, orderId, null, null, 1, request.items, request.note)
        Printed(action(catalog, id), kitchen)
    }

    /** « Addition » offline: recorded, and printed on this PC when asked. */
    suspend fun billOrder(orderId: Long, print: Boolean): Result<Printed<OrderActionDto>> = offline {
        val catalog = catalog()
        val outcome = bill(catalog, orderId, print)
        Printed(action(catalog, orderId), outcome)
    }

    /**
     * « Imprimer » offline: the bill again once it is made; before that, the kitchen tickets of the rounds taken
     * offline (a ticket that did not come out).
     */
    suspend fun printOrder(orderId: Long): Result<String> = offline {
        val catalog = catalog()
        val order = detail(catalog, orderId) ?: refusal(NOT_FOUND)
        val outcome = when (order.status) {
            // Settled here: the receipt again, with what was paid and the change.
            "paid" -> localFor(orderId)?.let { payments(it) }?.takeIf { it.isNotEmpty() }?.let { printReceipt(catalog, order, it) } ?: printBill(catalog, order)
            "billed" -> printBill(catalog, order)
            else -> {
                val local = localFor(orderId) ?: refusal("Rien à imprimer hors ligne : l'addition n'est pas encore faite.")
                val all = lines(local)
                if (all.isEmpty()) refusal("Rien à imprimer hors ligne : l'addition n'est pas encore faite.")
                printKitchen(catalog, local, all)
            }
        }
        if (!outcome.ok) refusal(outcome.message)
        outcome.message
    }

    /**
     * One round taken offline: on [orderId] (a check from the copy, or one taken offline), or a new order. Saved here
     * first, then queued, then its kitchen tickets print. Returns the id the screens open (negative for a new order)
     * and what the printer did.
     */
    private suspend fun round(
        catalog: OfflineCatalog,
        orderId: Long?,
        tableId: Long?,
        orderType: String?,
        pax: Int,
        items: List<SendOrderItem>,
        note: String?,
    ): Pair<Long, PrintOutcome> {
        val actor = catalog.offlineAccountId ?: refusal(NOT_CONFIGURED)
        if (items.isEmpty()) refusal("La commande est vide.")
        val at = now()
        val commandId = UUID.randomUUID().toString()

        val target: LocalOrderEntity
        val type: String
        val isNew: Boolean

        when {
            orderId == null -> {
                val number = sync.nextOfflineNumber() ?: refusal(NOT_REGISTERED)
                val table = catalog.table(tableId)
                target = LocalOrderEntity(
                    ref = UUID.randomUUID().toString(), baseOrderId = null, offlineNumber = number,
                    tableId = table?.id, tableCode = table?.code, pax = maxOf(1, pax),
                    orderType = if (table != null) "dine_in" else orderType?.takeIf { it != "dine_in" } ?: "pickup",
                    status = "kot", linesJson = "[]", createdAt = at,
                )
                type = "order.create"
                isNew = true
            }
            orderId < 0 -> {
                target = store.localOrder(localIdOf(orderId)) ?: refusal(NOT_FOUND)
                type = "order.round"
                isNew = false
            }
            else -> {
                target = localFor(orderId) ?: baseFor(orderId)
                type = "order.round"
                isNew = false
            }
        }

        val round = (lines(target).maxOfOrNull { it.round } ?: 0) + 1
        val newLines = items.map { resolve(catalog, it, round, commandId, at) }
        val updated = target.copy(status = "kot", linesJson = json.encodeToString(linesSerializer, lines(target) + newLines))

        // Saved before it is queued, so the sync always finds the order it settles.
        val localId = if (isNew) store.insertLocalOrder(updated) else updated.localId.also { store.saveLocalOrder(updated) }
        val saved = updated.copy(localId = localId)

        val data = buildJsonObject {
            put("order_ref", saved.ref)
            if (isNew) {
                put("offline_number", saved.offlineNumber)
                saved.tableId?.let { put("table_id", it) }
                put("order_type", saved.orderType)
                put("pax", saved.pax)
            } else {
                saved.tableId?.let { put("table_id", it) }
            }
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            put("kitchen_printed_at", Instant.ofEpochMilli(at).toString())
            putJsonArray("lines") {
                newLines.forEach { line ->
                    addJsonObject {
                        put("line_id", line.lineId)
                        put("menu_item_id", line.menuItemId)
                        line.variationId?.let { put("variation_id", it) }
                        putJsonArray("modifier_option_ids") { line.modifiers.forEach { add(it.id) } }
                        put("quantity", line.quantity)
                        put("unit_price", line.price)
                        put("amount", line.amount)
                        line.note?.let { put("note", it) }
                    }
                }
            }
        }

        try {
            sync.enqueue(type, data, actor, label(saved), orderRef = saved.ref, id = commandId)
        } catch (e: Exception) {
            if (isNew) store.deleteLocalOrder(localId) else store.saveLocalOrder(target)
            throw e
        }
        AppLog.info("Hors ligne : ${label(saved)}, tour $round (${newLines.sumOf { it.quantity }} article(s)) en attente d'envoi")

        // Offline, kitchen tickets always print here (whatever Automatique / Manuel).
        val kitchen = printKitchen(catalog, saved, newLines)
        return (saved.baseOrderId ?: screenId(localId)) to kitchen
    }

    /** The bill: recorded and queued; printed on this PC when asked. */
    private suspend fun bill(catalog: OfflineCatalog, orderId: Long, print: Boolean): PrintOutcome? {
        val actor = catalog.offlineAccountId ?: refusal(NOT_CONFIGURED)
        val order = detail(catalog, orderId) ?: refusal(NOT_FOUND)
        if (order.stockWaiting != null || order.items.any { it.stockState == "waiting" }) refusal(STOCK_WAITING)

        val local = localFor(orderId) ?: baseFor(orderId)
        store.saveLocalOrder(local.copy(status = "billed"))
        val saved = store.localOrderByRef(local.ref) ?: local

        sync.enqueue("order.bill", buildJsonObject { put("order_ref", saved.ref) }, actor, "Addition · ${label(saved)}", orderRef = saved.ref)

        if (!print) return null
        val billed = detail(catalog, orderId) ?: order.copy(status = "billed")
        return printBill(catalog, billed)
    }

    // ---------------------------------------------------------------- payments (step 3)

    /** The payment window offline: cash and card (and the bill divided), no credit, no tip; the drawer is the caisse's. */
    suspend fun paymentContext(orderId: Long): Result<PaymentContextDto> = offline {
        val catalog = catalog()
        val order = detail(catalog, orderId) ?: refusal(NOT_FOUND)
        if (order.stockWaiting != null || order.items.any { it.stockState == "waiting" }) refusal(STOCK_WAITING)
        PaymentContextDto(
            orderId = orderId,
            orderNumber = order.orderNumber,
            status = order.status,
            total = order.total,
            subTotal = order.subTotal,
            discountAmount = order.discountAmount ?: 0.0,
            tipAmount = order.tipAmount ?: 0.0,
            discountType = order.discountType,
            discountValue = order.discountValue,
            amountPaid = order.amountPaid,
            due = due(order),
            currencyDecimals = catalog.decimals,
            canAddTip = false,
            cashSessionRequired = false,
            cashSessionOpen = true,
            caisse = CaisseDto(catalog.caisseId, catalog.caisseName),
            paymentMethods = listOf("cash", "card"),
            canGiveCredit = false,
            items = order.items.map { SplitItemDto(it.id, it.name, it.quantity, OfflineCatalog.round2(it.amount / maxOf(1, it.quantity))) },
        )
    }

    /**
     * A payment taken offline, behind the payment PIN: cash (what was handed over, the rest is change) or card (the
     * whole due), or the bill divided in one go. Recorded on the order here and queued (`payment.tender` /
     * `payment.split`, authorized by the payment PIN) for the caisse, which puts it in its Caisse's cash session.
     * The receipt prints on this PC once the order is settled.
     */
    suspend fun pay(orderId: Long, request: PaymentRequest): Result<Printed<PaymentResultDto>> = offline {
        val catalog = catalog()
        val actor = catalog.offlineAccountId ?: refusal(NOT_CONFIGURED)
        if (!paymentsOpen(orderId)) throw ApiFailure("Code de paiement requis.", FailureKind.REFUSED, code = PAYMENT_PIN_REQUIRED)

        val order = detail(catalog, orderId) ?: refusal(NOT_FOUND)
        if (order.stockWaiting != null || order.items.any { it.stockState == "waiting" }) refusal(STOCK_WAITING)
        val due = due(order)
        if (due < 0.01) refusal("Cette commande est déjà réglée.")

        val local = localFor(orderId) ?: baseFor(orderId)
        val commandId = UUID.randomUUID().toString()
        val at = now()
        val split = request.split

        // What enters the drawer per method, the change given back, and the command for the caisse.
        val made: List<LocalPayment>
        val data: JsonObject
        if (split != null) {
            if (split.type !in setOf("equal", "custom", "items")) refusal("Partage de l'addition illisible.")
            val parts = split.splits.map { part ->
                if (part.paymentMethod != "cash" && part.paymentMethod != "card") refusal("Hors ligne, chaque part se règle en espèces ou par carte.")
                val amount = if (split.type == "items") {
                    OfflineCatalog.round2(part.items.orEmpty().sumOf { it.price * it.quantity })
                } else {
                    OfflineCatalog.round2(part.amount ?: 0.0)
                }
                part to amount
            }.filter { it.second >= 0.01 }
            val sum = OfflineCatalog.round2(parts.sumOf { it.second })
            if (sum < 0.01) refusal("Indiquez le montant des parts.")
            if (sum > due + TOLERANCE) refusal("Les parts dépassent le reste à payer (${catalog.money(due, true)}).")
            made = parts.map { (part, amount) -> LocalPayment(commandId, part.paymentMethod, amount, 0.0, split.type, at) }
            data = buildJsonObject {
                put("order_ref", local.ref)
                put("type", split.type)
                putJsonArray("parts") {
                    parts.forEach { (part, amount) ->
                        addJsonObject {
                            put("method", part.paymentMethod)
                            put("amount", amount)
                            put("change", 0.0)
                            if (split.type == "items") {
                                putJsonArray("items") {
                                    part.items.orEmpty().forEach { item ->
                                        addJsonObject {
                                            put("order_item_id", lineRef(local, item.orderItemId))
                                            put("price", item.price)
                                            put("quantity", item.quantity)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                put("authorized_by", "payment_pin")
            }
        } else {
            val method = request.method
            if (method != "cash" && method != "card") refusal("Hors ligne, le paiement se fait en espèces ou par carte (pas de paiement à crédit).")
            val (kept, change) = if (method == "card") {
                OfflineCatalog.round2(due) to 0.0
            } else {
                val tendered = OfflineCatalog.round2(request.amount ?: due)
                if (tendered < 0.01) refusal("Indiquez un montant supérieur à zéro.")
                val keep = OfflineCatalog.round2(minOf(tendered, due))
                keep to OfflineCatalog.round2(tendered - keep)
            }
            made = listOf(LocalPayment(commandId, method, kept, change, null, at))
            data = buildJsonObject {
                put("order_ref", local.ref)
                put("method", method)
                put("amount", OfflineCatalog.round2(kept + change))
                put("change", change)
                put("authorized_by", "payment_pin")
            }
        }

        val kept = OfflineCatalog.round2(made.sumOf { it.amount })
        val change = OfflineCatalog.round2(made.sumOf { it.change })
        val remaining = OfflineCatalog.round2(due - kept)
        val status = if (remaining < TOLERANCE) "paid" else local.status
        val saved = local.copy(status = status, paymentsJson = json.encodeToString(paymentsSerializer, payments(local) + made))
        store.saveLocalOrder(saved)

        try {
            sync.enqueue(
                if (split != null) "payment.split" else "payment.tender",
                data, actor, "Paiement ${catalog.money(kept, true)} · ${label(saved)}", orderRef = saved.ref, id = commandId,
            )
        } catch (e: Exception) {
            store.saveLocalOrder(local)
            throw e
        }
        AppLog.info("Hors ligne : paiement ${catalog.money(kept, true)} (${made.joinToString { it.method }}) sur ${label(saved)} en attente d'envoi")

        var print: PrintOutcome? = null
        if (status == "paid") {
            val settled = detail(catalog, orderId) ?: order
            print = printReceipt(catalog, settled, payments(saved))
        }

        Printed(
            PaymentResultDto(
                orderId = orderId,
                status = status,
                total = order.total,
                amountPaid = OfflineCatalog.round2(order.amountPaid + kept),
                due = maxOf(0.0, remaining),
                change = change,
            ),
            print,
        )
    }

    private fun due(order: OrderDetailDto): Double = maxOf(0.0, OfflineCatalog.round2(order.total - order.amountPaid))

    /** How the caisse finds a line of a split by article: its offline line id, or its own id. */
    private fun lineRef(local: LocalOrderEntity, itemId: Long): String {
        if (itemId >= 0) return itemId.toString()
        val n = -itemId - 1
        val owner = n / 10_000
        val index = (n % 10_000).toInt()
        val lines = if (owner == local.localId) lines(local) else emptyList()
        return lines.getOrNull(index)?.lineId ?: itemId.toString()
    }

    /** The settled order's receipt, with what was paid and the change given. */
    private suspend fun printReceipt(catalog: OfflineCatalog, order: OrderDetailDto, paid: List<LocalPayment>): PrintOutcome {
        val base = receipt(catalog, order)
        val receipt = base.copy(
            footer = base.footer.copy(
                payments = paid.map { p ->
                    ReceiptPayment(
                        amount = catalog.money(p.amount),
                        method = if (p.method == "card") catalog.label("card", "Carte") else catalog.label("cash", "Espèces"),
                        dateTime = catalog.billDate(p.at),
                    )
                },
                change = paid.sumOf { it.change }.takeIf { it > 0 }?.let { catalog.money(it) },
            ),
        )
        return printer.printReceipt(receipt).fold(
            onSuccess = { PrintOutcome(true, "Ticket imprimé sur ce poste.") },
            onFailure = { PrintOutcome(false, "Ticket non imprimé (${it.message}) : le paiement est enregistré.") },
        )
    }

    /** The local record for an order the screens opened by its id. */
    private suspend fun localFor(orderId: Long): LocalOrderEntity? =
        if (orderId < 0) store.localOrder(localIdOf(orderId))
        else store.localOrders().firstOrNull { (it.baseOrderId ?: it.syncedOrderId) == orderId }

    /** A check from the copy that gets its first round or bill offline: a local record on top of it. */
    private suspend fun baseFor(orderId: Long): LocalOrderEntity {
        val check = copyChecks().firstOrNull { it.id == orderId } ?: refusal(NOT_FOUND)
        store.localOrderByRef(orderId.toString())?.let { return it }
        val local = LocalOrderEntity(
            ref = orderId.toString(), baseOrderId = orderId, offlineNumber = check.offlineNumber,
            tableId = check.table?.id, tableCode = check.table?.code, pax = check.numberOfPax ?: 1,
            orderType = check.orderType, status = check.status, linesJson = "[]", createdAt = now(),
        )
        val id = store.insertLocalOrder(local)
        return local.copy(localId = id)
    }

    private fun resolve(catalog: OfflineCatalog, item: SendOrderItem, round: Int, commandId: String, at: Long): LocalLine {
        val menuItem = catalog.items.firstOrNull { it.id == item.menuItemId }
            ?: refusal("Un article n'est plus dans les données hors ligne : rafraîchissez-les quand la caisse répond.")
        val variation = item.menuItemVariationId?.let { id -> menuItem.variations.firstOrNull { it.id == id } }
        val options = menuItem.modifierGroups.flatMap { it.options }.filter { it.id in item.modifierOptionIds }
        val price = OfflineCatalog.round2(variation?.price ?: menuItem.price)
        val quantity = maxOf(1, item.quantity)
        val amount = OfflineCatalog.round2(quantity * (price + options.sumOf { it.price }))

        return LocalLine(
            lineId = UUID.randomUUID().toString(), round = round, commandId = commandId,
            menuItemId = menuItem.id, variationId = variation?.id, name = menuItem.name, variation = variation?.name,
            quantity = quantity, price = price, amount = amount, modifiers = options, note = item.note?.takeIf { it.isNotBlank() },
            stationId = catalog.stationOf[menuItem.id], at = at,
        )
    }

    private suspend fun action(catalog: OfflineCatalog, orderId: Long): OrderActionDto {
        val order = detail(catalog, orderId)
        return OrderActionDto(
            id = orderId,
            orderNumber = order?.orderNumber?.ifBlank { null } ?: order?.offlineNumber.orEmpty(),
            status = order?.status ?: "kot",
            total = order?.total ?: 0.0,
            subTotal = order?.subTotal ?: 0.0,
            amountPaid = order?.amountPaid ?: 0.0,
            tableId = order?.table?.id,
        )
    }

    private fun label(order: LocalOrderEntity): String {
        val number = order.offlineNumber?.let { "Commande $it" } ?: order.baseOrderId?.let { "Commande n° $it" } ?: "Commande"
        return number + (order.tableCode?.let { " · ${tableLabel(it)}" } ?: "")
    }

    /** « T12 » for a digits-only table code, a named table keeps its name (as the kitchen reads it). */
    private fun tableLabel(code: String): String = if (code.all { it.isDigit() }) "T$code" else code

    /** The kitchen and the bill printed with one action: one outcome, both said. */
    private fun joined(a: PrintOutcome?, b: PrintOutcome?): PrintOutcome? = when {
        a == null -> b
        b == null -> a
        else -> PrintOutcome(a.ok && b.ok, "${a.message} ${b.message}")
    }

    // ---------------------------------------------------------------- printing

    /** One kitchen ticket per station for these lines, on this PC's printer (an item with no kitchen gets none). */
    private suspend fun printKitchen(catalog: OfflineCatalog, order: LocalOrderEntity, lines: List<LocalLine>): PrintOutcome {
        val byStation = lines.filter { it.stationId != null }.groupBy { it.stationId!! }
        if (byStation.isEmpty()) return PrintOutcome(true, "Aucun ticket cuisine pour ces articles.")

        val number = order.offlineNumber ?: order.syncedNumber ?: order.baseOrderId?.toString().orEmpty()
        val left = order.tableCode?.let { catalog.label("kot_table", "Table :table", "table" to it) }
            ?: if (order.orderType == "pickup") catalog.label("kot_takeaway", "À emporter") else "#$number"
        val right = catalog.label("kot_number", "Ticket :number", "number" to number)

        var problem: String? = null
        byStation.forEach { (stationId, stationLines) ->
            val ticket = KitchenTicketPrint(
                station = catalog.stations[stationId] ?: "Cuisine",
                left = left,
                right = right,
                items = stationLines.map { line ->
                    KitchenTicketLine(
                        name = line.name,
                        quantity = line.quantity,
                        details = listOfNotNull(line.variation) + line.modifiers.map { it.name } + listOfNotNull(line.note?.takeIf { it.isNotBlank() }),
                    )
                },
                totalQuantity = catalog.label("kot_total_qty", "Quantité totale : :count", "count" to stationLines.sumOf { it.quantity }.toString()),
                note = null,
                dateTime = catalog.kitchenDate(stationLines.first().at),
            )
            printer.printKitchen(ticket).onFailure { problem = it.message }
        }

        return problem?.let { PrintOutcome(false, "Ticket cuisine non imprimé ($it) : la commande est enregistrée, réimprimez-le depuis la commande.") }
            ?: PrintOutcome(true, if (byStation.size == 1) "Ticket cuisine imprimé sur ce poste." else "${byStation.size} tickets cuisine imprimés sur ce poste.")
    }

    /** The bill, built here the caisse's way (SunmiReceiptBuilder): currency on the total only. */
    private suspend fun printBill(catalog: OfflineCatalog, order: OrderDetailDto): PrintOutcome =
        printer.printReceipt(receipt(catalog, order)).fold(
            onSuccess = { PrintOutcome(true, "Addition imprimée sur ce poste.") },
            onFailure = { PrintOutcome(false, "Addition non imprimée (${it.message}) : réimprimez-la depuis la commande.") },
        )

    private fun receipt(catalog: OfflineCatalog, order: OrderDetailDto): ReceiptPayload {
        val totals = catalog.totals(order.subTotal, order.discountType, order.discountValue, order.tipAmount ?: 0.0, order.orderType)
        val at = order.dateTime?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: runCatching { order.dateTime?.let { Instant.parse(it).toEpochMilli() } }.getOrNull() ?: now()

        val details = buildList {
            if (catalog.showTableNumber) order.table?.code?.takeIf { it.isNotBlank() }?.let { add(ReceiptRow(catalog.label("table_number", "Table"), it)) }
            if (catalog.showTotalGuest) order.numberOfPax?.takeIf { it > 0 }?.let { add(ReceiptRow(catalog.label("pax", "Couverts"), it.toString())) }
            if (catalog.showWaiter) order.waiter?.name?.takeIf { it.isNotBlank() }?.let { add(ReceiptRow(catalog.label("waiter", "Serveur"), it)) }
            if (catalog.showCustomerName) order.customer?.takeIf { it.isNotBlank() }?.let { add(ReceiptRow(catalog.label("customer", "Client"), it)) }
        }

        val summary = buildList {
            add(ReceiptRow(catalog.label("sub_total", "Sous-total"), catalog.money(totals.subTotal)))
            if (totals.discount > 0) add(ReceiptRow(catalog.label("discount", "Remise"), "-" + catalog.money(totals.discount)))
            totals.charges.forEach { (label, amount) -> add(ReceiptRow(label, catalog.money(amount))) }
            if (totals.tip > 0) add(ReceiptRow(catalog.label("tip", "Pourboire"), catalog.money(totals.tip)))
            totals.taxes.forEach { (label, amount) -> add(ReceiptRow(label, catalog.money(amount))) }
        }

        return ReceiptPayload(
            restaurant = ReceiptRestaurant(catalog.restaurantName, catalog.restaurantLines),
            order = ReceiptOrder(number = order.offlineNumber?.takeIf { order.id < 0 } ?: order.orderNumber, dateTime = catalog.billDate(at)),
            details = details,
            items = order.items.map { item ->
                ReceiptItem(
                    qty = item.quantity.toString(),
                    name = item.name,
                    price = catalog.money(item.price),
                    amount = catalog.money(item.amount),
                    variation = item.variation,
                    modifiers = item.modifiers.map { m -> if (m.price > 0) "${m.name} (+${catalog.money(m.price)})" else m.name },
                )
            },
            summary = summary,
            total = ReceiptRow(catalog.label("total", "Total"), catalog.money(totals.total, withSymbol = true)),
            footer = ReceiptFooter(message = catalog.label("thank_you", "Merci de votre visite")),
        )
    }
}
