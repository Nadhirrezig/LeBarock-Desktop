package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.ApiFailure
import com.lebarock.lebarock_desktop.data.CaisseRepository
import com.lebarock.lebarock_desktop.data.FailureKind
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.TrustStore
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.CachedAccount
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.ui.login.LoginModel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connection test, the sign-in and the error messages against a local server that answers
 * like the caisse (routes under /api/mobile), in HTTP and in HTTPS with a test certificate authority
 * standing in for mkcert.
 */
class CaisseRepositoryTest {

    private val dir: File = Files.createTempDirectory("altospos-test").toFile()
    private val certsDir = File(dir, "certs").apply { mkdirs() }
    private val settings = SettingsStore(File(dir, "settings.json"))
    private val sessions = SessionHolder()
    private val client = ApiClient({ sessions.token }, { TrustStore.trustManager(certsDir) })
    private val fixedNow = Instant.parse("2026-11-08T12:00:00Z")
    private val repository = CaisseRepository(client, settings, sessions, now = { fixedNow })
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun stop() {
        servers.forEach { runCatching { it.close() } }
    }

    private fun server(https: HandshakeCertificates? = null): MockWebServer =
        MockWebServer().also { s ->
            if (https != null) s.useHttps(https.sslSocketFactory())
            s.start()
            servers += s
        }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun addressOf(s: MockWebServer, https: Boolean) = ServerAddress(https, s.hostName, s.port)

    private val ping = """{"status":"success","data":{"product":"AltosPos","restaurant_name":"Le Baroque",
        "currency":{"code":"TND","symbol":"DT","position":"right_with_space","decimals":3},
        "theme_hex":"#86EFAC","server_time":"2026-11-08T12:03:30.000000Z"}}"""

    // ── Tester la connexion ────────────────────────────────────────────────

    @Test
    fun the_test_names_the_restaurant_and_the_clock_gap() = runBlocking<Unit> {
        val s = server()
        s.enqueue(json(ping))
        val check = repository.testConnection(addressOf(s, https = false)).getOrThrow()
        assertEquals("Le Baroque", check.restaurantName)
        assertEquals(Duration.ofSeconds(210), check.clockOffset)
        assertTrue(check.clockWarning, "3.5 minutes apart is worth a warning")
        assertEquals("/api/mobile/ping", s.takeRequest().url.encodedPath)
    }

    @Test
    fun https_with_mkcert_like_certificate_needs_its_root_then_works() = runBlocking<Unit> {
        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("mkcert test root").build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        val s = server(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build())
        val address = addressOf(s, https = true)

        // Not trusted on this PC yet: the message says to install or import rootCA.pem.
        val refused = repository.testConnection(address).exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.CERTIFICATE_UNTRUSTED, refused.kind)
        assertTrue(refused.hint!!.contains("rootCA.pem"))

        // Réglages → Certificat → Importer rootCA.pem: then the caisse answers.
        val pem = File(dir, "rootCA.pem").apply { writeText(root.certificatePem()) }
        val imported = TrustStore.import(pem, certsDir).getOrThrow()
        assertEquals("mkcert test root", imported.subject)
        client.reloadTrust()
        s.enqueue(json(ping))
        assertEquals("Le Baroque", repository.testConnection(address).getOrThrow().restaurantName)
    }

    @Test
    fun a_certificate_made_for_another_address_is_named_as_such() = runBlocking<Unit> {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("caisse.other").build()
        val s = server(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build())
        TrustStore.import(File(dir, "root.pem").apply { writeText(root.certificatePem()) }, certsDir).getOrThrow()
        client.reloadTrust()
        val failure = repository.testConnection(addressOf(s, https = true)).exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.CERTIFICATE_WRONG_HOST, failure.kind)
    }

    @Test
    fun the_server_certificate_is_not_accepted_as_a_root() {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        val failure = TrustStore.import(File(dir, "server.pem").apply { writeText(leaf.certificatePem()) }, certsDir)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("racine"))
        val notACert = TrustStore.import(File(dir, "notes.txt").apply { writeText("hello") }, certsDir)
        assertTrue(notACert.isFailure)
        assertTrue(TrustStore.imported(certsDir).isEmpty())
    }

    @Test
    fun https_to_a_plain_http_port_is_explained() = runBlocking<Unit> {
        // A web server on a plain HTTP port answers a TLS hello at once with HTTP text (as nginx does).
        java.net.ServerSocket(0).use { plain ->
            val answering = kotlin.concurrent.thread {
                runCatching {
                    plain.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(512))
                        socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            }
            val failure = repository.testConnection(ServerAddress(true, "127.0.0.1", plain.localPort)).exceptionOrNull() as ApiFailure
            answering.join(2_000)
            assertEquals(FailureKind.HTTPS_TO_HTTP_PORT, failure.kind, failure.message)
        }
    }

    @Test
    fun nothing_listening_on_the_port_is_a_refused_connection() = runBlocking<Unit> {
        val freePort = ServerSocket(0).use { it.localPort }
        val failure = repository.testConnection(ServerAddress(false, "127.0.0.1", freePort)).exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.CONNECTION_REFUSED, failure.kind)
        assertTrue(failure.unreachable)
    }

    @Test
    fun another_web_server_is_not_a_caisse() = runBlocking<Unit> {
        val s = server()
        s.enqueue(MockResponse.Builder().code(404).body("<html>Not found</html>").build())
        s.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>IIS</html>").build())
        s.enqueue(json("""{"status":"success","data":{}}"""))
        val address = addressOf(s, https = false)
        repeat(3) {
            val failure = repository.testConnection(address).exceptionOrNull() as ApiFailure
            assertEquals(FailureKind.NOT_A_CAISSE, failure.kind, "answer #${it + 1}")
        }
    }

    @Test
    fun a_gateway_without_the_caisse_behind_it_says_so() = runBlocking<Unit> {
        val s = server()
        s.enqueue(MockResponse.Builder().code(502).body("Bad Gateway").build())
        val failure = repository.testConnection(addressOf(s, https = false)).exceptionOrNull() as ApiFailure
        assertEquals(FailureKind.SERVER_ERROR, failure.kind)
        assertTrue(failure.unreachable)
    }

    // ── Enregistrer ────────────────────────────────────────────────────────

    @Test
    fun saving_another_server_forgets_the_old_restaurants_accounts() = runBlocking<Unit> {
        val s = server()
        s.enqueue(json(ping))
        val address = addressOf(s, https = false)
        val check = repository.testConnection(address).getOrThrow()
        settings.update { it.copy(accounts = listOf(CachedAccount(1, "Ancien")), lastUserId = 1) }
        repository.saveServer(address, check)
        assertEquals("Le Baroque", settings.current.restaurantName)
        assertEquals("DT", settings.current.currency.symbol)
        assertEquals(3, settings.current.currency.decimals)
        assertTrue(settings.current.accounts.isEmpty())
        assertNull(settings.current.lastUserId)

        // The same server saved again keeps them.
        settings.update { it.copy(accounts = listOf(CachedAccount(2, "Amel")), lastUserId = 2) }
        repository.saveServer(address, check)
        assertEquals(1, settings.current.accounts.size)
    }

    // ── Connexion ──────────────────────────────────────────────────────────

    private fun signedInServer(): MockWebServer {
        val s = server()
        repository.saveServer(addressOf(s, https = false), null)
        return s
    }

    private val loginOk = """{"status":"success","data":{"token":"7|abcdef","user":{"id":3,"name":"Amel Caissière",
        "roles":["Caissier"],"branch_id":1,"restaurant_id":1,"can_switch_terminal":false,
        "terminal":{"id":1,"name":"Caisse","menu_id":2},
        "abilities":{"kitchen":false,"orders":true,"take_payment":true,"cancel_order":false,"force_send":false,
        "show_orders":true,"cash_register":true,"use_cash_register":true,"apply_discount":true,"give_credit":false,
        "assign_waiter":true,"create_expense":false,"manage_settings":false}}}}"""

    @Test
    fun the_account_list_is_kept_for_the_next_start() = runBlocking<Unit> {
        val s = signedInServer()
        s.enqueue(json("""{"status":"success","data":[{"id":3,"name":"Amel","roles":["Caissier"]},{"id":"4","name":"Karim","roles":["Serveur"]}]}"""))
        val accounts = repository.accounts().getOrThrow()
        assertEquals(listOf(3L, 4L), accounts.map { it.id })
        assertEquals(listOf("Amel", "Karim"), repository.cachedAccounts().map { it.name })
    }

    @Test
    fun signing_in_keeps_the_token_in_memory_and_sends_it_after() = runBlocking<Unit> {
        val s = signedInServer()
        s.enqueue(json(loginOk))
        val session = repository.login(3, "1234").getOrThrow()
        val login = s.takeRequest()
        assertEquals("/api/mobile/login", login.url.encodedPath)
        val body = login.body!!.utf8()
        assertTrue(body.contains("\"user_id\":3") && body.contains("\"device_name\":\"altospos-desktop\""), body)
        assertEquals("Caisse", session.caisse?.name)
        assertTrue(session.rights.cashRegister && session.rights.takePayment && !session.rights.giveCredit)
        assertEquals(3L, settings.current.lastUserId)
        assertTrue(!File(dir, "settings.json").readText().contains("abcdef"), "the token is never written to disk")

        s.enqueue(json("""{"status":"success","data":${loginOk.substringAfter("\"user\":").removeSuffix("}}")}}"""))
        repository.refreshSession().getOrThrow()
        assertEquals("Bearer 7|abcdef", s.takeRequest().headers["Authorization"])
    }

    @Test
    fun a_wrong_password_reads_as_such() = runBlocking<Unit> {
        val s = signedInServer()
        s.enqueue(json("""{"message":"Identifiants incorrects.","errors":{"password":["Identifiants incorrects."]}}""", 422))
        val failure = repository.login(3, "0000").exceptionOrNull() as ApiFailure
        assertEquals("Identifiants incorrects.", failure.message)
        assertTrue(LoginModel.isWrongPassword(failure))
        assertNull(sessions.current)

        s.enqueue(json("""{"message":"Restaurant is inactive. Contact admin.","errors":{"email":["Restaurant is inactive. Contact admin."]}}""", 422))
        val other = repository.login(3, "1234").exceptionOrNull() as ApiFailure
        assertTrue(!LoginModel.isWrongPassword(other))

        s.enqueue(json("""{"message":"Too Many Attempts."}""", 429))
        val throttled = repository.login(3, "1234").exceptionOrNull() as ApiFailure
        assertTrue(throttled.message!!.contains("Trop de tentatives"))
    }

    @Test
    fun a_refused_token_ends_the_session() = runBlocking<Unit> {
        val s = signedInServer()
        s.enqueue(json(loginOk))
        repository.login(3, "1234").getOrThrow()
        s.enqueue(json("""{"message":"Unauthenticated."}""", 401))
        val failure = repository.refreshSession().exceptionOrNull() as ApiFailure
        assertTrue(failure.sessionExpired)
        assertNull(sessions.current)
    }

    @Test
    fun signing_out_forgets_the_session_even_when_the_caisse_is_down() = runBlocking<Unit> {
        val s = signedInServer()
        s.enqueue(json(loginOk))
        repository.login(3, "1234").getOrThrow()
        s.close()
        repository.logout("test")
        assertNull(sessions.current)
    }

    @Test
    fun a_manager_switches_the_accounts_caisse() = runBlocking<Unit> {
        val s = signedInServer()
        s.enqueue(json(loginOk.replace("\"can_switch_terminal\":false", "\"can_switch_terminal\":true")))
        repository.login(3, "1234").getOrThrow()
        s.takeRequest()
        s.enqueue(json("""{"status":"success","data":{"id":5,"name":"Terrasse","menu_id":7}}"""))
        s.enqueue(json("""{"status":"success","data":${loginOk.substringAfter("\"user\":").removeSuffix("}}").replace("\"id\":1,\"name\":\"Caisse\"", "\"id\":5,\"name\":\"Terrasse\"")}}"""))
        val session = repository.switchTerminal(5).getOrThrow()
        assertEquals("Terrasse", session.caisse?.name)
        val switch = s.takeRequest()
        assertEquals("/api/mobile/terminal", switch.url.encodedPath)
        assertTrue(switch.body!!.utf8().contains("\"order_place_id\":5"))
        assertNotNull(sessions.current)
    }

    @Test
    fun the_search_ignores_case_and_accents() {
        assertEquals("helene", LoginModel.fold("Hélène"))
        assertEquals("caissiere", LoginModel.fold("  CAISSIÈRE "))
    }
}
