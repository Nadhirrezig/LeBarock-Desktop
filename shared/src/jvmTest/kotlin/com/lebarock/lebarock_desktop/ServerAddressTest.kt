package com.lebarock.lebarock_desktop

import com.lebarock.lebarock_desktop.data.settings.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerAddressTest {

    @Test
    fun the_default_port_is_left_out_of_the_url() {
        assertEquals("https://192.168.1.20/", ServerAddress(true, "192.168.1.20", 443).baseUrl)
        assertEquals("https://192.168.1.20:8443/", ServerAddress(true, "192.168.1.20", 8443).baseUrl)
        assertEquals("http://192.168.1.20/", ServerAddress(false, "192.168.1.20", 80).baseUrl)
        assertEquals("http://192.168.1.20:8000/", ServerAddress(false, "192.168.1.20", 8000).baseUrl)
        assertEquals("", ServerAddress(true, "", 443).baseUrl)
    }

    @Test
    fun a_pasted_url_fills_protocol_host_and_port() {
        assertEquals(ServerAddress(true, "192.168.1.20", 8443), ServerAddress.parsePasted("https://192.168.1.20:8443/api/mobile"))
        assertEquals(ServerAddress(true, "caisse.local", 443), ServerAddress.parsePasted("HTTPS://caisse.local/"))
        assertEquals(ServerAddress(false, "10.0.0.5", 80), ServerAddress.parsePasted("http://10.0.0.5"))
        assertNull(ServerAddress.parsePasted("192.168.1.20"))
    }

    @Test
    fun wrong_addresses_and_ports_are_explained() {
        assertNull(ServerAddress.hostError("192.168.1.20"))
        assertNull(ServerAddress.hostError("caisse-lebaroque.local"))
        assertNotNull(ServerAddress.hostError(""))
        assertNotNull(ServerAddress.hostError("192.168.1.300"))
        assertNotNull(ServerAddress.hostError("192.168.1.20/api"))
        assertNotNull(ServerAddress.hostError("192.168 .1.20"))
        assertNull(ServerAddress.portError("443"))
        assertNotNull(ServerAddress.portError("0"))
        assertNotNull(ServerAddress.portError("70000"))
        assertNotNull(ServerAddress.portError(""))
    }

    @Test
    fun the_label_names_the_protocol() {
        assertEquals("192.168.1.20:443 (HTTPS)", ServerAddress(true, "192.168.1.20", 443).label)
        assertEquals("—", ServerAddress().label)
    }
}
