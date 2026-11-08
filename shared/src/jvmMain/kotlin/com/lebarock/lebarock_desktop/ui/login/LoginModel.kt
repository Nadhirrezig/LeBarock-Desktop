package com.lebarock.lebarock_desktop.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.remote.AccountDto
import com.lebarock.lebarock_desktop.ui.common.ScreenModel
import kotlinx.coroutines.launch
import java.text.Normalizer

/** The password window of the account picked. */
data class PinDialog(
    val account: AccountDto,
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * Sign-in (as on the handheld): the restaurant's accounts — shown at once from the copy kept on
 * this PC, then refreshed from the caisse —, pick one, type its password ("Mot de passe / PIN").
 */
class LoginModel(
    private val repository: CaisseRepository,
    val notice: String?,
    private val onSignedIn: () -> Unit,
) : ScreenModel() {

    var accounts by mutableStateOf(repository.cachedAccounts())
        private set

    /** True until the caisse's list arrived (the copy may be shown meanwhile). */
    var loading by mutableStateOf(true)
        private set

    /** Why the list could not be refreshed (shown over the kept copy, or alone when there is none). */
    var listError by mutableStateOf<ApiFailure?>(null)
        private set

    var query by mutableStateOf("")
        private set

    var dialog by mutableStateOf<PinDialog?>(null)
        private set

    val lastUserId: Long? get() = repository.settingsNow.lastUserId

    init {
        refresh()
    }

    fun refresh() {
        loading = true
        scope.launch {
            repository.accounts()
                .onSuccess {
                    accounts = it
                    listError = null
                }
                .onFailure { listError = it as? ApiFailure ?: ApiFailure.from(it) }
            loading = false
        }
    }

    fun onQuery(text: String) {
        query = text
    }

    /** The list as shown: the last account of this PC first, then by name; filtered by the search. */
    val visibleAccounts: List<AccountDto>
        get() {
            val needle = fold(query)
            val last = lastUserId
            return accounts
                .filter { needle.isEmpty() || fold(it.name).contains(needle) || it.roles.any { r -> fold(r).contains(needle) } }
                .sortedWith(compareBy<AccountDto>({ it.id != last }, { fold(it.name) }))
        }

    /** Enter in the search box: open the only match. */
    fun openSingleMatch() {
        visibleAccounts.singleOrNull()?.let(::pick)
    }

    fun pick(account: AccountDto) {
        dialog = PinDialog(account)
    }

    fun dismiss() {
        if (dialog?.submitting != true) dialog = null
    }

    fun onPassword(text: String) {
        dialog = dialog?.copy(password = text.take(64), error = null)
    }

    fun key(digit: Char) = onPassword((dialog?.password ?: "") + digit)

    fun backspace() = onPassword((dialog?.password ?: "").dropLast(1))

    fun clear() = onPassword("")

    fun submit() {
        val d = dialog ?: return
        if (d.submitting) return
        if (d.password.isEmpty()) {
            dialog = d.copy(error = "Saisissez le mot de passe ou le code PIN.")
            return
        }
        dialog = d.copy(submitting = true, error = null)
        scope.launch {
            repository.login(d.account.id, d.password)
                .onSuccess {
                    dialog = null
                    onSignedIn()
                }
                .onFailure { e ->
                    val failure = e as? ApiFailure ?: ApiFailure.from(e)
                    dialog = dialog?.copy(
                        submitting = false,
                        password = "",
                        error = if (isWrongPassword(failure)) "Mot de passe incorrect." else failure.message,
                    )
                }
        }
    }

    companion object {
        /**
         * The caisse answers a wrong password with Laravel's `auth.failed` (422): "Identifiants
         * incorrects." in French. Other 422s (e.g. "Restaurant is inactive") keep their own text.
         */
        fun isWrongPassword(failure: ApiFailure): Boolean {
            if (failure.httpCode != 422) return false
            val m = failure.message.orEmpty().lowercase()
            return m.contains("identifiants") || m.contains("credentials") || m.contains("mot de passe")
        }

        /** Case and accents do not matter in the search ("helene" finds "Hélène"). */
        fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()
    }
}
