package com.lebarock.lebarock_desktop.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.remote.TerminalDto
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import com.lebarock.lebarock_desktop.ui.common.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class HomeModel(
    private val repository: CaisseRepository,
    sessions: SessionHolder,
    private val onOpenSettings: () -> Unit,
    /** Opens a section that is built (POS, Commandes…). */
    private val onOpenSection: (Section) -> Unit = {},
) : ScreenModel() {

    val session = sessions.session

    /** A short message at the bottom of the screen (snackbar). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The "pas de caisse" window, opened from a button that needs one. */
    var noCaisse by mutableStateOf(false)
        private set

    /** The Caisse picker (Admin / Gérant); null when closed. */
    var picker by mutableStateOf<UiState<List<TerminalDto>>?>(null)
        private set
    var switching by mutableStateOf(false)
        private set
    var pickerError by mutableStateOf<String?>(null)
        private set

    init {
        // The account may have changed since sign-in (rights, Caisse): read it again on Accueil.
        scope.launch { repository.refreshSession() }
    }

    fun open(section: Section, hasCaisse: Boolean) {
        when {
            section == Section.SETTINGS -> onOpenSettings()
            section.needsCaisse && !hasCaisse -> noCaisse = true
            !section.available -> _messages.trySend("« ${section.title} » arrive à l'étape ${section.comesIn}.")
            else -> onOpenSection(section)
        }
    }

    fun closeNoCaisse() {
        noCaisse = false
    }

    fun openPicker() {
        noCaisse = false
        pickerError = null
        picker = UiState.Loading
        scope.launch {
            picker = repository.terminals().fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Liste des caisses indisponible.", (it as? ApiFailure)?.hint) },
            )
        }
    }

    fun closePicker() {
        if (!switching) picker = null
    }

    fun choose(terminal: TerminalDto) {
        switching = true
        pickerError = null
        scope.launch {
            repository.switchTerminal(terminal.id)
                .onSuccess {
                    picker = null
                    _messages.trySend("Caisse : « ${terminal.name} ».")
                }
                .onFailure { pickerError = it.message }
            switching = false
        }
    }
}
