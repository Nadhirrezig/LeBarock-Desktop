package com.lebarock.lebarock_desktop.data

import com.lebarock.lebarock_desktop.AppInfo
import com.lebarock.lebarock_desktop.data.remote.AccountDto
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.AltosApi
import com.lebarock.lebarock_desktop.data.remote.LoginRequest
import com.lebarock.lebarock_desktop.data.remote.PingData
import com.lebarock.lebarock_desktop.data.remote.PrintAgentDto
import com.lebarock.lebarock_desktop.data.remote.SwitchTerminalRequest
import com.lebarock.lebarock_desktop.data.remote.TerminalDto
import com.lebarock.lebarock_desktop.data.settings.AppSettings
import com.lebarock.lebarock_desktop.data.settings.CachedAccount
import com.lebarock.lebarock_desktop.data.settings.CurrencyInfo
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** What "Tester la connexion" found: the restaurant, and how far this PC's clock is from the server's. */
data class ConnectionCheck(
    val address: ServerAddress,
    val ping: PingData,
    /** Server clock − this PC's clock; null when the server did not say its time. */
    val clockOffset: Duration?,
) {
    val restaurantName: String get() = ping.restaurantName?.takeIf { it.isNotBlank() } ?: "la caisse"

    /** More than 2 minutes apart: tickets and reports would show odd times. */
    val clockWarning: Boolean get() = clockOffset != null && clockOffset.abs() > Duration.ofMinutes(2)
}

/**
 * The single gateway the screens talk to, as the handheld's AltosRepository: the caisse's API,
 * the session and this PC's settings. Every failure comes back as an [ApiFailure] with a French
 * message.
 */
class CaisseRepository(
    private val client: ApiClient,
    private val settings: SettingsStore,
    private val sessions: SessionHolder,
    private val now: () -> Instant = Instant::now,
) {

    private fun api(): AltosApi {
        val baseUrl = settings.current.server.baseUrl
        if (baseUrl.isBlank()) throw ApiFailure("Adresse du serveur non réglée.", FailureKind.OTHER, hint = "Ouvrez Réglages → Serveur.")
        return client.apiFor(baseUrl)
    }

    private suspend fun <T> call(https: Boolean = settings.current.server.https, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        val failure = ApiFailure.from(e, https)
        if (failure.sessionExpired && sessions.current != null) {
            AppLog.info("Session refusée par la caisse (401) : retour à la connexion")
            sessions.clear()
        }
        Result.failure(failure)
    }

    // ---- Réglages → Serveur ----

    /** Asks the caisse at [address] who it is (`GET /api/mobile/ping`), with short timeouts. */
    suspend fun testConnection(address: ServerAddress): Result<ConnectionCheck> = call(address.https) {
        val sentAt = now()
        val ping = client.probeFor(address.baseUrl).ping()
        val data = ping.data ?: throw ApiFailure(
            "Ce serveur répond, mais ce n'est pas une caisse AltosPOS.", FailureKind.NOT_A_CAISSE,
            hint = "Vérifiez l'adresse et le port de la caisse.",
        )
        if (data.product == null && data.restaurantName == null && data.serverTime == null) {
            throw ApiFailure(
                "Ce serveur répond, mais ce n'est pas une caisse AltosPOS.", FailureKind.NOT_A_CAISSE,
                hint = "Vérifiez l'adresse et le port de la caisse.",
            )
        }
        val offset = data.serverTime?.let { time ->
            runCatching {
                val server = OffsetDateTime.parse(time).toInstant()
                val midpoint = sentAt.plus(Duration.between(sentAt, now()).dividedBy(2))
                Duration.between(midpoint, server)
            }.getOrNull()
        }
        ConnectionCheck(address, data, offset)
    }.onSuccess { AppLog.info("Test de connexion réussi : ${address.label} → ${it.restaurantName}") }
        .onFailure { AppLog.warn("Test de connexion échoué : ${address.label} → ${it.message}") }

    /**
     * Keeps the address (and what the test learnt: restaurant, currency). Another server means
     * another restaurant: the account list kept for the old one is dropped.
     */
    fun saveServer(address: ServerAddress, check: ConnectionCheck?) {
        settings.update { current ->
            val changed = current.server.baseUrl != address.baseUrl
            current.copy(
                server = address,
                restaurantName = check?.ping?.restaurantName ?: if (changed) "" else current.restaurantName,
                currency = check?.ping?.currency?.let {
                    CurrencyInfo(code = it.code, symbol = it.symbol ?: "DT", position = it.position, decimals = it.decimals)
                } ?: current.currency,
                accounts = if (changed) emptyList() else current.accounts,
                lastUserId = if (changed) null else current.lastUserId,
            )
        }
        AppLog.info("Adresse du serveur enregistrée : ${address.label}")
    }

    fun saveIdleLock(minutes: Int) {
        settings.update { it.copy(idleLockMinutes = minutes.coerceAtLeast(0)) }
    }

    // ---- Sign-in ----

    /** The accounts of the restaurant (sign-in list); kept on this PC so the list shows at once next time. */
    suspend fun accounts(): Result<List<AccountDto>> = call {
        api().accounts().data.orEmpty()
    }.onSuccess { list ->
        settings.update { s -> s.copy(accounts = list.map { CachedAccount(it.id, it.name, it.roles) }) }
    }

    fun cachedAccounts(): List<AccountDto> = settings.current.accounts.map { AccountDto(id = it.id, name = it.name, roles = it.roles) }

    /** Signs in with the account's password ("Mot de passe / PIN", as on the handheld). */
    suspend fun login(userId: Long, password: String): Result<Session> = call {
        val data = api().login(LoginRequest(userId = userId, password = password, deviceName = AppInfo.DEVICE_NAME)).data
            ?: throw ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
        Session(data.token, data.user)
    }.onSuccess { session ->
        sessions.start(session)
        settings.update { it.copy(lastUserId = session.user.id) }
        AppLog.info("Connexion : ${session.user.name} (#${session.user.id}), caisse ${session.caisse?.name ?: "aucune"}")
    }.onFailure { AppLog.info("Connexion refusée pour le compte #$userId : ${it.message}") }

    /** Reads the account again (rights, Caisse), e.g. after the gérant changed something. */
    suspend fun refreshSession(): Result<Session> = call {
        val user = api().me().data ?: throw ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
        sessions.updateUser(user)
        sessions.current ?: throw ApiFailure("Session expirée : reconnectez-vous.", FailureKind.SESSION_EXPIRED)
    }

    /** Ends the session: the token is deleted on the caisse (best effort), then forgotten here. */
    suspend fun logout(reason: String) {
        val session = sessions.current ?: return
        runCatching { api().logout() }.onFailure { AppLog.warn("Déconnexion : la caisse n'a pas répondu (${it.message})") }
        sessions.clear()
        AppLog.info("Déconnexion de ${session.user.name} : $reason")
    }

    // ---- The account's Caisse (Admin / Gérant may switch) ----

    suspend fun terminals(): Result<List<TerminalDto>> = call { api().terminals().data.orEmpty() }

    suspend fun switchTerminal(id: Long): Result<Session> = call {
        api().switchTerminal(SwitchTerminalRequest(id)).data
            ?: throw ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
        val user = api().me().data ?: throw ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
        sessions.updateUser(user)
        sessions.current ?: throw ApiFailure("Session expirée : reconnectez-vous.", FailureKind.SESSION_EXPIRED)
    }.onSuccess { AppLog.info("Caisse choisie : ${it.caisse?.name}") }

    // ---- Impression « Directe » (print agent) ----

    /**
     * An administrator signs in once for this PC's print agent: a session opened only for this
     * (its own token, ended right after), never the counter's session. Returns the branch key and
     * the printers; 403 when the account may not manage settings.
     */
    suspend fun fetchPrintAgentAccess(userId: Long, password: String): Result<PrintAgentDto> = try {
        val api = api()
        val login = api.login(LoginRequest(userId = userId, password = password, deviceName = AppInfo.DEVICE_NAME + "-agent")).data
            ?: throw ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
        val bearer = "Bearer ${login.token}"
        try {
            val access = api.printAgent(bearer).data ?: throw ApiFailure("Réponse inattendue de la caisse.", FailureKind.NOT_A_CAISSE)
            AppLog.info("Impression Directe : clé reçue (compte #$userId, succursale ${access.branch.name})")
            Result.success(access)
        } finally {
            runCatching { api.logoutAs(bearer) }
        }
    } catch (e: Exception) {
        Result.failure(ApiFailure.from(e, settings.current.server.https))
    }

    val settingsNow: AppSettings get() = settings.current
}
