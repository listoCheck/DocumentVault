package ru.documentvault.desktop

import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopLayoutTest {
    @Test fun `main actions stay readable and layout uses the dark palette`() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeAndWait {
            val prefs = java.util.prefs.Preferences.userRoot().node("/documentvault-test/" + java.util.UUID.randomUUID())
            val window = VaultWindow(prefs)
            try {
                window.setSize(1160, 800); window.isVisible = true; window.validate()
                fun descendants(component: Component): List<Component> = listOf(component) +
                    if (component is Container) component.components.flatMap(::descendants) else emptyList()
                val components = descendants(window.contentPane)
                val login = components.filterIsInstance<JButton>().firstOrNull { it.text == "Войти" }
                val upload = components.filterIsInstance<JButton>().firstOrNull { it.text == "+ Добавить файл" }
                assertNotNull(login); assertNotNull(upload)
                assertTrue(login.background.green > 200 && login.foreground.green < 80, "Login button must have dark text on acid background")
                assertTrue(upload.background.green > 200 && upload.foreground.green < 80, "Upload button must have dark text on acid background")
                val brand = components.filterIsInstance<JLabel>().first { it.text == "DOCUMENT VAULT" }
                assertTrue(brand.foreground.red > 200, "Brand should remain readable on a dark header")
                val title = components.filterIsInstance<JLabel>().first { it.text == "Документы" }
                val panel = components.filterIsInstance<JLabel>().first { it.text == "ВАШЕ ПРОСТРАНСТВО / 01" }
                assertTrue(kotlin.math.abs(title.x - panel.x) < 3, "Headings should share a left edge")
                assertTrue(window.contentPane.background.red < 40, "Main background should be dark")
                val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().also { window.paintAll(it); it.dispose() }
                File("build/reports/ui").mkdirs(); ImageIO.write(image, "png", File("build/reports/ui/desktop-dark.png"))
                components.filterIsInstance<JButton>().first { it.text == "Светлая тема" }.doClick()
                assertTrue(window.contentPane.background.red > 230, "Light theme must have a light background")
                assertTrue(login.foreground.green < 80 && login.background.green > 200, "Primary button must stay readable")
                assertTrue(prefs.getBoolean("lightTheme", false), "Theme choice should be saved")
                window.validate(); image.createGraphics().also { window.paintAll(it); it.dispose() }
                ImageIO.write(image, "png", File("build/reports/ui/desktop-light.png"))
                val restored = VaultWindow(prefs)
                try { assertTrue(restored.contentPane.background.red > 230, "Saved theme should be restored") } finally { restored.dispose() }
                components.filterIsInstance<JButton>().first { it.text == "Тёмная тема" }.doClick()
                assertTrue(window.contentPane.background.red < 40 && !prefs.getBoolean("lightTheme", true), "Dark theme should be restored and saved")
            } finally { window.dispose(); prefs.removeNode() }
        }
    }
}
