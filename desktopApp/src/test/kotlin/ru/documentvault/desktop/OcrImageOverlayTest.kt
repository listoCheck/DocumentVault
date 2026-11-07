package ru.documentvault.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrImageOverlayTest {
    private val json = ObjectMapper()
    @Test fun `renders gray cover and fitted text in supplied word bounds`() {
        val source = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().also { it.color = Color.WHITE; it.fillRect(0, 0, 400, 200); it.color = Color.BLACK; it.fillRect(40, 40, 240, 50); it.dispose() }
        val layout = json.readTree("""{"version":1,"words":[["DOCUMENT",0.1,0.2,0.7,0.45],["bad",-1,0,1,1]]}""")
        val out = OcrImageOverlay.render(source, layout)
        try {
            assertEquals(Color(224, 226, 225).rgb, out.getRGB(38, 38))
            assertEquals(Color.WHITE.rgb, out.getRGB(5, 5))
            assertTrue((40 until 280).any { x -> (40 until 90).any { y -> (out.getRGB(x, y) and 0x00ffffff) < 0x00303030 } })
        } finally { source.flush(); out.flush() }
    }
    @Test fun `covers uncertain words and matches glyph height to the original region`() {
        val source = BufferedImage(500, 220, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().also { it.color = Color.WHITE; it.fillRect(0, 0, 500, 220); it.color = Color.BLACK; it.fillRect(20, 40, 180, 50); it.fillRect(260, 140, 100, 30); it.dispose() }
        val layout = json.readTree("""{"version":1,"words":[["DOCUMENT",0.04,0.18,0.40,0.41],["",0.52,0.64,0.72,0.78]]}""")
        val output = OcrImageOverlay.render(source, layout)
        try {
            assertEquals(Color(224, 226, 225).rgb, output.getRGB(280, 150))
            val rows = (39 until 90).filter { y -> (20 until 200).any { x -> (output.getRGB(x, y) and 0xffffff) < 0x303030 } }
            assertTrue(rows.isNotEmpty() && rows.last() - rows.first() >= 38, "Text height should closely match its 50-pixel region: $rows")
        } finally { source.flush(); output.flush() }
    }
    @Test fun `rectifies perspective using android corner coordinates`() {
        val source = BufferedImage(300, 250, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().also { it.color = Color.WHITE; it.fillRect(0, 0, 300, 250); it.dispose() }
        val layout = json.readTree("""{"version":1,"corners":[0.1,0.1,0.9,0.15,0.8,0.9,0.2,0.85],"words":[["WORD",0.1,0.2,0.5,0.4]]}""")
        val out = OcrImageOverlay.render(source, layout)
        try { assertTrue(out.width in 200..245); assertTrue(out.height in 180..230); assertEquals(Color(224,226,225).rgb, out.getRGB((out.width * 0.11).toInt(), (out.height * 0.21).toInt())) }
        finally { source.flush(); out.flush() }
    }
    @Test fun `rejects unsupported layout`() {
        val source = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        try { kotlin.test.assertFailsWith<IllegalArgumentException> { OcrImageOverlay.render(source, json.readTree("""{"version":0,"words":[]}""")) } } finally { source.flush() }
    }
}
