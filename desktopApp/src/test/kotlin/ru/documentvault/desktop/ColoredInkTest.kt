package ru.documentvault.desktop

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColoredInkTest {
    @Test fun `suppresses blue ink but keeps black lettering even when blue is nearby`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { g ->
            g.color = Color(160, 160, 160); g.fillRect(0, 0, 200, 100)
            g.color = Color(40, 55, 100); g.fillRect(10, 10, 30, 30); g.fillRect(110, 10, 3, 30)
            g.color = Color(25, 25, 25); g.fillRect(120, 10, 30, 30); g.dispose()
        }
        try {
            assertTrue(ColoredInk.mostlyBlue(image, Rectangle(0, 0, 50, 50)))
            assertFalse(ColoredInk.mostlyBlue(image, Rectangle(100, 0, 60, 50)))
        } finally { image.flush() }
    }
}
