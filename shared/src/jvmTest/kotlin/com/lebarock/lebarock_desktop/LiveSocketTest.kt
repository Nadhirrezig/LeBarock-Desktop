package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.realtime.LiveEvent
import com.lebarock.lebarock_desktop.data.realtime.LiveSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live updates (client, 2026-11-11): the desktop's websocket to Reverb (Pusher protocol 7) against a fake server —
 * it joins its channel with the caisse's signature, answers pings, hands over the signals, and comes back by itself
 * when the server drops it.
 */
class LiveSocketTest {
    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<String>()
    private var serverSocket: WebSocket? = null

    /** A fake Reverb: greets, accepts a signed subscription, then sends one signal. */
    private fun reverb(socketId: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket = webSocket
            webSocket.send("""{"event":"pusher:connection_established","data":"{\"socket_id\":\"$socketId\",\"activity_timeout\":30}"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            if (text.contains("pusher:subscribe")) {
                webSocket.send("""{"event":"pusher_internal:subscription_succeeded","channel":"private-floor.1","data":"{}"}""")
                webSocket.send("""{"event":"pusher:ping","data":{}}""")
                webSocket.send("""{"event":"floor.changed","channel":"private-floor.1","data":"{\"order_id\":42}"}""")
            }
        }
    }

    @BeforeTest
    fun start() {
        server.start()
    }

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    private fun socket(signed: MutableList<Pair<String, String>>) = LiveSocket(
        http = { OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build() },
        name = "test",
        authorize = { socketId, channel -> signed += socketId to channel; "key:signature" },
        maxBackoffMillis = 200,
    )

    @Test
    fun it_joins_its_channel_answers_pings_and_hands_over_signals() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(reverb("123.456")).build())
        val signed = mutableListOf<Pair<String, String>>()
        val live = socket(signed)
        val url = LiveSocket.wsUrl(server.url("/").toString(), "/app/key?protocol=7")

        val event: LiveEvent = withTimeout(5_000) {
            live.connect(LiveSocket.Target(url, listOf("private-floor.1")))
            live.events.first()
        }
        assertEquals("floor.changed", event.event)
        assertEquals("floor", event.kind)
        assertEquals("42", event.data["order_id"].toString())
        assertEquals(listOf("123.456" to "private-floor.1"), signed, "the caisse signs the channel for this connection")
        assertTrue(live.connected.value)

        withTimeout(2_000) { while (received.none { it.contains("pusher:pong") }) kotlinx.coroutines.delay(20) }
        val subscribe = received.first { it.contains("pusher:subscribe") }
        assertTrue(subscribe.contains("\"channel\":\"private-floor.1\"") && subscribe.contains("\"auth\":\"key:signature\""), subscribe)
        live.disconnect()
        assertFalse(live.connected.value)
    }

    @Test
    fun it_comes_back_by_itself_when_the_server_drops_it() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(reverb("1.1")).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(reverb("2.2")).build())
        val signed = mutableListOf<Pair<String, String>>()
        val live = socket(signed)
        live.connect(LiveSocket.Target(LiveSocket.wsUrl(server.url("/").toString(), "/app/key"), listOf("private-floor.1")))
        withTimeout(5_000) { while (!live.connected.value) kotlinx.coroutines.delay(20) }

        serverSocket!!.close(1001, "restart")
        withTimeout(5_000) { while (signed.size < 2) kotlinx.coroutines.delay(20) }
        withTimeout(5_000) { while (!live.connected.value) kotlinx.coroutines.delay(20) }
        assertEquals("2.2", signed.last().first, "a new connection, signed again")
        live.disconnect()
    }

    @Test
    fun a_refused_channel_leaves_it_not_connected() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(reverb("9.9")).build())
        val live = LiveSocket(
            http = { OkHttpClient() }, name = "test", authorize = { _, _ -> null }, maxBackoffMillis = 60_000,
        )
        live.connect(LiveSocket.Target(LiveSocket.wsUrl(server.url("/").toString(), "/app/key"), listOf("private-floor.1")))
        kotlinx.coroutines.delay(500)
        assertFalse(live.connected.value)
        assertTrue(received.none { it.contains("pusher:subscribe") })
        live.disconnect()
    }

    @Test
    fun the_websocket_address_follows_the_caisse_address() {
        assertEquals("wss://192.168.1.10/app/k?protocol=7", LiveSocket.wsUrl("https://192.168.1.10/", "/app/k?protocol=7"))
        assertEquals("ws://127.0.0.1:8000/app/k", LiveSocket.wsUrl("http://127.0.0.1:8000/", "app/k"))
    }
}
