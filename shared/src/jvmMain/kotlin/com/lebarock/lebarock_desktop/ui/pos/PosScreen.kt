package com.lebarock.lebarock_desktop.ui.pos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Sell
import com.lebarock.lebarock_desktop.pos.Discount
import com.lebarock.lebarock_desktop.ui.common.FormField
import com.lebarock.lebarock_desktop.ui.payment.PaymentWindow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lebarock.lebarock_desktop.data.Money
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.MenuItemDto
import com.lebarock.lebarock_desktop.data.remote.OrderDetailDto
import com.lebarock.lebarock_desktop.data.remote.PosContextDto
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.pos.CartLine
import com.lebarock.lebarock_desktop.pos.OrderType
import com.lebarock.lebarock_desktop.pos.tableLabel
import com.lebarock.lebarock_desktop.ui.check.CheckAction
import com.lebarock.lebarock_desktop.ui.check.CheckActionsButton
import com.lebarock.lebarock_desktop.ui.check.CheckDialogs
import com.lebarock.lebarock_desktop.ui.common.AppButton
import com.lebarock.lebarock_desktop.ui.common.AppDialog
import com.lebarock.lebarock_desktop.ui.common.AppTopBar
import com.lebarock.lebarock_desktop.ui.common.Banner
import com.lebarock.lebarock_desktop.ui.common.ButtonKind
import com.lebarock.lebarock_desktop.ui.common.Choice
import com.lebarock.lebarock_desktop.ui.common.ChoiceRow
import com.lebarock.lebarock_desktop.ui.common.ErrorBox
import com.lebarock.lebarock_desktop.ui.common.LoadingBox
import com.lebarock.lebarock_desktop.ui.common.ScrollArea
import com.lebarock.lebarock_desktop.ui.common.SelectField
import com.lebarock.lebarock_desktop.ui.common.TextInput
import com.lebarock.lebarock_desktop.ui.common.Tone
import com.lebarock.lebarock_desktop.ui.common.UiState
import com.lebarock.lebarock_desktop.ui.theme.Danger
import com.lebarock.lebarock_desktop.ui.theme.Dimens
import com.lebarock.lebarock_desktop.ui.theme.Info
import com.lebarock.lebarock_desktop.ui.theme.InfoContainer
import com.lebarock.lebarock_desktop.ui.theme.Success
import com.lebarock.lebarock_desktop.ui.theme.SuccessContainer
import com.lebarock.lebarock_desktop.ui.theme.Warning
import com.lebarock.lebarock_desktop.ui.theme.WarningContainer

/** The counter's POS: the menu on the left, the web POS's cart on the right, always visible. */
@Composable
fun PosScreen(model: PosModel, caisseName: String?, images: ItemImages) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    val existing = model.existingOrder

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (existing != null) "Nouveau ticket — Commande #${existing.orderNumber}" else "POS",
                subtitle = listOfNotNull(caisseName?.let { "Caisse : $it" }, model.menus.firstOrNull { it.id == model.menuId }?.name).joinToString(" · ").ifEmpty { null },
                onBack = model::requestLeave,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val cartWidth = if (maxWidth < 1200.dp) 420.dp else 460.dp
            // A short screen (1366 × 768 once Windows has its title bar and taskbar, or 125 %): the
            // cart's header folds into one line so the lines keep their room.
            val compact = maxHeight < 660.dp
            when (val ctx = model.context) {
                UiState.Loading -> LoadingBox(label = "Chargement du POS…")
                is UiState.Error -> ErrorBox(ctx.message, ctx.hint, onRetry = model::load)
                is UiState.Success -> Row(Modifier.fillMaxSize().padding(Dimens.m), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    MenuColumn(model, images, Modifier.weight(1f).fillMaxHeight())
                    CartPanel(model, ctx.data, compact, Modifier.width(cartWidth).fillMaxHeight())
                }
            }
        }
    }

    PosDialogs(model)
}

// ── The menu ──────────────────────────────────────────────────────────────

@Composable
private fun MenuColumn(model: PosModel, images: ItemImages, modifier: Modifier) {
    val currency = model.currency
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        if (!model.menuLocked && model.menus.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                ChoiceRow(model.menus.map { Choice(it.id, it.name) }, model.menuId ?: -1L, { id -> model.menus.firstOrNull { it.id == id }?.let(model::switchMenu) })
            }
        }
        TextInput(
            model.search, model::onSearch,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Rechercher un article",
            leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailing = if (model.search.isNotEmpty()) {
                { IconBox(Icons.Outlined.Close, "Effacer la recherche", onClick = { model.onSearch("") }) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model.addSingleMatch() }, onDone = { model.addSingleMatch() }),
        )
        val sectionName = model.openSectionName
        when {
            model.search.isNotBlank() -> Text("Résultats pour « ${model.search.trim()} »", style = MaterialTheme.typography.titleMedium, modifier = Modifier.height(Dimens.control).padding(top = Dimens.sm))
            sectionName != null && model.sections.size > 1 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                AppButton("Sections", model::closeSection, kind = ButtonKind.SECONDARY, icon = Icons.AutoMirrored.Outlined.ArrowBack)
                Text(sectionName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val state = model.items) {
                UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(state.message, state.hint, onRetry = model::load)
                is UiState.Success -> if (model.showSections) {
                    SectionTiles(model)
                } else {
                    ItemGrid(model.visibleItems, currency, images, empty = if (model.search.isNotBlank()) "Aucun article ne correspond." else "Aucun élément", onItem = model::onItem)
                }
            }
        }
    }
}

@Composable
private fun SectionTiles(model: PosModel) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
            contentPadding = PaddingValues(end = Dimens.m, bottom = Dimens.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(model.sections, key = { it.id }) { section ->
                Card(
                    onClick = { model.openSection(section.id) },
                    shape = Dimens.cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().height(104.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Column(Modifier.fillMaxSize().padding(Dimens.m), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(section.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (section.itemCount > 1) "${section.itemCount} articles" else "${section.itemCount} article", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun ItemGrid(items: List<MenuItemDto>, currency: CurrencyInfo, images: ItemImages, empty: String, onItem: (MenuItemDto) -> Unit) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            // Edge to edge, cards as close to one size as the width allows (client, 2026-11-08): see NearSizeCells.
            columns = NearSizeCells(ITEM_CARD_WIDTH),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
            contentPadding = PaddingValues(end = Dimens.m, bottom = Dimens.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { item -> ItemCard(item, currency, images) { onItem(item) } }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** The item cards' target width. */
private val ITEM_CARD_WIDTH = 158.dp

/**
 * The item grid's columns (client, 2026-11-08): the cards fill the row from the search box's left edge to the scrollbar,
 * and the number of columns is the one whose card width is closest to [target] — 5 at 1366 × 768 (~167 dp), 4 at 125 %
 * (~143 dp), 8 at 1920 (~164 dp). A grid that only takes the columns that fit and stretches them made 3 cards of
 * ~195 dp at 125 %; a fixed size left gaps beside the row.
 */
private class NearSizeCells(private val target: androidx.compose.ui.unit.Dp) : GridCells {
    override fun androidx.compose.ui.unit.Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val wanted = target.roundToPx()
        var columns = 1
        var bestGap = Int.MAX_VALUE
        for (n in 1..24) {
            val size = (availableSize - spacing * (n - 1)) / n
            if (size <= 0) break
            val gap = kotlin.math.abs(size - wanted)
            if (gap < bestGap) {
                bestGap = gap
                columns = n
            }
        }
        val room = availableSize - spacing * (columns - 1)
        return List(columns) { room / columns + if (it < room % columns) 1 else 0 }
    }

    override fun equals(other: Any?) = other is NearSizeCells && other.target == target
    override fun hashCode() = target.hashCode()
}

/**
 * The web POS's item card (`pos/menu`): the photo in a square (the food icon when the item has none), the item
 * type's badge at the top left, « ÉPUISÉ » over a darkened photo when out of stock; under it the name on two lines
 * and the price in a green pill, or « Voir les variations ».
 */
@Composable
private fun ItemCard(item: MenuItemDto, currency: CurrencyInfo, images: ItemImages, onClick: () -> Unit) {
    val available = item.inStock
    Card(
        onClick = onClick,
        enabled = available,
        shape = Dimens.cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().pointerHoverIcon(if (available) PointerIcon.Hand else PointerIcon.Default),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ItemPhoto(item.imageUrl, images, Modifier.fillMaxSize())
            ItemImages.typeIcon(item.type)?.let { icon ->
                Box(
                    Modifier.padding(Dimens.s).size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.95f)).padding(5.dp),
                ) {
                    Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
            if (!available) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Pill("ÉPUISÉ", Color.White, Danger)
                }
            }
        }
        // The web's px-2.5: 10 dp a side, so the price fits whole in the narrowest card (~143 dp at 125 %).
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = Dimens.s)) {
            Text(
                item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (available) {
                Spacer(Modifier.height(Dimens.xs))
                if (item.variations.isEmpty()) {
                    // As the web card: the price with the currency (« 55.000 DT »).
                    PricePill(Money.format(item.price, currency))
                } else {
                    Text("Voir les variations", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

// ── The cart (web pos/kot_items, pos/order_items) ─────────────────────────

@Composable
private fun CartPanel(model: PosModel, ctx: PosContextDto, compact: Boolean, modifier: Modifier) {
    Surface(modifier, shape = Dimens.cardShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxSize()) {
            if (compact) CompactHeader(model, ctx) else CartHeader(model, ctx)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) { CartLines(model) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CartFooter(model, compact)
        }
    }
    if (model.detailsOpen) {
        AppDialog(
            model.existingOrder?.let { "Commande #${it.orderNumber}" } ?: "Commande #${ctx.nextOrderNumber ?: "—"}",
            // 540: the number (« #D1-001 » offline), the table and the covers' − / + on one line.
            onDismiss = { model.detailsOpen = false }, width = 540.dp,
            actions = { AppButton("Fermer", { model.detailsOpen = false }) },
        ) {
            CartHeaderContent(model, ctx)
        }
    }
}

/** The header in one line: order, type, table, covers, note, waiter — a click opens them. */
@Composable
private fun CompactHeader(model: PosModel, ctx: PosContextDto) {
    val existing = model.existingOrder
    val waiter = ctx.waiters.firstOrNull { it.id == model.waiterId }?.name
    val summary = if (existing != null) {
        listOfNotNull(
            "#${existing.orderNumber}",
            existing.table?.code?.let(::tableLabel),
            existing.numberOfPax?.let { "$it couv." },
            "Note".takeIf { model.roundNote.isNotBlank() },
            existing.waiter?.name,
        )
    } else {
        val dineIn = model.orderType == OrderType.DINE_IN
        listOfNotNull(
            "#${ctx.nextOrderNumber ?: "—"}",
            model.orderType.label,
            if (dineIn) model.table?.let { tableLabel(it.code) } ?: "sans table" else null,
            if (dineIn && model.table != null) "${model.covers} couv." else null,
            "Note".takeIf { model.roundNote.isNotBlank() },
            waiter,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(Dimens.s).height(Dimens.control).clip(Dimens.shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape)
            .clickable { model.detailsOpen = true }.pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.TableRestaurant, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.s))
        Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CartHeader(model: PosModel, ctx: PosContextDto) {
    Column(Modifier.fillMaxWidth().padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        CartHeaderContent(model, ctx)
    }
}

@Composable
private fun CartHeaderContent(model: PosModel, ctx: PosContextDto) {
    val existing = model.existing
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        if (existing == null && ctx.orderTypes.dineIn && ctx.orderTypes.pickup) {
            // The web's two halves: each box takes half the cart's width.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                ChoiceRow(
                    listOf(Choice(OrderType.DINE_IN, OrderType.DINE_IN.label), Choice(OrderType.PICKUP, OrderType.PICKUP.label)),
                    model.orderType, model::setType, itemWidth = (maxWidth - Dimens.s) / 2,
                )
            }
        }
        when (existing) {
            null -> NewOrderHeader(model, ctx)
            UiState.Loading -> Text("Chargement de la commande…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is UiState.Error -> Banner(Tone.DANGER, existing.message, detail = existing.hint)
            is UiState.Success -> ExistingOrderHeader(model, existing.data)
        }
    }
}

@Composable
private fun NewOrderHeader(model: PosModel, ctx: PosContextDto) {
    val dineIn = model.orderType == OrderType.DINE_IN
    val table = model.table
    // One row: the order number, then the table (the web's call to action while there is none —
    // a counter sale may go without), then its covers once a table is chosen.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Pill("Commande #${ctx.nextOrderNumber ?: "—"}", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainer)
        when {
            !dineIn -> Spacer(Modifier.weight(1f))
            table == null -> AppButton("Choisir la table", model::openTables, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.TableRestaurant, modifier = Modifier.weight(1f))
            else -> {
                AppButton(tableLabel(table.code), model::openTables, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.TableRestaurant)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.Groups, contentDescription = "Couverts", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Stepper(model.covers, onMinus = { model.coversInput(model.covers - 1) }, onPlus = { model.coversInput(model.covers + 1) }, label = "couverts")
            }
        }
    }
    if (dineIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            NoteButton(model.roundNote.isNotBlank(), model::openRoundNote)
            WaiterField(model, ctx, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExistingOrderHeader(model: PosModel, order: OrderDetailDto) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Pill("Commande #${order.orderNumber}", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainer)
        order.table?.code?.let { Pill(tableLabel(it), Info, InfoContainer) }
        if (order.needsReview) Pill("À vérifier", Warning, WarningContainer)
        Spacer(Modifier.weight(1f))
        NoteButton(model.roundNote.isNotBlank(), model::openRoundNote)
    }
    // The party on the left, the check's other actions on the right (D4: table, merge, split, waiter,
    // customer…, as on the web's order panel) — on their own line so both buttons keep their width.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(
            listOfNotNull(
                order.numberOfPax?.let { "$it couvert" + if (it > 1) "s" else "" },
                order.waiter?.name?.let { "Serveur : $it" },
                order.customer?.let { "Client : $it" },
            ).joinToString(" · ").ifEmpty { " " },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        CheckActionsButton(model.check.actions(order), enabled = !model.sending && !model.check.busy, onPick = { model.detailsOpen = false; model.check.open(it, order) })
    }
}

@Composable
private fun WaiterField(model: PosModel, ctx: PosContextDto, modifier: Modifier) {
    val me = model.rights
    val waiters = ctx.waiters
    if (me?.assignWaiter == true && waiters.isNotEmpty()) {
        val selected = waiters.firstOrNull { it.id == model.waiterId }?.name
        SelectField(waiters.map { it.name }, selected, { name -> waiters.firstOrNull { it.name == name }?.let { model.setWaiter(it.id) } }, modifier = modifier, placeholder = "Choisir le serveur")
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.xs))
            Text(waiters.firstOrNull { it.id == model.waiterId }?.name ?: "Vous", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CartLines(model: PosModel) {
    val existing = model.existingOrder
    val lines = model.cart.lines
    val currency = model.currency
    if (existing == null && lines.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(Dimens.l), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(Dimens.sm))
            Text("Le panier est vide", style = MaterialTheme.typography.titleMedium)
            Text("Ajoutez des articles au panier !", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = Dimens.s)) {
            if (existing != null) {
                item { CartSubHeading("Sur la commande") }
                items(existing.items, key = { "sent-${it.id}" }) { line ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.m, vertical = Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text("${line.quantity} ×", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(line.name + (line.variation?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (line.modifiers.isNotEmpty()) Text(line.modifiers.joinToString { "+ ${it.name}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            line.waitingForStock -> Pill("En attente de stock", Warning, WarningContainer)
                            line.kotId != null || line.stockState == "taken" -> Pill("Envoyé", Success, SuccessContainer)
                        }
                        Spacer(Modifier.width(Dimens.s))
                        Text(Money.plain(line.amount, currency), style = MaterialTheme.typography.bodyMedium)
                        // D4: a waiting line, or a sent drink (« Annuler une commande »), comes off the order.
                        if (CheckAction.canRemove(existing, line, model.rights)) {
                            IconBox(Icons.Outlined.DeleteOutline, "Retirer cet article", tint = Danger, enabled = !model.check.busy, onClick = { model.check.askRemoveLine(existing, line) })
                        }
                    }
                }
                item { CartSubHeading("Nouveau ticket") }
                if (lines.isEmpty()) {
                    item {
                        Text("Ajoutez les articles de ce ticket.", Modifier.padding(horizontal = Dimens.m, vertical = Dimens.s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(lines, key = { it.id }) { line -> CartRow(model, line, currency) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun CartSubHeading(text: String) {
    Text(text.uppercase(), Modifier.padding(start = Dimens.m, end = Dimens.m, top = Dimens.sm, bottom = Dimens.xs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

/** One row as the web's compact cart: name · note · − + · ×qty · bin; options and note under it. */
@Composable
private fun CartRow(model: PosModel, line: CartLine, currency: CurrencyInfo) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.m, vertical = Dimens.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line.item.name + (line.variation?.let { " — ${it.name}" } ?: ""),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconBox(Icons.Outlined.EditNote, if (line.note.isBlank()) "Ajouter une note" else "Note : ${line.note}", highlighted = line.note.isNotBlank(), onClick = { model.openNote(line.id) })
            IconBox(Icons.Outlined.Remove, "Diminuer la quantité", enabled = line.quantity > 1, onClick = { model.minus(line.id) })
            IconBox(Icons.Outlined.Add, "Augmenter la quantité", onClick = { model.plus(line.id) })
            Text("×${line.quantity}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 40.dp))
            IconBox(Icons.Outlined.DeleteOutline, "Retirer", tint = Danger, onClick = { model.remove(line.id) })
        }
        line.modifiers.forEach { option ->
            Row(Modifier.fillMaxWidth().padding(start = Dimens.s)) {
                Text("+ ${option.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(Money.plain(option.price, currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (line.note.isNotBlank()) {
            Text("« ${line.note} »", Modifier.padding(start = Dimens.s), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CartFooter(model: PosModel, compact: Boolean) {
    val currency = model.currency
    val totals = model.totals
    val existing = model.existingOrder
    val rights = model.rights
    Column(Modifier.fillMaxWidth().padding(if (compact) Dimens.s else Dimens.m), verticalArrangement = Arrangement.spacedBy(if (compact) Dimens.s else Dimens.sm)) {
        if (existing != null) {
            TotalRow("Commande #${existing.orderNumber} (avant ce ticket)", Money.format(existing.total, currency), strong = false)
            // D5: the check's « Remise », as the web's order panel: shown, changed or taken off while it is open.
            val checkOpen = existing.status == "kot" || existing.status == "billed"
            val off = existing.discountAmount ?: 0.0
            if (off > 0) {
                DiscountRow(existing.discountType, existing.discountValue, off, currency, onRemove = if (model.canDiscount && checkOpen) model::removeDiscount else null)
            }
            TotalRow("Ce ticket · ${totals.articleCount} art.", Money.format(totals.subTotal, currency), strong = true)
            if (model.canDiscount && checkOpen) {
                AddDiscountButton(if (off > 0) "Modifier la remise" else "Ajouter une remise", model::openDiscount)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().clip(Dimens.shape).clickable { model.totalsOpen = !model.totalsOpen }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Total", style = MaterialTheme.typography.titleMedium)
                Text(" · ${totals.articleCount} art.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (totals.discount > 0) {
                    Text("  −" + Money.plain(totals.discount, currency), style = MaterialTheme.typography.labelMedium, color = Success, fontWeight = FontWeight.SemiBold)
                }
                Icon(if (model.totalsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = "Détail du total", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(Money.format(totals.total, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (model.totalsOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    TotalRow("Article(s)", totals.articleCount.toString())
                    TotalRow("Sous-total", Money.format(totals.subTotal, currency))
                    model.discount?.takeIf { totals.discount > 0 }?.let { d ->
                        DiscountRow(d.type, d.value, totals.discount, currency, onRemove = model::removeDiscount)
                    }
                    totals.charges.forEach { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.charge.name + if (c.charge.isPercent) " (${pct(c.charge.value)}%)" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconBox(Icons.Outlined.Close, "Retirer ce frais", tint = Danger, onClick = { model.removeCharge(c.charge.id) })
                            Spacer(Modifier.weight(1f))
                            Text(Money.format(c.amount, currency), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    totals.taxes.forEach { t -> TotalRow("${t.tax.name} (${pct(t.tax.percent)}%)", Money.format(t.amount, currency)) }
                    if (!model.cart.isEmpty && model.canDiscount) {
                        AddDiscountButton(if (model.discount != null) "Modifier la remise" else "Ajouter une remise", model::openDiscount)
                    }
                }
            }
        }
        AppButton("Envoyer", { model.submit("kot") }, icon = Icons.AutoMirrored.Outlined.Send, large = !compact, loading = model.sending, enabled = !model.cart.isEmpty, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            // « Encaisser Ticket » (D5, web `saveOrder('bill', 'payment')`): billed, then the payment window.
            if (rights?.takePayment == true) {
                val payable = existing?.status.let { it == "kot" || it == "billed" || it == "payment_due" }
                AppButton(
                    "Encaisser Ticket", { model.submit("bill") }, kind = ButtonKind.SECONDARY,
                    enabled = !model.sending && (!model.cart.isEmpty || payable), modifier = Modifier.weight(1f),
                )
            }
            AppButton(
                "Imprimer Ticket", { model.submit("bill_print") }, kind = ButtonKind.SECONDARY,
                enabled = !model.sending && (!model.cart.isEmpty || existing?.status == "kot"), modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun pct(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** « Remise (10 %) » in green with its ×, as the web cart. */
@Composable
private fun DiscountRow(type: String?, value: Double?, amount: Double, currency: CurrencyInfo, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Remise" + if (type == Discount.PERCENT && value != null) " (${pct(value)} %)" else "",
            style = MaterialTheme.typography.bodyMedium, color = Success,
        )
        if (onRemove != null) IconBox(Icons.Outlined.Close, "Retirer la remise", tint = Danger, onClick = onRemove)
        Spacer(Modifier.weight(1f))
        Text("−" + Money.format(amount, currency), style = MaterialTheme.typography.bodyMedium, color = Success, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AddDiscountButton(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        AppButton(label, onClick, kind = ButtonKind.TEXT, icon = Icons.Outlined.Sell)
    }
}

@Composable
private fun TotalRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── Small parts ──────────────────────────────────────────────────────────

/** A square icon button of the style's control size (48 dp). */
@Composable
internal fun IconBox(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        Modifier.size(Dimens.control).clip(Dimens.shape)
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (!enabled) MaterialTheme.colorScheme.outline else if (highlighted) MaterialTheme.colorScheme.primary else tint)
    }
}

/** The card's price, in the web's green pill: bold 14 sp, whole on one line (« 110.000 DT » fits a 143 dp card). */
@Composable
private fun PricePill(text: String) {
    Surface(shape = Dimens.shape, color = SuccessContainer) {
        Text(
            text, Modifier.padding(horizontal = 6.dp, vertical = Dimens.xs),
            style = MaterialTheme.typography.titleSmall, color = Success, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, softWrap = false,
        )
    }
}

@Composable
internal fun Pill(text: String, fg: Color, bg: Color, large: Boolean = false) {
    Surface(shape = Dimens.shape, color = bg) {
        Text(
            text, Modifier.padding(horizontal = Dimens.s, vertical = Dimens.xs),
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            color = fg, fontWeight = if (large) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
private fun NoteButton(hasNote: Boolean, onClick: () -> Unit) {
    AppButton(if (hasNote) "Note ✓" else "Note", onClick, kind = ButtonKind.SECONDARY, icon = Icons.Outlined.EditNote)
}

@Composable
private fun Stepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit, label: String) {
    Row(
        Modifier.height(Dimens.control).border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(Icons.Outlined.Remove, "Moins de $label", enabled = value > 1, onClick = onMinus)
        Text("$value", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
        IconBox(Icons.Outlined.Add, "Plus de $label", onClick = onPlus)
    }
}

// ── Windows ──────────────────────────────────────────────────────────────

@Composable
private fun PosDialogs(model: PosModel) {
    val currency = model.currency
    CheckDialogs(model.check, currency)
    PaymentWindow(model.payment)
    model.discountForm?.let { f -> DiscountDialog(model, f) }
    model.variationPick?.let { item ->
        AppDialog(item.name, onDismiss = model::closeVariations, subtitle = "Choisissez la variation", actions = { AppButton("Annuler", model::closeVariations, kind = ButtonKind.SECONDARY) }) {
            item.variations.forEach { v ->
                Row(
                    Modifier.fillMaxWidth().height(Dimens.primary).clip(Dimens.shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Dimens.shape)
                        .clickable { model.chooseVariation(v) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = Dimens.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(v.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(Money.plain(v.price, currency), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    model.modifierPick?.let { pick -> ModifierDialog(model, pick, currency) }

    model.noteFor?.let { id ->
        val line = model.cart.lines.firstOrNull { it.id == id }
        if (line != null) {
            TextDialog(
                title = "Instructions particulières",
                subtitle = line.item.name + (line.variation?.let { " — ${it.name}" } ?: ""),
                initial = line.note,
                placeholder = "Instructions ? (ex. sans oignons, bien épicé)",
                onSave = model::saveNote,
                onDismiss = model::closeNote,
            )
        }
    }

    if (model.roundNoteOpen) {
        TextDialog(
            title = "Note de commande",
            subtitle = "Imprimée sur le ticket cuisine de ce ticket.",
            initial = model.roundNote,
            placeholder = "Ex. allergie arachide, servir ensemble",
            onSave = model::saveRoundNote,
            onDismiss = model::closeRoundNote,
        )
    }

    model.tablePicker?.let { state -> TablePicker(model, state, currency) }

    model.coversPick?.let { pick ->
        AppDialog(
            "Nombre de couverts", onDismiss = model::closeCovers, width = 400.dp, subtitle = "Table ${tableLabel(pick.table.code)}",
            actions = {
                AppButton("Annuler", model::closeCovers, kind = ButtonKind.SECONDARY)
                AppButton("Confirmer", model::confirmCovers)
            },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape)) {
                    IconBox(Icons.Outlined.Remove, "Moins de couverts", enabled = pick.count > 1, onClick = { model.coversStep(-1) })
                }
                Text("${pick.count}", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
                Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outline, Dimens.shape)) {
                    IconBox(Icons.Outlined.Add, "Plus de couverts", onClick = { model.coversStep(1) })
                }
            }
        }
    }

    model.stockPrompt?.let { prompt ->
        AppDialog(
            "En attente de stock", onDismiss = {}, dismissible = false,
            actions = {
                AppButton("Garder en attente", model::keepWaiting, kind = ButtonKind.SECONDARY, enabled = !model.sending)
                if (prompt.canForce) AppButton("Forcer l'envoi", model::forceSend, kind = ButtonKind.DANGER, loading = model.sending)
            },
        ) {
            Banner(Tone.WARNING, prompt.message)
            Text(
                if (prompt.canForce) "« Forcer l'envoi » envoie la commande en cuisine : le stock du département passe sous zéro."
                else "La commande reste en attente jusqu'au réapprovisionnement. Un gérant peut forcer l'envoi.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    model.printProblem?.let { message ->
        AppDialog("Ticket non imprimé", onDismiss = model::closePrintProblem, actions = { AppButton("Compris", model::closePrintProblem) }) {
            Banner(Tone.WARNING, message)
            Text("La commande est facturée. Réimprimez le ticket depuis Commandes une fois l'imprimante prête.", style = MaterialTheme.typography.bodyMedium)
        }
    }

    model.noCaisse?.let { message ->
        AppDialog("Pas de caisse", onDismiss = model::closeNoCaisse, actions = { AppButton("Fermer", model::closeNoCaisse) }) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (model.leaveConfirm) {
        AppDialog(
            "Abandonner la commande ?", onDismiss = model::stayHere,
            subtitle = "Les articles du panier n'ont pas été envoyés.",
            actions = {
                AppButton("Continuer la commande", model::stayHere, kind = ButtonKind.SECONDARY)
                AppButton("Abandonner", model::abandon, kind = ButtonKind.DANGER)
            },
        ) {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** The web's « Ajouter une remise »: the value, and Montant fixe / Pourcentage. */
@Composable
private fun DiscountDialog(model: PosModel, f: DiscountForm) {
    val check = model.existingOrder
    AppDialog(
        "Ajouter une remise",
        onDismiss = model::closeDiscount,
        dismissible = !f.busy,
        subtitle = check?.let { "Commande #${it.orderNumber} : la remise porte sur toute la commande." } ?: "Sur le sous-total ; taxes et frais sont calculés après la remise.",
        actions = {
            AppButton("Annuler", model::closeDiscount, kind = ButtonKind.SECONDARY, enabled = !f.busy)
            AppButton("Enregistrer", model::saveDiscount, loading = f.busy)
        },
    ) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        ChoiceRow(
            listOf(Choice(Discount.FIXED, "Montant fixe"), Choice(Discount.PERCENT, "Pourcentage")),
            selected = f.type, onSelect = { t -> model.editDiscount { it.copy(type = t) } }, modifier = Modifier.fillMaxWidth(), itemWidth = 640.dp,
        )
        FormField(if (f.type == Discount.PERCENT) "Remise (%)" else "Remise (${model.currency.symbol})", error = f.error, help = "0 retire la remise.") {
            TextInput(
                f.valueText, { v -> model.editDiscount { it.copy(valueText = v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } },
                placeholder = "Valeur de la remise", isError = f.error != null,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Enter) { model.saveDiscount(); true } else false
                },
            )
        }
    }
}

@Composable
private fun ModifierDialog(model: PosModel, pick: ModifierPick, currency: CurrencyInfo) {
    AppDialog(
        pick.item.name + (pick.variation?.let { " — ${it.name}" } ?: ""),
        onDismiss = model::closeModifiers,
        width = 640.dp,
        subtitle = "Choisissez les options",
        actions = {
            AppButton("Annuler", model::closeModifiers, kind = ButtonKind.SECONDARY)
            AppButton("Ajouter au panier", model::confirmModifiers, icon = Icons.Outlined.Add)
        },
    ) {
        ScrollArea(420.dp) {
            pick.item.modifierGroups.forEach { group ->
                val chosen = pick.selected[group.id].orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    val rule = buildList {
                        if (group.isRequired || group.minSelections > 0) add("Obligatoire")
                        if (group.allowMultipleSelection) add(group.maxSelections?.let { "jusqu'à $it" } ?: "plusieurs choix") else add("1 au choix")
                    }.joinToString(" · ")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        group.options.forEach { option ->
                            val on = option.id in chosen
                            Row(
                                Modifier.height(Dimens.control).clip(Dimens.shape)
                                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Dimens.shape)
                                    .clickable { model.toggleOption(group, option) }.pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = Dimens.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, tint = if (on) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(Dimens.xs))
                                Text(option.name, style = MaterialTheme.typography.labelLarge)
                                if (option.price > 0) {
                                    Spacer(Modifier.width(Dimens.s))
                                    Text(Money.plain(option.price, currency), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        pick.error?.let { Banner(Tone.DANGER, it) }
    }
}

@Composable
private fun TextDialog(title: String, subtitle: String, initial: String, placeholder: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AppDialog(
        title, onDismiss = onDismiss, subtitle = subtitle,
        actions = {
            if (initial.isNotBlank()) AppButton("Effacer", { onSave("") }, kind = ButtonKind.TEXT)
            AppButton("Annuler", onDismiss, kind = ButtonKind.SECONDARY)
            AppButton("Enregistrer", { onSave(text) })
        },
    ) {
        TextInput(
            text, { text = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus), placeholder = placeholder,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TablePicker(model: PosModel, state: UiState<List<AreaDto>>, currency: CurrencyInfo) {
    AppDialog(
        "Choisir la table", onDismiss = model::closeTables, width = 880.dp,
        subtitle = "Une table occupée reçoit ce ticket sur sa commande ouverte.",
        actions = {
            if (model.table != null) AppButton("Sans table", model::clearTable, kind = ButtonKind.TEXT)
            AppButton("Fermer", model::closeTables, kind = ButtonKind.SECONDARY)
        },
    ) {
        when (state) {
            UiState.Loading -> Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingBox() }
            is UiState.Error -> Banner(Tone.DANGER, state.message, detail = state.hint)
            is UiState.Success -> ScrollArea(460.dp) {
                if (state.data.all { it.tables.isEmpty() }) Text("Aucune table active.", style = MaterialTheme.typography.bodyLarge)
                state.data.filter { it.tables.isNotEmpty() }.forEach { area ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        Text("${area.name} · ${area.tables.size} table" + if (area.tables.size > 1) "s" else "", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            area.tables.forEach { t -> TableCard(t, selected = model.table?.id == t.id, currency = currency) { model.pickTable(t) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCard(t: TableDto, selected: Boolean, currency: CurrencyInfo, onClick: () -> Unit) {
    val (label, fg, bg) = when (t.status) {
        "running" -> Triple("Occupée", Info, InfoContainer)
        "reserved" -> Triple("Réservée", Danger, Color(0xFFFEE2E2))
        else -> Triple("Libre", Success, SuccessContainer)
    }
    Column(
        Modifier.width(152.dp).height(112.dp).clip(Dimens.cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, Dimens.cardShape)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(Dimens.sm),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = Dimens.shape, color = bg) {
                Text(tableLabel(t.code), Modifier.padding(horizontal = Dimens.s, vertical = Dimens.xs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = fg)
            }
            Spacer(Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
        val open = t.activeOrder
        Text(
            if (open != null) "#${open.orderNumber} · ${Money.format(open.total, currency)}" else "${t.seats} place" + if (t.seats > 1) "s" else "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
