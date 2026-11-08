package com.lebarock.lebarock_desktop.ui.pos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.PrintOutcome
import com.lebarock.lebarock_desktop.data.remote.AddItemsRequest
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.MenuDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.ModifierGroupDto
import com.lebarock.lebarock_desktop.data.remote.ModifierOptionDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.PosContextDto
import com.lebarock.lebarock_desktop.data.remote.SendOrderRequest
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.remote.VariationDto
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.pos.Cart
import com.lebarock.lebarock_desktop.pos.CartMath
import com.lebarock.lebarock_desktop.pos.CartTotals
import com.lebarock.lebarock_desktop.pos.MenuSection
import com.lebarock.lebarock_desktop.pos.ModifierRules
import com.lebarock.lebarock_desktop.pos.OrderType
import com.lebarock.lebarock_desktop.pos.searchItems
import com.lebarock.lebarock_desktop.pos.sectionsOf
import com.lebarock.lebarock_desktop.ui.check.CheckActions
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.idle.IdleMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The options window for an item (and its variation): the choice so far, and why it cannot go yet. */
data class ModifierPick(
    val item: MenuItemDto,
    val variation: VariationDto?,
    val selected: Map<Long, Set<Long>> = emptyMap(),
    val error: String? = null,
)

/** The stock did not take the round (C5): it waits on check [orderId], nothing reached the kitchen. */
data class StockPrompt(val orderId: Long, val message: String, val canForce: Boolean, val action: String)

/** The covers window for a free table (web: « Nombre de Couverts », 1 by default). */
data class CoversPick(val table: TableDto, val count: Int = 1)

/**
 * The counter's POS (D3), the web POS's logic: the menu by sections in the Tri order, a search over
 * the whole menu, and the cart of `pos/kot_items` (Sur place / À emporter, table, couverts, note,
 * waiter, lines with their notes, totals as `calculateTotal`). [existingOrderId]: « Nouveau ticket »
 * on an open check (`pos/order_items`): its lines are shown, sent lines locked, the new ones go as a
 * round.
 */
class PosModel(
    private val orders: OrdersRepository,
    private val sessions: SessionHolder,
    private val idle: IdleMonitor,
    private val existingOrderId: Long?,
    /** Leave the POS (back to where it was opened from), with a message for that screen. */
    private val onLeave: (String?) -> Unit,
    /** D4, from the floor: a free table already chosen with its covers (nothing recorded until « Envoyer »). */
    private val startTable: TableDto? = null,
    private val startCovers: Int = 1,
) : ScreenModel() {

    /** Opened from Tables: once the order is sent (or billed) the POS goes back to the floor. */
    private val returnsWhenDone: Boolean get() = existingOrderId != null || startTable != null

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    var context by mutableStateOf<UiState<PosContextDto>>(UiState.Loading)
        private set
    var items by mutableStateOf<UiState<List<MenuItemDto>>>(UiState.Loading)
        private set

    /** Menu tabs: only on a till without its own menu (web `menuLocked`). */
    var menus by mutableStateOf<List<MenuDto>>(emptyList())
        private set
    var menuId by mutableStateOf<Long?>(null)
        private set

    var search by mutableStateOf("")
        private set
    var openSection by mutableStateOf<Long?>(null)
        private set

    var cart by mutableStateOf(Cart())
        private set
    var orderType by mutableStateOf(OrderType.DINE_IN)
        private set
    var table by mutableStateOf<TableDto?>(null)
        private set
    var covers by mutableStateOf(1)
        private set
    var waiterId by mutableStateOf<Long?>(sessions.current?.user?.id)
        private set
    var roundNote by mutableStateOf("")
        private set
    var removedCharges by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** « Nouveau ticket » on an open check: the check as the caisse has it. */
    var existing by mutableStateOf<UiState<OrderDetailDto>?>(null)
        private set

    var sending by mutableStateOf(false)
        private set
    var totalsOpen by mutableStateOf(false)

    /** Short screens: the cart header is a one-line summary that opens its controls in a window. */
    var detailsOpen by mutableStateOf(false)

    // ---- Windows ----
    var variationPick by mutableStateOf<MenuItemDto?>(null)
        private set
    var modifierPick by mutableStateOf<ModifierPick?>(null)
        private set
    var noteFor by mutableStateOf<Long?>(null)
        private set
    var roundNoteOpen by mutableStateOf(false)
        private set
    var tablePicker by mutableStateOf<UiState<List<AreaDto>>?>(null)
        private set
    var coversPick by mutableStateOf<CoversPick?>(null)
        private set
    var stockPrompt by mutableStateOf<StockPrompt?>(null)
        private set
    var printProblem by mutableStateOf<String?>(null)
        private set
    var noCaisse by mutableStateOf<String?>(null)
        private set

    private var hold: IdleMonitor.Hold? = null

    val rights get() = sessions.current?.rights
    val currency get() = orders.currency
    val appendMode: Boolean get() = existing != null
    val existingOrder: OrderDetailDto? get() = (existing as? UiState.Success)?.data

    /** A till with its own menu sells from it only (web `lockedMenuId`). */
    val menuLocked: Boolean get() = sessions.current?.caisse?.menuId != null

    /**
     * D4: what the web order panel does to a table's check (table, merge, split, waiter, customer,
     * charges, « Vérifié », a line taken off, cancel). A merge answers the other table's check: the
     * POS follows it. A cancelled or emptied check is done with.
     */
    val check = CheckActions(
        orders, sessions, scope,
        onChanged = { change ->
            existing = UiState.Success(change.order)
            messageChannel.trySend(change.message)
        },
        onClosed = { message -> finished(message) },
    )

    init {
        load()
        existingOrderId?.let { openExisting(it) }
        startTable?.let {
            table = it
            covers = startCovers.coerceAtLeast(1)
        }
    }

    override fun onDispose() {
        hold?.release()
        hold = null
    }

    fun load() {
        context = UiState.Loading
        scope.launch {
            context = orders.posContext().fold(
                onSuccess = { ctx ->
                    if (!ctx.orderTypes.dineIn && ctx.orderTypes.pickup) orderType = OrderType.PICKUP
                    UiState.Success(ctx)
                },
                onFailure = { UiState.Error(it.message ?: "La caisse ne répond pas.", (it as? ApiFailure)?.hint) },
            )
        }
        val ownMenu = sessions.current?.caisse?.menuId
        if (ownMenu != null) {
            loadItems(ownMenu)
        } else {
            items = UiState.Loading
            scope.launch {
                orders.menus()
                    .onSuccess { list ->
                        menus = list
                        val first = list.firstOrNull()
                        if (first == null) items = UiState.Success(emptyList()) else loadItems(first.id)
                    }
                    .onFailure { items = UiState.Error(it.message ?: "Menu indisponible.", (it as? ApiFailure)?.hint) }
            }
        }
    }

    private fun loadItems(menu: Long) {
        menuId = menu
        items = UiState.Loading
        scope.launch {
            items = orders.menuItems(menu).fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Menu indisponible.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    /** Another menu on a counter without its own; the cart stays. */
    fun switchMenu(menu: MenuDto) {
        if (menuLocked || menu.id == menuId) return
        openSection = null
        search = ""
        loadItems(menu.id)
    }

    // ---- The menu column ----

    private val allItems: List<MenuItemDto> get() = (items as? UiState.Success)?.data.orEmpty()

    val sections: List<MenuSection> get() = sectionsOf(allItems)

    /** A menu with a single section opens straight on its items (web). */
    val showSections: Boolean get() = search.isBlank() && openSection == null && sections.size > 1

    val visibleItems: List<MenuItemDto>
        get() = when {
            search.isNotBlank() -> searchItems(allItems, search)
            openSection != null -> allItems.filter { it.category?.id == openSection }
            else -> allItems
        }

    val openSectionName: String? get() = sections.firstOrNull { it.id == openSection }?.name

    fun onSearch(text: String) {
        search = text
    }

    fun openSection(id: Long) {
        openSection = id
    }

    fun closeSection() {
        openSection = null
    }

    /** Enter in the search box: a single match goes to the cart. */
    fun addSingleMatch() {
        searchItems(allItems, search).singleOrNull()?.let { onItem(it) }
    }

    // ---- Adding to the cart: variation first, then options (web addCartItems) ----

    fun onItem(item: MenuItemDto) {
        if (!item.inStock) return
        when {
            item.variations.isNotEmpty() -> variationPick = item
            item.modifierGroups.isNotEmpty() -> modifierPick = ModifierPick(item, null)
            else -> addToCart(item, null, emptyList())
        }
    }

    fun closeVariations() {
        variationPick = null
    }

    fun chooseVariation(variation: VariationDto) {
        val item = variationPick ?: return
        variationPick = null
        if (item.modifierGroups.isNotEmpty()) modifierPick = ModifierPick(item, variation) else addToCart(item, variation, emptyList())
    }

    fun closeModifiers() {
        modifierPick = null
    }

    fun toggleOption(group: ModifierGroupDto, option: ModifierOptionDto) {
        val pick = modifierPick ?: return
        val next = ModifierRules.toggle(group, option.id, pick.selected[group.id].orEmpty())
        modifierPick = pick.copy(selected = pick.selected + (group.id to next), error = null)
    }

    fun confirmModifiers() {
        val pick = modifierPick ?: return
        ModifierRules.missing(pick.item, pick.selected)?.let {
            modifierPick = pick.copy(error = "« ${it.name} » : sélection obligatoire.")
            return
        }
        val options = pick.item.modifierGroups.flatMap { g -> g.options.filter { it.id in pick.selected[g.id].orEmpty() } }
        modifierPick = null
        addToCart(pick.item, pick.variation, options)
    }

    private fun addToCart(item: MenuItemDto, variation: VariationDto?, options: List<ModifierOptionDto>) {
        updateCart(cart.add(item, variation, options))
    }

    fun plus(id: Long) = updateCart(cart.plus(id))
    fun minus(id: Long) = updateCart(cart.minus(id))
    fun remove(id: Long) = updateCart(cart.remove(id))

    fun openNote(id: Long) {
        noteFor = id
    }

    fun closeNote() {
        noteFor = null
    }

    fun saveNote(text: String) {
        val id = noteFor ?: return
        noteFor = null
        updateCart(cart.note(id, text))
    }

    /** The idle lock never closes the session on a cart with lines (client, 2026-11-08). */
    private fun updateCart(next: Cart) {
        cart = next
        if (next.isEmpty) {
            hold?.release()
            hold = null
        } else if (hold == null) {
            hold = idle.hold("panier en cours")
        }
    }

    // ---- The cart header ----

    fun setType(type: OrderType) {
        if (appendMode || type == orderType) return
        orderType = type
        removedCharges = emptySet()
        if (type != OrderType.DINE_IN) table = null
    }

    fun openTables() {
        tablePicker = UiState.Loading
        scope.launch {
            tablePicker = orders.tables().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Tables indisponibles.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    fun closeTables() {
        tablePicker = null
    }

    /**
     * A free (or reserved) table asks its covers, as on the web. A table with an open check takes
     * the round on that check (the caisse keeps one open check per table): the POS opens it.
     */
    fun pickTable(t: TableDto) {
        val open = t.activeOrder
        if (open != null) {
            tablePicker = null
            openExisting(open.id)
        } else {
            coversPick = CoversPick(t)
        }
    }

    fun coversStep(delta: Int) {
        coversPick = coversPick?.let { it.copy(count = (it.count + delta).coerceAtLeast(1)) }
    }

    fun closeCovers() {
        coversPick = null
    }

    fun confirmCovers() {
        val pick = coversPick ?: return
        coversPick = null
        tablePicker = null
        table = pick.table
        covers = pick.count
    }

    fun clearTable() {
        tablePicker = null
        table = null
        covers = 1
    }

    fun coversInput(value: Int) {
        covers = value.coerceAtLeast(1)
    }

    fun setWaiter(id: Long) {
        waiterId = id
    }

    fun openRoundNote() {
        roundNoteOpen = true
    }

    fun closeRoundNote() {
        roundNoteOpen = false
    }

    fun saveRoundNote(text: String) {
        roundNote = text.trim()
        roundNoteOpen = false
    }

    fun removeCharge(id: Long) {
        if (!appendMode) removedCharges = removedCharges + id
    }

    // ---- Totals (web calculateTotal) ----

    val totals: CartTotals
        get() {
            val ctx = (context as? UiState.Success)?.data
            val charges = if (appendMode || ctx == null) emptyList() else CartMath.chargesFor(ctx.charges, orderType, removedCharges)
            return CartMath.totals(cart.lines, if (appendMode) emptyList() else ctx?.taxes.orEmpty(), charges)
        }

    // ---- « Nouveau ticket » on an open check ----

    private fun openExisting(orderId: Long) {
        existing = UiState.Loading
        scope.launch {
            existing = orders.orderDetail(orderId).fold(
                onSuccess = { detail ->
                    orderType = if (detail.orderType == "pickup") OrderType.PICKUP else OrderType.DINE_IN
                    UiState.Success(detail)
                },
                onFailure = { UiState.Error(it.message ?: "Commande introuvable.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    /** Back to a new order (the web goes back to a fresh POS after each save). */
    private fun reset() {
        updateCart(cart.cleared())
        // As the web after each order: back on « Sur place » (when the restaurant takes it).
        val types = (context as? UiState.Success)?.data?.orderTypes
        orderType = if (types == null || types.dineIn) OrderType.DINE_IN else OrderType.PICKUP
        table = null
        covers = 1
        roundNote = ""
        removedCharges = emptySet()
        waiterId = sessions.current?.user?.id
        existing = null
        totalsOpen = false
        context.let { if (it is UiState.Success) refreshNumber() }
    }

    private fun refreshNumber() {
        scope.launch { orders.posContext().onSuccess { context = UiState.Success(it) } }
    }

    /** Done with this order: a POS opened from Commandes or Tables goes back there; else a fresh POS. */
    private fun finished(message: String) {
        if (returnsWhenDone) {
            hold?.release()
            hold = null
            onLeave(message)
        } else {
            reset()
            messageChannel.trySend(message)
        }
    }

    // ---- Envoyer / Imprimer Ticket ----

    /**
     * « Envoyer » (kot) or « Imprimer Ticket » (bill + print). On an open check, the new lines go as a
     * round first; « Imprimer Ticket » then bills it.
     */
    fun submit(action: String) {
        if (sending) return
        val existingDetail = existingOrder
        if (cart.isEmpty && !(action == "bill_print" && existingDetail != null)) {
            messageChannel.trySend("Ajoutez des articles au panier.")
            return
        }
        sending = true
        scope.launch {
            if (existingDetail != null) submitOnExisting(existingDetail, action) else submitNew(action)
            sending = false
        }
    }

    private suspend fun submitNew(action: String) {
        val r = rights
        val request = SendOrderRequest(
            tableId = table?.id.takeIf { orderType == OrderType.DINE_IN },
            orderType = orderType.api,
            numberOfPax = if (orderType == OrderType.DINE_IN && table != null) covers else null,
            waiterId = if (r?.assignWaiter == true) waiterId else null,
            note = roundNote.ifBlank { null },
            action = action,
            items = cart.requestItems(),
            removedChargeIds = removedCharges.toList(),
        )
        orders.sendOrder(request)
            .onSuccess { sent ->
                val number = sent.value.orderNumber
                when (action) {
                    "bill_print" -> afterBill("Commande #$number facturée.", sent.print)
                    else -> finished("Commande #$number envoyée en cuisine.")
                }
            }
            .onFailure { failed(it, action, orderIdFallback = null) }
    }

    private suspend fun submitOnExisting(detail: OrderDetailDto, action: String) {
        if (!cart.isEmpty) {
            val added = orders.addItems(detail.id, AddItemsRequest(items = cart.requestItems(), note = roundNote.ifBlank { null }))
            val error = added.exceptionOrNull()
            if (error != null) {
                failed(error, action, orderIdFallback = detail.id)
                return
            }
            if (action != "bill_print") {
                finished("Nouveau ticket envoyé : commande #${detail.orderNumber}.")
                return
            }
            updateCart(cart.cleared())
        }
        orders.bill(detail.id, print = true)
            .onSuccess { afterBill("Commande #${detail.orderNumber} facturée.", it.print) }
            .onFailure { failed(it, action, orderIdFallback = detail.id) }
    }

    /** The bill is made: say where the ticket came out, or keep a window with why it did not. */
    private fun afterBill(done: String, print: PrintOutcome?) {
        when {
            print == null || print.ok -> finished(listOfNotNull(done, print?.message).joinToString(" "))
            else -> printProblem = print.message
        }
    }

    private fun failed(e: Throwable, action: String, orderIdFallback: Long?) {
        val failure = e as? ApiFailure
        when {
            failure?.noCaisse == true -> noCaisse = failure.message
            failure?.stockWaiting == true -> {
                // The lines are on the check, waiting: the cart is done with.
                updateCart(cart.cleared())
                stockPrompt = StockPrompt(
                    orderId = failure.orderId ?: orderIdFallback ?: 0L,
                    message = failure.message ?: "Stock insuffisant.",
                    canForce = failure.canForce,
                    action = action,
                )
            }
            else -> messageChannel.trySend(e.message ?: "Envoi impossible.")
        }
    }

    /** « Garder en attente »: the check keeps the waiting round; Commandes shows it. */
    fun keepWaiting() {
        stockPrompt = null
        finished("Commande gardée en attente de stock.")
    }

    /** « Forcer l'envoi »: the waiting round goes anyway, the department goes below zero. */
    fun forceSend() {
        val prompt = stockPrompt ?: return
        if (prompt.orderId <= 0L || sending) return
        sending = true
        scope.launch {
            if (prompt.action == "bill_print") {
                orders.bill(prompt.orderId, print = true, force = true)
                    .onSuccess { stockPrompt = null; afterBill("Envoi forcé : commande facturée.", it.print) }
                    .onFailure { stillRefused(prompt, it) }
            } else {
                orders.sendWaiting(prompt.orderId, force = true)
                    .onSuccess { stockPrompt = null; finished("Envoi forcé : commande #${it.orderNumber} envoyée en cuisine.") }
                    .onFailure { stillRefused(prompt, it) }
            }
            sending = false
        }
    }

    private fun stillRefused(prompt: StockPrompt, e: Throwable) {
        stockPrompt = prompt.copy(message = e.message ?: prompt.message, canForce = (e as? ApiFailure)?.canForce ?: prompt.canForce)
    }

    /** The print problem was read: the bill is made. */
    fun closePrintProblem() {
        printProblem = null
        finished("Commande facturée, ticket non imprimé.")
    }

    fun closeNoCaisse() {
        noCaisse = null
    }

    /** Back with lines not sent: asked first (as on the handheld); nothing to lose: leave. */
    var leaveConfirm by mutableStateOf(false)
        private set

    fun requestLeave() {
        if (cart.isEmpty) onLeave(null) else leaveConfirm = true
    }

    fun stayHere() {
        leaveConfirm = false
    }

    fun abandon() {
        leaveConfirm = false
        hold?.release()
        hold = null
        onLeave(null)
    }
}
