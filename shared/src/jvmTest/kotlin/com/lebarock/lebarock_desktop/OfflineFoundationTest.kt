package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.offline.ConnectionMonitor
import com.lebarock.lebarock_desktop.data.offline.DeviceStateEntity
import com.lebarock.lebarock_desktop.data.offline.OfflineAccess
import com.lebarock.lebarock_desktop.data.offline.OfflineStatus
import com.lebarock.lebarock_desktop.data.offline.OfflineStore
import com.lebarock.lebarock_desktop.data.offline.OfflineSync
import com.lebarock.lebarock_desktop.data.offline.OpenCheckEntity
import com.lebarock.lebarock_desktop.data.offline.OrderRefEntity
import com.lebarock.lebarock_desktop.data.offline.OutboxEntity
import com.lebarock.lebarock_desktop.data.offline.ReviewItemEntity
import com.lebarock.lebarock_desktop.data.offline.SnapshotPartEntity
import com.lebarock.lebarock_desktop.data.offline.TableStateEntity
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.UserDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import com.lebarock.lebarock_desktop.ui.offline.bannerText
import com.lebarock.lebarock_desktop.ui.settings.OfflineModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline mode, desktop step 1 (client, 2026-11-10): the PC's own store, the connection watch, the heartbeat that
 * registers it as « Caisse PC D1 », the copy of the caisse and the sending of what waits — the handheld's foundation.
 */
class OfflineFoundationTest {

    private val dir: File = Files.createTempDirectory("altospos-offline").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<OfflineStore>()

    private fun store(file: File = File(dir, "offline.db")) = OfflineStore.open(file).also { opened += it }

    @AfterTest
    fun stop() {
        scope.cancel()
        opened.forEach { it.close() }
        runCatching { server.close() }
    }

    private fun action(id: String, seq: Long, ref: String? = null) =
        OutboxEntity(id, seq, "order.round", 4, "2026-11-10T20:00:00Z", """{"order_ref":"${ref ?: id}"}""", "Commande $id", 1_000L + seq, orderRef = ref)

    // ── The store ─────────────────────────────────────────────────────────

    @Test
    fun the_store_keeps_everything_across_a_restart() = runBlocking<Unit> {
        val file = File(dir, "offline.db")
        val first = store(file)
        assertTrue(first.persistent)
        assertNull(first.device(), "nothing before the first heartbeat")

        val device = first.updateDevice({ DeviceStateEntity(uuid = "pc-1") }) { it.copy(code = "D1", syncKeySealed = "aes:sealed") }
        assertEquals("pc-1", device.uuid)
        // Each action takes the device's next number, as one step.
        val a = first.enqueue({ error("the device exists") }) { d -> action("a", d.nextSeq) }
        val b = first.enqueue({ error("the device exists") }) { d -> action("b", d.nextSeq) }
        assertEquals(listOf(1L, 2L), listOf(a.seq, b.seq))
        first.saveSnapshotPart(SnapshotPartEntity("static", "v1", 2, 4, """{"menu":{"items":[]}}""", 5_000))
        first.saveOfflineAccesses(listOf(OfflineAccess(9, "Hors ligne", "aes:pin", null, 2, "Counter 1", 1), OfflineAccess(11, "Hors ligne Bar", "aes:pin2", null, 3, "Bar", 0)))
        first.close()

        val again = store(file)
        assertEquals(DeviceStateEntity("pc-1", "D1", "aes:sealed", nextSeq = 3), again.device())
        assertEquals(listOf("a", "b"), again.nextOutbox(50).map { it.id }, "in the order they were taken")
        assertEquals(2, again.outboxSize())
        assertEquals(1_001L, again.oldestOutbox())
        assertEquals("v1", again.snapshotPart("static")!!.version)
        assertEquals(listOf("Bar", "Counter 1"), again.offlineAccesses().map { it.caisseName }, "one PIN check per Caisse (O4a)")
        assertEquals("Hors ligne", again.offlineAccess(2)!!.accountName)
        assertEquals(2, again.outboxCount().first(), "the flows read the file")
    }

    @Test
    fun checks_replace_the_last_copy_and_clearing_keeps_what_waits() = runBlocking<Unit> {
        val s = store()
        val check = { id: Long, table: Long? -> OpenCheckEntity(id, "#$id", null, table, "kot", 12.5, """{"id":$id}""", 1) }
        s.replaceChecks(SnapshotPartEntity("checks", null, 2, 4, "{}", 1), listOf(check(10, 1), check(11, null)), listOf(TableStateEntity(1, "running", "{}")))
        assertEquals(listOf(10L, 11L), s.openChecks().map { it.id })
        assertNull(s.openChecks()[1].tableId, "a take-away check has no table")
        s.replaceChecks(SnapshotPartEntity("checks", null, 2, 4, "{}", 2), listOf(check(12, 3)), emptyList())
        assertEquals(listOf(12L), s.openChecks().map { it.id }, "the whole list, not added to the old one")
        assertEquals(emptyList(), s.tableStates())
        assertEquals(1, s.openCheckCount().first())

        s.insertOutbox(action("a", 1))
        s.saveSnapshotPart(SnapshotPartEntity("static", "v1", 2, 4, "{}", 1))
        s.saveOfflineAccesses(listOf(OfflineAccess(9, "Hors ligne", "aes:pin", "aes:pay", 2, "Counter 1", 1)))
        s.saveReview(ReviewItemEntity("r", 7, "payment.tender", "refused", "paid", "Déjà payée.", "Paiement", "2026-11-10T20:00:00Z", "{}", null, 1))
        s.clearCopy()
        assertNull(s.snapshotPart("static"))
        assertNull(s.offlineAccess(), "the PIN checks go with the copy")
        assertEquals(emptyList(), s.openChecks())
        assertEquals(1, s.outboxSize(), "an action that waits is never erased")
        assertEquals(1, s.reviews().size)
    }

    @Test
    fun an_answered_action_leaves_the_outbox() = runBlocking<Unit> {
        val s = store()
        s.insertOutbox(action("a", 1, ref = "ref-a"))
        s.insertOutbox(action("b", 2))
        s.markAttempt(listOf("a", "b"), "HTTP 500")
        assertEquals(listOf(1, 1), s.nextOutbox(50).map { it.attempts })

        s.settle("a", null, OrderRefEntity("ref-a", "D1-001", 812, "#812", 9))
        assertEquals(listOf("b"), s.outboxIds())
        assertEquals(OrderRefEntity("ref-a", "D1-001", 812, "#812", 9), s.orderRef("ref-a"))
        s.settle("b", ReviewItemEntity("b", 2, "order.round", "refused", "table_gone", "Table supprimée.", "Commande b", "2026-11-10T20:00:00Z", "{}", null, 9), null)
        assertEquals(0, s.outboxSize())
        assertEquals("Table supprimée.", s.review("b")!!.message)
        s.deleteReview("b")
        assertEquals(0, s.reviewCount().first())
    }

    @Test
    fun a_damaged_file_is_set_aside_and_the_counter_still_opens() = runBlocking<Unit> {
        val file = File(dir, "damaged.db").apply { writeText("this is not a database, it only looks like a file ".repeat(40)) }
        val s = store(file)
        assertTrue(s.persistent)
        assertNull(s.device())
        s.insertOutbox(action("a", 1))
        assertEquals(1, s.outboxSize())
        assertTrue(File(file.path + ".bad").exists(), "kept for support")
        assertFalse(OfflineStore.inMemory().also { opened += it }.persistent)
    }

    // ── The connection watch ──────────────────────────────────────────────

    @Test
    fun the_pc_goes_offline_after_ten_seconds_without_an_answer() {
        val clock = AtomicLong(0)
        val monitor = ConnectionMonitor(clock::get)
        monitor.failed()
        assertTrue(monitor.doubtful.value)
        assertFalse(monitor.offline.value, "one failed call is not an outage")
        clock.set(9_000)
        monitor.failed()
        assertFalse(monitor.offline.value)
        clock.set(10_000)
        monitor.failed()
        assertTrue(monitor.offline.value)
        clock.set(12_000)
        monitor.answered()
        assertFalse(monitor.offline.value, "back at the first answer")
        assertFalse(monitor.doubtful.value)
        assertEquals(12_000, monitor.lastAnswerAt.value)
        monitor.failed()
        clock.set(30_000)
        monitor.reset()
        monitor.failed()
        assertFalse(monitor.offline.value, "another address: the count starts again")
    }

    // ── The caisse ────────────────────────────────────────────────────────

    private val routes = ConcurrentHashMap<String, ArrayDeque<MockResponse>>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val server = MockWebServer().also {
        it.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val queue = routes[request.url.encodedPath]
                return synchronized(routes) { queue?.removeFirstOrNull() } ?: json("""{"status":"error","message":"not found"}""", 404)
            }
        }
        it.start()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun answer(path: String, response: MockResponse) {
        synchronized(routes) { routes.getOrPut(path) { ArrayDeque() }.addLast(response) }
    }

    private fun sent(path: String) = requests.filter { it.url.encodedPath == path }

    private val clock = AtomicLong(1_794_340_000_000)
    private val settings = SettingsStore(File(dir, "settings.json")).apply { update { it.copy(server = ServerAddress(false, server.hostName, server.port)) } }
    private val sessions = SessionHolder().apply { start(Session("t0ken", UserDto(id = 4, name = "Amel"))) }
    private val secrets = SecretBox(dir, windows = false)
    private val monitor = ConnectionMonitor(clock::get)
    private val client = ApiClient({ sessions.token }, watch = monitor.interceptor)
    private val db = store(File(dir, "sync.db"))
    private val sync = OfflineSync(db, client, settings, sessions, secrets, monitor, scope, clock::get)

    private fun heartbeat(code: String = "D1", key: String? = "k3y-from-the-caisse", stale: Boolean = false, extra: String = "") = json(
        """{"status":"success","data":{"device":{"id":3,"uuid":"x","code":"$code"},"server_time":"2026-11-10T20:00:00+00:00",
            "snapshot_version":"v1","snapshot_stale":$stale,"waiting":[]${key?.let { ""","sync_key":"$it"""" } ?: ""}$extra}}""",
    )

    /** The branch's copy (O4a): two Caisses, each with its « Hors ligne » account. */
    private val static = """{"format":2,"device":{"code":"D1"},
        "caisses":[{"id":2,"name":"Counter 1","menu_id":1,"offline_account":{"id":9,"name":"Hors ligne Counter 1","verifier":"pbkdf2-sha256${'$'}100000${'$'}c2FsdA==${'$'}aGFzaA=="}},
                   {"id":3,"name":"Bar","menu_id":null,"offline_account":{"id":11,"name":"Hors ligne Bar","verifier":"pbkdf2-sha256${'$'}100000${'$'}YmFy${'$'}YmFyMg=="}}],
        "menu":{"items":[{"id":1,"name":"Coca","menu_id":1},{"id":2,"name":"Café","menu_id":1}]},
        "areas":[{"id":1,"name":"Salle","tables":[{"id":1,"code":"1"},{"id":2,"code":"2"}]},{"id":2,"name":"Terrasse","tables":[{"id":3,"code":"T1"}]}],
        "offline":{"payment_pin":"pbkdf2-sha256${'$'}100000${'$'}cGF5${'$'}cGlu"}}"""

    private val checks = """{"generated_at":"2026-11-10T20:00:00+00:00",
        "open_checks":[{"id":812,"order_number":"812","table":{"id":1,"code":"1"},"status":"kot","total":18.5},{"id":813,"order_number":813,"offline_number":"H1-004","status":"billed","total":7}],
        "tables":[{"id":1,"status":"running","session":{"pax":2}},{"id":2,"status":"available"}]}"""

    private suspend fun eventually(what: String, check: () -> Boolean) {
        runCatching { withTimeout(5_000) { while (!check()) delay(20) } }.onFailure { error("Never happened: $what") }
    }

    @Test
    fun a_signed_in_account_registers_this_pc_and_its_key_is_kept_sealed() = runBlocking<Unit> {
        answer("/api/sync/heartbeat", heartbeat())
        sync.heartbeat()

        val first = sent("/api/sync/heartbeat").single()
        val body = Json.parseToJsonElement(first.body!!.utf8()).jsonObject
        assertEquals("desktop", body["kind"]!!.jsonPrimitive.content, "the caisse names it D…, « Caisse PC »")
        assertEquals("Bearer t0ken", first.headers["Authorization"])
        assertNull(first.headers["X-Sync-Key"], "no key yet")
        val device = db.device()!!
        assertEquals(body["device_uuid"]!!.jsonPrimitive.content, device.uuid)
        assertEquals("D1", device.code)
        assertFalse("k3y-from-the-caisse" in device.syncKeySealed!!, "never in clear on the disk")
        assertEquals("k3y-from-the-caisse", secrets.open(device.syncKeySealed))
        assertEquals(1_794_340_800_000 - clock.get(), device.clockOffsetMs, "offline times go on the caisse's clock")
        eventually("the code reaches Réglages") { sync.deviceCode.value == "D1" }

        // The next one carries the key; the same uuid for ever.
        answer("/api/sync/heartbeat", heartbeat(key = null))
        sync.heartbeat()
        val second = sent("/api/sync/heartbeat")[1]
        assertEquals("k3y-from-the-caisse", second.headers["X-Sync-Key"])
        assertEquals(device.uuid, Json.parseToJsonElement(second.body!!.utf8()).jsonObject["device_uuid"]!!.jsonPrimitive.content)
        assertEquals("k3y-from-the-caisse", secrets.open(db.device()!!.syncKeySealed), "kept when no new one is issued")
        assertEquals("D1-001", sync.nextOfflineNumber())
        assertEquals("D1-002", sync.nextOfflineNumber())
    }

    @Test
    fun with_nobody_signed_in_only_a_pc_that_holds_a_key_speaks() = runBlocking<Unit> {
        sessions.clear()
        sync.heartbeat()
        sync.refreshSnapshot("static,checks")
        sync.push()
        assertEquals(0, requests.size, "an unknown PC with nobody signed in asks nothing")
        assertNull(sync.nextOfflineNumber(), "no code before the first heartbeat")
        eventually("not active") { !sync.status.value.active }

        db.updateDevice({ DeviceStateEntity(uuid = "pc-1") }) { it.copy(code = "D1", syncKeySealed = secrets.seal("k3y")) }
        answer("/api/sync/heartbeat", heartbeat(key = null))
        sync.heartbeat()
        val alone = sent("/api/sync/heartbeat").single()
        assertEquals("k3y", alone.headers["X-Sync-Key"])
        assertNull(alone.headers["Authorization"], "the device key alone")
        eventually("active with its key") { sync.status.value.active }
        // O4a: the copy too, with the key alone.
        answer("/api/sync/branch-snapshot", json("""{"status":"success","data":{"branch_id":1,"version":"v1","static":$static,"checks":$checks}}"""))
        sync.refreshSnapshot("static,checks")
        val copy = sent("/api/sync/branch-snapshot").single()
        assertEquals("k3y", copy.headers["X-Sync-Key"])
        assertNull(copy.headers["Authorization"], "no account needed for the copy")
        assertEquals(2, db.offlineAccesses().size)

        // The caisse no longer knows the key: said in Réglages and on the banner once something waits.
        answer("/api/sync/heartbeat", json("""{"status":"error","code":"unauthenticated","message":"Appareil non reconnu."}""", 401))
        sync.heartbeat()
        assertTrue(db.device()!!.keyRefused)
        eventually("key refused") { sync.status.value.keyRefused }
        assertEquals("clé refusée : enregistrez ce poste à nouveau avec la clé de synchronisation", OfflineModel.stateLine(sync.status.value))
    }

    @Test
    fun the_branch_key_registers_this_pc_without_any_sign_in() = runBlocking<Unit> {
        sessions.clear()

        // Wrong key: the caisse's French message, nothing kept.
        answer("/api/sync/heartbeat", json("""{"status":"error","code":"enrol_key_wrong","message":"Clé de synchronisation incorrecte : recopiez celle de la caisse (Paramètres → Mode hors ligne)."}""", 401))
        val wrong = sync.enrol("aaaa bbbb cccc")
        assertEquals("Clé de synchronisation incorrecte : recopiez celle de la caisse (Paramètres → Mode hors ligne).", wrong.exceptionOrNull()?.message)
        assertEquals("AAAA-BBBB-CCCC", sent("/api/sync/heartbeat").single().headers["X-Sync-Enrol"], "typed loosely, sent as the caisse shows it")
        assertNull(db.device()!!.syncKeySealed)
        assertEquals("La clé compte 12 lettres et chiffres (ex. K7QM-2XRT-9HPB).", sync.enrol("K7QM").exceptionOrNull()?.message)

        // The right key: the PC's own key, then the copy with it.
        answer("/api/sync/heartbeat", heartbeat())
        answer("/api/sync/branch-snapshot", json("""{"status":"success","data":{"branch_id":1,"version":"v1","static":$static,"checks":$checks}}"""))
        assertEquals("D1", sync.enrol("K7QM-2XRT-9HPB").getOrThrow())
        val enrolled = sent("/api/sync/heartbeat")[1]
        assertNull(enrolled.headers["Authorization"])
        assertNull(enrolled.headers["X-Sync-Key"])
        assertEquals(2, Json.parseToJsonElement(enrolled.body!!.utf8()).jsonObject["snapshot_format"]!!.jsonPrimitive.content.toInt())
        assertEquals("k3y-from-the-caisse", secrets.open(db.device()!!.syncKeySealed))
        assertEquals("k3y-from-the-caisse", sent("/api/sync/branch-snapshot").single().headers["X-Sync-Key"])
        assertNotNull(db.snapshotPart("static"))
        eventually("registered") { sync.status.value.registered && sync.status.value.active }

        // Registered again (a new key from the caisse): the old key works until the new one comes.
        answer("/api/sync/heartbeat", json("""{"status":"error","code":"enrol_key_wrong","message":"Clé incorrecte."}""", 401))
        assertTrue(sync.enrol("K7QM-2XRT-0000").isFailure)
        assertEquals("k3y-from-the-caisse", secrets.open(db.device()!!.syncKeySealed), "a failed try keeps the working key")
    }

    @Test
    fun the_copy_is_stored_without_the_pin_checks() = runBlocking<Unit> {
        answer("/api/sync/heartbeat", heartbeat())
        answer("/api/sync/branch-snapshot", json("""{"status":"success","data":{"branch_id":1,"version":"v1","static":$static,"checks":$checks}}"""))
        sync.refreshSnapshot("static,checks")

        val asked = sent("/api/sync/branch-snapshot").single()
        assertEquals("static,checks", asked.url.queryParameter("parts"))
        assertEquals(db.device()!!.uuid, asked.headers["X-Device-Uuid"])
        assertEquals("k3y-from-the-caisse", asked.headers["X-Sync-Key"], "the heartbeat came first: the PC had no key")
        assertNull(asked.headers["If-None-Match"])

        val stored = db.snapshotPart("static")!!
        assertEquals("v1", stored.version)
        assertEquals(0L, stored.caisseId, "the branch's copy: every Caisse")
        assertFalse("pbkdf2" in stored.json, "the PIN checks are not in the copy")
        val caisses = Json.parseToJsonElement(stored.json).jsonObject["caisses"]!!.jsonArray
        assertEquals("Hors ligne Counter 1", caisses[0].jsonObject["offline_account"]!!.jsonObject["name"]!!.jsonPrimitive.content, "orders will be recorded as it")
        val access = db.offlineAccess(2)!!
        assertEquals(9L, access.accountId)
        assertEquals("Counter 1", access.caisseName)
        assertEquals(11L, db.offlineAccess(3)!!.accountId)
        assertFalse("pbkdf2" in access.accountVerifierSealed!!)
        assertTrue(secrets.open(access.accountVerifierSealed)!!.startsWith("pbkdf2-sha256$100000$"))
        assertTrue(secrets.open(access.paymentVerifierSealed)!!.startsWith("pbkdf2-sha256$"))

        assertEquals(listOf(812L, 813L), db.openChecks().map { it.id })
        assertEquals("813", db.openChecks()[1].number, "a number the caisse sent as a number")
        assertEquals("H1-004", db.openChecks()[1].offlineNumber)
        assertEquals(1L, db.openChecks()[0].tableId)
        assertEquals(listOf("running", "available"), db.tableStates().map { it.status })
        eventually("Réglages shows the copy") { sync.snapshot.value.openChecks == 2 && sync.snapshot.value.items == 2 }
        assertEquals(3, sync.snapshot.value.tables)
        assertTrue(OfflineModel.copyLine(sync.snapshot.value).startsWith("2 articles, 3 tables, 2 commandes ouvertes · Counter 1, Bar — "))
        assertEquals("pas encore téléchargées", OfflineModel.copyLine(com.lebarock.lebarock_desktop.data.offline.SnapshotSummary()))

        // Unchanged: asked with its version, nothing downloaded again.
        answer("/api/sync/branch-snapshot", MockResponse.Builder().code(304).build())
        sync.refreshSnapshot("static")
        assertEquals("\"v1\"", sent("/api/sync/branch-snapshot")[1].headers["If-None-Match"])
        assertEquals("v1", db.snapshotPart("static")!!.version)

        // The checks alone, every 30 s: the list is replaced.
        answer("/api/sync/branch-snapshot", json("""{"status":"success","data":{"branch_id":1,"checks":{"open_checks":[],"tables":[]}}}"""))
        sync.refreshSnapshot("checks")
        assertEquals(emptyList(), db.openChecks())
        assertNotNull(db.snapshotPart("static"), "the menu stays")

        // The caisse dropped the offline PIN: nothing is kept of it here.
        answer("/api/sync/branch-snapshot", json("""{"status":"success","data":{"branch_id":1,"version":"v2","static":{"format":2,"caisses":[{"id":2,"name":"Counter 1","offline_account":null}],"offline":{"payment_pin":null}}}}"""))
        sync.refreshSnapshot("static")
        assertEquals(emptyList(), db.offlineAccesses())
        assertEquals("v2", db.snapshotPart("static")!!.version)
    }

    @Test
    fun what_waits_is_sent_in_order_and_refusals_are_kept_to_be_seen() = runBlocking<Unit> {
        answer("/api/sync/heartbeat", heartbeat())
        sync.heartbeat()
        val data = { ref: String -> buildJsonObject { put("order_ref", ref); put("offline_number", "D1-001") } }
        val a = sync.enqueue("order.create", data("ref-a"), actorId = 9, label = "Commande D1-001 · T5", orderRef = "ref-a")
        val b = sync.enqueue("order.round", data("ref-a"), actorId = 9, label = "Commande D1-001 · T5", orderRef = "ref-a")
        val c = sync.enqueue("order.bill", data("ref-a"), actorId = 9, label = "Addition D1-001", orderRef = "ref-a")
        assertEquals(listOf(1L, 2L, 3L), listOf(a.seq, b.seq, c.seq))
        assertEquals("2026-11-10T20:00:00Z", a.at, "on the caisse's clock, not this PC's")
        assertTrue(sync.shouldQueue() || db.outboxSize() == 3, "new actions queue behind what waits")

        answer(
            "/api/sync/batch",
            json(
                """{"status":"success","data":{"stopped":false,"results":[
                {"id":"${a.id}","seq":1,"type":"order.create","status":"applied","mapping":{"order_ref":"ref-a","order_id":812,"order_number":"812"}},
                {"id":"${b.id}","seq":2,"type":"order.round","status":"refused","code":"item_gone","message":"Article supprimé du menu."},
                {"id":"${c.id}","seq":3,"type":"order.bill","status":"retry"}]}}""",
            ),
        )
        answer("/api/sync/branch-snapshot", json("""{"status":"success","data":{"branch_id":1,"checks":$checks}}"""))
        sync.push()

        val batch = sent("/api/sync/batch").single()
        assertEquals("k3y-from-the-caisse", batch.headers["X-Sync-Key"])
        val commands = Json.parseToJsonElement(batch.body!!.utf8()).jsonObject["commands"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf("1", "2", "3"), commands.map { it["seq"]!!.jsonPrimitive.content })
        assertEquals("9", commands[0]["actor_id"]!!.jsonPrimitive.content, "the one who did it")
        assertEquals("ref-a", commands[0]["data"]!!.jsonObject["order_ref"]!!.jsonPrimitive.content)

        assertEquals(listOf(c.id), db.outboxIds(), "the caisse asked for the bill again: it waits, in its place")
        assertEquals(OrderRefEntity("ref-a", "D1-001", 812, "812", clock.get()), db.orderRef("ref-a"))
        val refused = db.reviews().single()
        assertEquals("Article supprimé du menu.", refused.message)
        assertEquals("Commande D1-001 · T5", refused.label)
        assertEquals(clock.get(), db.device()!!.lastSyncAt)
        assertEquals(listOf(812L, 813L), db.openChecks().map { it.id }, "the open checks are read again after a sending")
        eventually("one left, one to check") { sync.status.value.pending == 1 && sync.reviewCount.value == 1 && !sync.status.value.syncing }

        // « Effacer » waits for the outbox; the key refused is said on the banner.
        assertEquals("1 action(s) pas encore envoyée(s) à la caisse : synchronisez d'abord.", sync.clearCopy().exceptionOrNull()!!.message)
        answer("/api/sync/batch", json("""{"status":"error","code":"unauthenticated"}""", 401))
        sync.push()
        assertTrue(db.device()!!.keyRefused)
        assertEquals(1, db.nextOutbox(1).single().attempts)
        eventually("the banner says it") { sync.status.value.keyRefused }
        assertEquals("Synchronisation impossible : enregistrez ce poste avec la clé de synchronisation (Réglages → Mode hors ligne) (1 action en attente).", bannerText(sync.status.value))

        // A new sign-in gives a new key: the last action goes, the copy can be erased.
        answer("/api/sync/heartbeat", heartbeat(key = "n3w-key"))
        sync.heartbeat()
        answer("/api/sync/batch", json("""{"status":"success","data":{"results":[{"id":"${c.id}","seq":3,"status":"merged","mapping":{"order_ref":"ref-a","order_id":812,"order_number":"812"}}]}}"""))
        sync.push()
        assertEquals("n3w-key", sent("/api/sync/batch").last().headers["X-Sync-Key"])
        assertEquals(0, db.outboxSize())
        assertFalse(db.device()!!.keyRefused)
        assertTrue(sync.clearCopy().isSuccess)
        assertNull(db.snapshotPart("checks"))
    }

    @Test
    fun a_caisse_that_stops_answering_puts_the_banner_up() = runBlocking<Unit> {
        answer("/api/sync/heartbeat", heartbeat())
        sync.heartbeat()
        assertNull(bannerText(sync.status.value), "nothing to say while the caisse answers")

        // The gateway answers for a caisse that is down (502 / 503 / 504): the same as no answer.
        answer("/api/sync/ping", json("{}", 503))
        runCatching { client.apiFor(settings.current.server.baseUrl).syncPing() }
        assertTrue(monitor.doubtful.value)
        assertFalse(monitor.offline.value)
        clock.addAndGet(10_000)
        answer("/api/sync/ping", json("{}", 502))
        runCatching { client.apiFor(settings.current.server.baseUrl).syncPing() }
        assertTrue(monitor.offline.value)
        eventually("offline") { sync.status.value.offline }
        assertEquals("Hors ligne — aucune action en attente", bannerText(sync.status.value))
        assertEquals("hors ligne (la caisse ne répond pas)", OfflineModel.stateLine(sync.status.value))

        // « Tester la connexion » on another address fails without touching what is known of the saved caisse.
        monitor.answered()
        val elsewhere = MockWebServer().also { it.start() }
        val gone = "http://${elsewhere.hostName}:${elsewhere.port}/"
        elsewhere.close()
        runCatching { client.probeFor(gone).ping() }
        assertFalse(monitor.doubtful.value)

        answer("/api/sync/ping", json("""{"status":"ok","server_time":"2026-11-10T20:00:20+00:00"}"""))
        assertEquals("ok", client.apiFor(settings.current.server.baseUrl).syncPing().status)
        eventually("back online") { !sync.status.value.offline }
        assertNull(bannerText(sync.status.value))
    }

    @Test
    fun the_banner_speaks_only_when_there_is_something_to_say() {
        assertNull(bannerText(OfflineStatus(active = false, offline = true)), "a PC the caisse never knew: no offline mode yet")
        assertNull(bannerText(OfflineStatus(active = true)))
        assertEquals("Hors ligne — 3 actions en attente", bannerText(OfflineStatus(active = true, offline = true, pending = 3)))
        assertEquals("Synchronisation… (1 action en attente)", bannerText(OfflineStatus(active = true, syncing = true, pending = 1)))
        assertNull(bannerText(OfflineStatus(active = true, keyRefused = true)), "a refused key matters once something waits")
    }
}
