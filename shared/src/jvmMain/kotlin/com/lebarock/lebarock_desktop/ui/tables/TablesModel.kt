package com.lebarock.lebarock_desktop.ui.tables

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.OrdersRepository
import com.lebarock.lebarock_desktop.data.remote.AreaDto
import com.lebarock.lebarock_desktop.data.remote.TableDto
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.pos.tableLabel
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** « Nombre de couverts » for a free table (web: 1 by default). */
data class FloorCovers(val table: TableDto, val count: Int = 1)

/**
 * Tables (D4), the web floor (`Table/Tables`): every area's tables with their state, refreshed every
 * 15 s like the web, and at once after an action here or when coming back to the screen (the model
 * is made again). Client's choices (2026-11-08): an occupied table opens the POS on its check, like
 * the web; a free table asks its covers and opens the POS on it — nothing is recorded on the caisse
 * until « Envoyer ». A table opened elsewhere (web, handheld) where nobody ordered can be freed.
 */
class TablesModel(
    private val orders: OrdersRepository,
    private val sessions: SessionHolder,
    initialMessage: String?,
    /** The POS on this check (round mode). */
    private val onOpenCheck: (Long) -> Unit,
    /** The POS for a new order on this table with these covers. */
    private val onNewOrder: (TableDto, Int) -> Unit,
) : ScreenModel() {

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    var areas by mutableStateOf<UiState<List<AreaDto>>>(UiState.Loading)
        private set

    /** null = « Toutes les salles ». */
    var areaFilter by mutableStateOf<Long?>(null)
        private set
    var covers by mutableStateOf<FloorCovers?>(null)
        private set
    var releasing by mutableStateOf<TableDto?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** When the floor was last read (ms), for « À jour ». */
    var updatedAt by mutableStateOf<Long?>(null)
        private set

    val rights get() = sessions.current?.rights
    val currency get() = orders.currency

    init {
        initialMessage?.let { messageChannel.trySend(it) }
        refresh()
        scope.launch {
            while (true) {
                delay(REFRESH_MILLIS)
                refresh()
            }
        }
    }

    fun refresh() {
        val keep = areas is UiState.Success
        scope.launch {
            orders.tables()
                .onSuccess {
                    areas = UiState.Success(it)
                    updatedAt = System.currentTimeMillis()
                }
                .onFailure { e ->
                    if (!keep) areas = UiState.Error(e.message ?: "Tables indisponibles.", (e as? ApiFailure)?.hint)
                }
        }
    }

    fun chooseArea(id: Long?) {
        areaFilter = id
    }

    val shownAreas: List<AreaDto>
        get() {
            val all = (areas as? UiState.Success)?.data.orEmpty().filter { it.tables.isNotEmpty() }
            return areaFilter?.let { id -> all.filter { it.id == id } } ?: all
        }

    private val allTables: List<TableDto> get() = (areas as? UiState.Success)?.data.orEmpty().flatMap { it.tables }

    /** A tap on a tile, as on the web floor. */
    fun tap(t: TableDto) {
        if (rights?.orders != true) {
            messageChannel.trySend("Ce compte ne prend pas de commandes.")
            return
        }
        val host = t.session?.mergedInto
        val open = t.activeOrder
        when {
            // Merged into another table's party: its check is there.
            host != null -> {
                val check = allTables.firstOrNull { it.id == host.id }?.activeOrder
                if (check != null) onOpenCheck(check.id) else messageChannel.trySend("La table ${tableLabel(t.code)} est avec la table ${host.code?.let(::tableLabel) ?: ""}.")
            }
            open != null -> onOpenCheck(open.id)
            // Opened (web, handheld) but nobody ordered yet: an order on it, with the covers already given.
            t.session != null -> onNewOrder(t, t.session.pax.coerceAtLeast(1))
            else -> covers = FloorCovers(t)
        }
    }

    fun coversStep(delta: Int) {
        covers = covers?.let { it.copy(count = (it.count + delta).coerceAtLeast(1)) }
    }

    fun closeCovers() {
        covers = null
    }

    fun confirmCovers() {
        val pick = covers ?: return
        covers = null
        onNewOrder(pick.table, pick.count)
    }

    // ---- « Libérer » ----

    fun askRelease(t: TableDto) {
        releasing = t
    }

    fun closeRelease() {
        if (!busy) releasing = null
    }

    fun confirmRelease() {
        val t = releasing ?: return
        if (busy) return
        busy = true
        scope.launch {
            orders.releaseTable(t.id)
                .onSuccess { messageChannel.trySend(it) }
                .onFailure { messageChannel.trySend(it.message ?: "La table n'a pas pu être libérée.") }
            releasing = null
            busy = false
            refresh()
        }
    }

    companion object {
        /** As the web floor (`wire:poll.15s`). */
        const val REFRESH_MILLIS = 15_000L
    }
}
