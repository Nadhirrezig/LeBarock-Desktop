package com.lebarock.lebarock_desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import com.lebarock.lebarock_desktop.ui.pos.ItemImages
import com.lebarock.lebarock_desktop.ui.pos.ItemImages.Source
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The POS cards' photos (client, 2026-11-08: "like the web, a photo on each article and the food icon for those
 * with none"): the caisse's photo URL put on the server of Réglages, the web's food icon drawn from the app, a
 * photo that does not come falling back to it.
 */
class ItemImagesTest {

    private val server = MockWebServer().also { it.start() }
    private val base get() = "http://${server.hostName}:${server.port}/"
    private val photo = ColorPainter(Color.Red)
    private val food = ColorPainter(Color.Gray)
    private var clock = 1_000_000L
    private val decoded = mutableListOf<Boolean>()
    private val images = ItemImages(
        http = { OkHttpClient() },
        baseUrl = { base },
        decode = { _, svg -> decoded += svg; photo },
        placeholderOf = { food },
        now = { clock },
    )

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    @Test
    fun the_caisse_url_goes_to_the_server_of_reglages() {
        assertEquals(
            Source.Remote("https://192.168.1.20/user-uploads/item/magon.jpg?v=2"),
            ItemImages.resolve("http://localhost/user-uploads/item/magon.jpg?v=2", "https://192.168.1.20/"),
        )
        assertEquals(Source.Placeholder, ItemImages.resolve("http://localhost/img/food.svg", "https://192.168.1.20"), "the web's own food icon is bundled")
        assertEquals(Source.Placeholder, ItemImages.resolve(null, "https://192.168.1.20"))
        assertEquals(Source.Placeholder, ItemImages.resolve("  ", "https://192.168.1.20"))
        assertEquals(Source.Remote("http://10.0.0.5:8080/user-uploads/item/a.png"), ItemImages.resolve("user-uploads/item/a.png", "http://10.0.0.5:8080"))
    }

    @Test
    fun a_photo_is_fetched_once_then_kept() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("JPEGDATA").build())
        val url = "http://localhost/user-uploads/item/magon.jpg"

        assertSame(photo, images.load(url))
        assertSame(photo, images.load(url), "the second card takes it from memory")
        assertSame(photo, images.cached(url))
        assertEquals(1, server.requestCount)
        assertEquals("/user-uploads/item/magon.jpg", server.takeRequest().url.encodedPath)
        assertEquals(listOf(false), decoded)
    }

    @Test
    fun an_item_without_photo_shows_the_food_icon_without_a_call() = runBlocking<Unit> {
        assertSame(food, images.load("http://localhost/img/food.svg"))
        assertSame(food, images.cached(null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun a_photo_that_does_not_come_falls_back_and_is_tried_again_later() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(404).build())
        val url = "http://localhost/user-uploads/item/gone.jpg"

        assertSame(food, images.load(url))
        assertSame(food, images.load(url), "not asked again at once")
        assertEquals(1, server.requestCount)

        clock += 61_000
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/svg+xml").body("<svg/>").build())
        assertSame(photo, images.load(url), "asked again after a minute")
        assertTrue(decoded.single(), "an SVG photo is read as SVG")
    }

    @Test
    fun the_type_badge_exists_only_for_the_web_icons() {
        assertEquals(null, ItemImages.typeIcon("other"), "the web has no icon for « other »")
        assertEquals(null, ItemImages.typeIcon(null))
    }
}
