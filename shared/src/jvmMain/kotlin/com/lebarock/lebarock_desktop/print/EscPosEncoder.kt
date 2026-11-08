package com.lebarock.lebarock_desktop.print

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * How accented letters reach the paper. Thermal printers know a few code pages; the counter
 * printer is chosen in Réglages and "Imprimer un test" shows which one prints é / è / à right.
 */
@Serializable
enum class TicketCharset(val label: String, val hint: String) {
    /** Epson and most ESC/POS printers: page 19 = PC858 (Latin-1 + €). */
    @SerialName("cp858")
    CP858("Européen (PC858)", "Epson et la plupart des imprimantes thermiques"),

    /** Page 16 = WPC1252. */
    @SerialName("cp1252")
    CP1252("Windows-1252", "Si les accents sortent mal en PC858"),

    /** Letters without their accents: always readable. */
    @SerialName("ascii")
    ASCII("Sans accents", "« é » s'imprime « e »"),
}

/**
 * Turns [PrintOp]s into ESC/POS bytes (ESC @, code page, alignment, bold, double size, feed,
 * cut, QR). The same bytes go to a USB printer (Windows spooler, raw) or a network one (port 9100).
 */
class EscPosEncoder(private val charset: TicketCharset = TicketCharset.CP858) {

    private val encoder: CharsetEncoder? = when (charset) {
        TicketCharset.CP858 -> charsetOrNull("IBM00858")
        TicketCharset.CP1252 -> charsetOrNull("windows-1252")
        TicketCharset.ASCII -> charsetOrNull("US-ASCII")
    }?.newEncoder()?.onUnmappableCharacter(CodingErrorAction.REPORT)?.onMalformedInput(CodingErrorAction.REPORT)

    fun encode(ops: List<PrintOp>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)
        codePage()?.let { out.write(byteArrayOf(ESC, 't'.code.toByte(), it)) }
        var align = Align.LEFT
        var bold = false
        var big = false
        out.write(alignBytes(align))
        ops.forEach { op ->
            when (op) {
                is PrintOp.Text -> {
                    if (op.align != align) { align = op.align; out.write(alignBytes(align)) }
                    if (op.bold != bold) { bold = op.bold; out.write(byteArrayOf(ESC, 'E'.code.toByte(), if (bold) 1 else 0)) }
                    if (op.big != big) { big = op.big; out.write(byteArrayOf(GS, '!'.code.toByte(), if (big) 0x11 else 0x00)) }
                    out.write(text(op.text))
                    out.write('\n'.code)
                }
                is PrintOp.Feed -> out.write(byteArrayOf(ESC, 'd'.code.toByte(), op.lines.coerceIn(0, 255).toByte()))
                PrintOp.Cut -> out.write(CUT)
                is PrintOp.Qr -> {
                    if (align != Align.CENTER) { align = Align.CENTER; out.write(alignBytes(align)) }
                    out.write(qr(op.data))
                    out.write('\n'.code)
                }
            }
        }
        // Leave the printer as a next ticket expects it.
        if (bold) out.write(byteArrayOf(ESC, 'E'.code.toByte(), 0))
        if (big) out.write(byteArrayOf(GS, '!'.code.toByte(), 0))
        return out.toByteArray()
    }

    /** The bytes of one line of text in the chosen code page. */
    fun text(value: String): ByteArray {
        val cleaned = typographic(value)
        val enc = encoder ?: return ascii(cleaned).toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        cleaned.forEach { c ->
            val bytes = encodeChar(enc, c) ?: encodeChar(enc, stripAccent(c)) ?: byteArrayOf('?'.code.toByte())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun encodeChar(enc: CharsetEncoder, c: Char): ByteArray? = try {
        enc.reset()
        val buf: ByteBuffer = enc.encode(CharBuffer.wrap(charArrayOf(c)))
        ByteArray(buf.remaining()).also { buf.get(it) }
    } catch (_: Exception) {
        null
    }

    private fun codePage(): Byte? = when (charset) {
        TicketCharset.CP858 -> 19
        TicketCharset.CP1252 -> 16
        TicketCharset.ASCII -> null
    }

    private fun alignBytes(align: Align) = byteArrayOf(ESC, 'a'.code.toByte(), when (align) {
        Align.LEFT -> 0
        Align.CENTER -> 1
        Align.RIGHT -> 2
    })

    /** QR code (GS ( k): model 2, size 6, correction M. */
    private fun qr(data: String): ByteArray {
        val payload = data.toByteArray(Charsets.UTF_8)
        val len = payload.size + 3
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 4, 0, 49, 65, 50, 0))
        out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 67, 6))
        out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 69, 49))
        out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), (len % 256).toByte(), (len / 256).toByte(), 49, 80, 48))
        out.write(payload)
        out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte(), 3, 0, 49, 81, 48))
        return out.toByteArray()
    }

    companion object {
        private const val ESC: Byte = 0x1B
        private const val GS: Byte = 0x1D
        val INIT = byteArrayOf(ESC, '@'.code.toByte())

        /** Feed to the cutter and cut (partial cut, GS V 66 0). */
        val CUT = byteArrayOf(GS, 'V'.code.toByte(), 66, 0)

        private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

        /** Characters thermal code pages lack, written the way a ticket can show them. */
        fun typographic(value: String): String = buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    ' ', ' ', ' ', ' ' -> append(' ')
                    '—', '–', '−' -> append('-')
                    '·', '•' -> append(if (c == '•') '*' else '.')
                    '«', '»', '“', '”', '„' -> append('"')
                    '‘', '’', '‚' -> append('\'')
                    '…' -> append("...")
                    '→' -> append("->")
                    '\t' -> append(' ')
                    else -> if (c.code >= 0x20 || c == '\n') append(c)
                }
            }
        }

        fun stripAccent(c: Char): Char =
            Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull { it.code < 0x300 || it.code > 0x36F } ?: c

        fun ascii(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .map { if (it.code in 0x20..0x7E || it == '\n') it else if (it == '€') 'E' else '?' }.joinToString("")
    }
}
