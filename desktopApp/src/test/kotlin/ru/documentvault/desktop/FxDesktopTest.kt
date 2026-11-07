package ru.documentvault.desktop

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.image.WritableImage
import javafx.stage.Stage
import org.junit.jupiter.api.io.TempDir
import ru.documentvault.core.VaultCrypto
import ru.documentvault.server.VaultServer
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FxDesktopTest {
    @TempDir lateinit var temp: Path
    private fun fx(action: () -> Unit) {
        val latch = CountDownLatch(1); val error = AtomicReference<Throwable>()
        Platform.runLater { try { action() } catch (e: Throwable) { error.set(e) } finally { latch.countDown() } }
        check(latch.await(20, TimeUnit.SECONDS)); error.get()?.let { throw it }
    }
    @Test fun `javafx app switches theme and reads encrypted test document`() {
        val server = VaultServer(temp.resolve("server.db")).app(); server.start("127.0.0.1", 0)
        val address = "http://127.0.0.1:${server.port()}"; val api = VaultApi(address)
        val password = "test-login-password"; val recovery = "recovery-password-123"
        api.register("fxuser", password, recovery.toCharArray())
        val token = api.login("fxuser", password); val master = api.unlock(token, recovery.toCharArray()); val id = UUID.randomUUID().toString()
        val pdf = java.io.ByteArrayOutputStream()
        org.apache.pdfbox.pdmodel.PDDocument().use { document -> document.addPage(org.apache.pdfbox.pdmodel.PDPage()); document.addPage(org.apache.pdfbox.pdmodel.PDPage()); document.save(pdf) }
        val payload = DocumentPayload.encode("пример.pdf", pdf.toByteArray())
        try { api.upload(token, id, VaultCrypto.encrypt(master, payload, "document:$id".toByteArray())); api.saveMetadata(token, id, VaultCrypto.encrypt(master, "{\"name\":\"пример.pdf\",\"text\":\"договор\",\"expiry\":\"2030-12-31\"}".toByteArray(), "metadata:$id".toByteArray())) } finally { payload.fill(0) }
        val prefs = Preferences.userRoot().node("/documentvault-fx-test/" + UUID.randomUUID())
        val ready = CountDownLatch(1); try { Platform.startup { ready.countDown() } } catch (_: IllegalStateException) { ready.countDown() }; check(ready.await(20, TimeUnit.SECONDS))
        Platform.setImplicitExit(false)
        lateinit var window: Stage; lateinit var app: VaultFxApp
        fun nodes(): Set<Node> = window.scene.root.lookupAll("*")
        fun button(text: String): Button = nodes().filterIsInstance<Button>().first { it.text == text }
        fun awaitStatus(value: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                var found = false; fx { found = nodes().filterIsInstance<Label>().any { it.text.contains(value) } }
                if (found) return
                Thread.sleep(100)
            }
            error("Status not found: $value")
        }
        try {
            fx {
                window = Stage(); app = VaultFxApp(address, prefs); app.start(window)
                window.scene.root.applyCss(); window.scene.root.layout()
                fun verifyErrorDialog() {
                    val failure = AtomicReference<Throwable>()
                    Platform.runLater {
                        try {
                            val dialog = javafx.stage.Window.getWindows().first { it !== window && it.isShowing && it.scene?.root is javafx.scene.control.DialogPane }
                            val pane = dialog.scene.root as javafx.scene.control.DialogPane; pane.applyCss(); pane.layout()
                            val message = pane.lookupAll(".label").filterIsInstance<Label>().first { it.text.contains("Сначала войдите") }
                            val color = message.textFill as javafx.scene.paint.Color
                            if (prefs.getBoolean("lightTheme", false)) assertTrue(color.red < 0.5, "Light dialog must have dark text") else assertTrue(color.red > 0.7, "Dark dialog must have light text")
                            dialog.hide()
                        } catch (error: Throwable) { failure.set(error); javafx.stage.Window.getWindows().filter { it !== window }.toList().forEach { it.hide() } }
                    }
                    val method = VaultFxApp::class.java.getDeclaredMethod("error", String::class.java); method.isAccessible = true; method.invoke(app, "Сначала войдите в аккаунт.")
                    failure.get()?.let { throw it }
                }
                verifyErrorDialog()
                button("Светлая тема").fire(); assertTrue(prefs.getBoolean("lightTheme", false)); verifyErrorDialog(); button("Тёмная тема").fire()
                val fields = nodes().filterIsInstance<TextField>()
                fields.first { it.accessibleText == "Логин" }.text = "fxuser"
                fields.first { it.accessibleText == "Пароль входа" }.text = password
                button("Войти").fire()
            }
            awaitStatus("Вход выполнен")
            fx {
                val fields = nodes().filterIsInstance<TextField>()
                assertTrue(fields.none { it.accessibleText == "Пароль входа" && it.parent.isVisible })
                fields.first { it.accessibleText == "Пароль восстановления" }.text = recovery
                button("Открыть хранилище").fire()
            }
            awaitStatus("Документы обновлены")
            fx {
                val list = nodes().filterIsInstance<ListView<*>>().first(); assertEquals(listOf(id), list.items.toList())
                val root = window.scene.root; root.applyCss(); root.layout()
                fun capture(name: String) {
                    val image = root.snapshot(SnapshotParameters(), null as WritableImage?); val out = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until out.height) for (x in 0 until out.width) out.setRGB(x, y, image.pixelReader.getArgb(x, y))
                    val dir = temp.resolve("screens").toFile(); dir.mkdirs(); ImageIO.write(out, "png", dir.resolve(name))
                    val repoDir = java.io.File("build/reports/ui"); repoDir.mkdirs(); ImageIO.write(out, "png", repoDir.resolve(name))
                }
                capture("javafx-dark.png"); button("Светлая тема").fire(); root.applyCss(); root.layout(); capture("javafx-light.png")
            }
            fun menuItem(title: String): javafx.scene.control.MenuItem {
                window.scene.root.applyCss(); window.scene.root.layout()
                val cell = nodes().filterIsInstance<javafx.scene.control.ListCell<*>>().first { it.item == id }
                return cell.contextMenu.items.first { it.text == title }
            }
            fx {
                val list = nodes().filterIsInstance<ListView<*>>().first()
                javafx.event.Event.fireEvent(list, javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 40.0, 250.0, 400.0, 400.0, false, null))
                val popup = javafx.stage.Window.getWindows().first { it !== window && it.isShowing && it.scene?.root?.lookupAll(".menu-item")?.isNotEmpty() == true }
                popup.scene.root.applyCss()
                assertTrue(popup.scene.root.lookupAll(".label").filterIsInstance<Label>().any { it.text == "Создать папку" })
                popup.hide()
            }
            fx {
                Platform.runLater {
                    val popup = javafx.stage.Window.getWindows().first { it !== window && it.scene?.root is javafx.scene.control.DialogPane }
                    val pane = popup.scene.root as javafx.scene.control.DialogPane
                    pane.lookupAll(".text-field").filterIsInstance<TextField>().first().text = "Работа"
                    (pane.lookupButton(javafx.scene.control.ButtonType.OK) as Button).fire()
                }
                button("+ Папка").fire()
            }
            awaitStatus("Папка создана")
            assertEquals(2, api.list(token).size)
            val folderRecord = api.list(token).filter { it != id }.single()
            val folderMetadata = VaultCrypto.decrypt(master, api.metadata(token, folderRecord)!!, "metadata:$folderRecord".toByteArray())
            try {
                val meta = com.fasterxml.jackson.databind.ObjectMapper().readTree(folderMetadata)
                assertEquals("documentvault.folder.v1", meta["recordType"].asText()); assertEquals("Работа", meta["folder"].asText())
            } finally { folderMetadata.fill(0) }
            fx {
                assertTrue(nodes().filterIsInstance<Button>().any { it.text == "Папка / Работа" }); button("Все документы").fire()
                val tile = button("ПАПКА   /   Работа"); assertTrue(tile.isVisible && tile.onDragDropped != null && tile.onDragOver != null)
                window.scene.root.applyCss(); window.scene.root.layout()
                val shot = window.scene.root.snapshot(SnapshotParameters(), null as WritableImage?); val out = BufferedImage(shot.width.toInt(), shot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until out.height) for (x in 0 until out.width) out.setRGB(x, y, shot.pixelReader.getArgb(x, y))
                val ui = java.io.File("build/reports/ui"); ui.mkdirs(); ImageIO.write(out, "png", ui.resolve("javafx-folders.png"))
            }
            fx {
                Platform.runLater {
                    val popup = javafx.stage.Window.getWindows().first { it !== window && it.scene?.root is javafx.scene.control.DialogPane }
                    val pane = popup.scene.root as javafx.scene.control.DialogPane
                    @Suppress("UNCHECKED_CAST") val choices = pane.lookupAll(".combo-box").filterIsInstance<javafx.scene.control.ComboBox<Any>>().first()
                    choices.selectionModel.select(choices.items.first { it.toString() == "Работа" })
                    (pane.lookupButton(javafx.scene.control.ButtonType.OK) as Button).fire()
                }
                menuItem("Переместить в папку").fire()
            }
            awaitStatus("Документ перемещён")
            val moved = VaultCrypto.decrypt(master, api.metadata(token, id)!!, "metadata:$id".toByteArray())
            try { assertEquals("Работа", com.fasterxml.jackson.databind.ObjectMapper().readTree(moved)["folder"].asText()) } finally { moved.fill(0) }
            fx { button("Папка / Работа").fire(); val list = nodes().filterIsInstance<ListView<*>>().first(); assertEquals(listOf(id), list.items.toList()); button("Обновить").fire() }
            awaitStatus("Документы обновлены")
            fx {
                assertTrue(nodes().filterIsInstance<Button>().any { it.text == "Папка / Работа" }); button("Все документы").fire()
                val tile = button("ПАПКА   /   Работа"); assertTrue(tile.isVisible && tile.onDragDropped != null && tile.onDragOver != null)
                window.scene.root.applyCss(); window.scene.root.layout()
                val shot = window.scene.root.snapshot(SnapshotParameters(), null as WritableImage?); val out = BufferedImage(shot.width.toInt(), shot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until out.height) for (x in 0 until out.width) out.setRGB(x, y, shot.pixelReader.getArgb(x, y))
                val ui = java.io.File("build/reports/ui"); ui.mkdirs(); ImageIO.write(out, "png", ui.resolve("javafx-folders.png"))
            }

            fx {
                val source = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
                val encoded = java.io.ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
                val method = VaultFxApp::class.java.getDeclaredMethod("renderPreview", ByteArray::class.java, String::class.java, Int::class.javaPrimitiveType); method.isAccessible = true
                val page = method.invoke(app, encoded, "image.png", 0)
                val width = page.javaClass.getDeclaredField("width"); width.isAccessible = true
                val height = page.javaClass.getDeclaredField("height"); height.isAccessible = true
                assertEquals(64, width.getInt(page)); assertEquals(48, height.getInt(page)); source.flush()
            }
            fx {
                val cell = nodes().filterIsInstance<javafx.scene.control.ListCell<*>>().first { it.item == id }
                val list = nodes().filterIsInstance<ListView<*>>().first(); list.selectionModel.clearSelection()
                javafx.event.Event.fireEvent(cell, javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5.0, 5.0, 20.0, 20.0, false, null))
                assertEquals(id, list.selectionModel.selectedItem)
                val menu = cell.contextMenu; menu.show(cell, javafx.geometry.Side.RIGHT, 0.0, 0.0)
                menu.scene.root.applyCss(); menu.scene.root.layout()
                val menuText = menu.scene.root.lookupAll(".label").filterIsInstance<Label>().first { it.text == "Открыть документ" }
                val menuColor = menuText.textFill as javafx.scene.paint.Color
                assertTrue(menuColor.red < 0.4, "Light menu needs dark text: $menuColor")
                val menuRegions = menu.scene.root.lookupAll("*").filterIsInstance<javafx.scene.layout.Region>().filter { it.background != null && it.background.fills.isNotEmpty() }; val menuBackground = menuRegions.first().background.fills.first().fill as javafx.scene.paint.Color
                assertTrue(menuBackground.red > 0.8, "Light menu needs light background: $menuBackground")
                menu.hide(); button("Тёмная тема").fire(); menu.show(cell, javafx.geometry.Side.RIGHT, 0.0, 0.0); menu.scene.root.applyCss()
                val darkText = menu.scene.root.lookupAll(".label").filterIsInstance<Label>().first { it.text == "Открыть документ" }.textFill as javafx.scene.paint.Color
                val darkBackground = menu.scene.root.lookupAll("*").filterIsInstance<javafx.scene.layout.Region>().first { it.background != null && it.background.fills.isNotEmpty() }.background.fills.first().fill as javafx.scene.paint.Color
                assertTrue(darkText.red < 0.4 && darkBackground.red > 0.8, "Menu must be legible in dark theme: $darkText on $darkBackground")
                menu.hide()
                assertTrue(listOf("Открыть документ", "Просмотр с текстом OCR", "Переименовать", "Редактировать текст OCR", "Удалить").all { title -> menu.items.any { it.text == title } })
                menuItem("Открыть документ").fire()
            }
            awaitStatus("Документ открыт")
            fx {
                val preview = javafx.stage.Window.getWindows().filterIsInstance<Stage>().first { it !== window && it.title == "пример.pdf" }
                assertTrue(preview.scene.root.lookupAll("*").filterIsInstance<javafx.scene.image.ImageView>().any { it.image != null })
                assertTrue(preview.scene.root.lookupAll("*").filterIsInstance<Label>().any { it.text == "Страница 1 из 2" })
                preview.scene.root.lookupAll("*").filterIsInstance<Button>().first { it.text == "Далее" }.fire()
            }
            awaitStatus("Документ открыт")
            fx {
                val preview = javafx.stage.Window.getWindows().filterIsInstance<Stage>().first { it !== window && it.title == "пример.pdf" }
                assertTrue(preview.scene.root.lookupAll("*").filterIsInstance<Label>().any { it.text == "Страница 2 из 2" })
                preview.close()
            }
            fx {
                Platform.runLater {
                    val popup = javafx.stage.Window.getWindows().first { it !== window && it.scene?.root is javafx.scene.control.DialogPane }
                    val pane = popup.scene.root as javafx.scene.control.DialogPane
                    pane.lookupAll(".text-field").filterIsInstance<TextField>().first().text = "новый"
                    (pane.lookupButton(javafx.scene.control.ButtonType.OK) as Button).fire()
                }
                menuItem("Переименовать").fire()
            }
            awaitStatus("Название сохранено")
            val renamed = VaultCrypto.decrypt(master, api.metadata(token, id)!!, "metadata:$id".toByteArray())
            try { assertEquals("новый.pdf", com.fasterxml.jackson.databind.ObjectMapper().readTree(renamed)["name"].asText()) } finally { renamed.fill(0) }
            fx {
                Platform.runLater {
                    val popup = javafx.stage.Window.getWindows().first { it !== window && it.scene?.root is javafx.scene.control.DialogPane }
                    val pane = popup.scene.root as javafx.scene.control.DialogPane
                    pane.lookupAll(".text-area").filterIsInstance<javafx.scene.control.TextArea>().first().text = "исправленный текст"
                    (pane.lookupButton(javafx.scene.control.ButtonType.OK) as Button).fire()
                }
                menuItem("Редактировать текст OCR").fire()
            }
            awaitStatus("Текст OCR сохранён")
            val edited = VaultCrypto.decrypt(master, api.metadata(token, id)!!, "metadata:$id".toByteArray())
            try { assertEquals("исправленный текст", com.fasterxml.jackson.databind.ObjectMapper().readTree(edited)["text"].asText()) } finally { edited.fill(0) }
            fx {
                Platform.runLater {
                    val popup = javafx.stage.Window.getWindows().first { it !== window && it.scene?.root is javafx.scene.control.DialogPane }
                    val pane = popup.scene.root as javafx.scene.control.DialogPane
                    (pane.lookupButton(javafx.scene.control.ButtonType.CANCEL) as Button).fire()
                }
                menuItem("Удалить").fire()
            }
            assertTrue(api.list(token).contains(id))
            fx {
                Platform.runLater {
                    val popup = javafx.stage.Window.getWindows().first { it !== window && it.scene?.root is javafx.scene.control.DialogPane }
                    val pane = popup.scene.root as javafx.scene.control.DialogPane
                    (pane.lookupButton(javafx.scene.control.ButtonType.OK) as Button).fire()
                }
                menuItem("Удалить").fire()
            }
            awaitStatus("Документ удалён")
            assertTrue(!api.list(token).contains(id))
            val imageId = UUID.randomUUID().toString()
            val image = BufferedImage(1200, 1000, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().also { it.color = java.awt.Color.WHITE; it.fillRect(0, 0, image.width, image.height); it.color = java.awt.Color.BLACK; it.font = java.awt.Font("Arial", java.awt.Font.PLAIN, 100); it.drawString("DOCUMENT", 120, 300); it.dispose() }
            val imageBytes = java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray(); image.flush()
            val imagePayload = DocumentPayload.encode("ocr.png", imageBytes); imageBytes.fill(0)
            try { api.upload(token, imageId, VaultCrypto.encrypt(master, imagePayload, "document:$imageId".toByteArray())) } finally { imagePayload.fill(0) }
            val imageMeta = """{"name":"ocr.png","text":"DOCUMENT","ocrLayout":{"version":1,"words":[["DOCUMENT",0.1,0.2,0.7,0.32]]}}""".toByteArray()
            api.saveMetadata(token, imageId, VaultCrypto.encrypt(master, imageMeta, "metadata:$imageId".toByteArray())); imageMeta.fill(0)
            fx { button("Обновить").fire() }; awaitStatus("Документы обновлены")
            fx {
                window.scene.root.applyCss(); window.scene.root.layout()
                val cell = nodes().filterIsInstance<javafx.scene.control.ListCell<*>>().first { it.item == imageId }
                cell.contextMenu.items.first { it.text == "Просмотр с текстом OCR" }.fire()
                val active = VaultFxApp::class.java.getDeclaredField("activeOcrId"); active.isAccessible = true; assertEquals(imageId, active.get(app))
                window.scene.root.applyCss(); window.scene.root.layout(); assertTrue(nodes().filterIsInstance<javafx.scene.control.ProgressIndicator>().any { it.accessibleText == "Распознавание документа" })
            }
            awaitStatus("Документ открыт")
            fx {
                val preview = javafx.stage.Window.getWindows().filterIsInstance<Stage>().first { it !== window && it.title == "ocr.png" }
                val imageView = preview.scene.root.lookupAll("*").filterIsInstance<javafx.scene.image.ImageView>().first()
                val scroll = preview.scene.root.lookupAll("*").filterIsInstance<javafx.scene.control.ScrollPane>().first()
                val recognized = preview.scene.root.lookupAll("*").filterIsInstance<javafx.scene.control.TextArea>().first()
                assertEquals("DOCUMENT", recognized.text); assertTrue(!recognized.isEditable); recognized.selectRange(0, 3); assertEquals("DOC", recognized.selectedText)
                preview.scene.root.applyCss(); preview.scene.root.layout()
                val screen = preview.scene.root.snapshot(SnapshotParameters(), null as WritableImage?)
                val screenshot = BufferedImage(screen.width.toInt(), screen.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until screenshot.height) for (x in 0 until screenshot.width) screenshot.setRGB(x, y, screen.pixelReader.getArgb(x, y))
                val uiDir = java.io.File("build/reports/ui"); uiDir.mkdirs(); ImageIO.write(screenshot, "png", uiDir.resolve("javafx-ocr-copy.png"))
                assertEquals(javafx.scene.paint.Color.rgb(224, 226, 225), imageView.image.pixelReader.getColor(121, 201))
                val before = imageView.fitWidth
                fun wheel(delta: Double) = javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL, 300.0, 300.0, 300.0, 300.0, false, false, false, false, false, false, 0.0, delta, 0.0, delta, javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0, javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0.0, 0, null)
                repeat(9) { javafx.event.Event.fireEvent(scroll, wheel(120.0)) }
                assertTrue(imageView.fitWidth > before, "Wheel should increase zoom")
                scroll.hvalue = 0.5; scroll.vvalue = 0.5
                fun mouse(type: javafx.event.EventType<javafx.scene.input.MouseEvent>, x: Double, y: Double) = javafx.scene.input.MouseEvent(type, x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null)
                javafx.event.Event.fireEvent(imageView, mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, 300.0, 300.0))
                javafx.event.Event.fireEvent(imageView, mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, 220.0, 220.0))
                assertTrue(scroll.hvalue > 0.5 && scroll.vvalue > 0.5, "Dragging should pan zoomed image")
                javafx.event.Event.fireEvent(imageView, mouse(javafx.scene.input.MouseEvent.MOUSE_RELEASED, 220.0, 220.0))
                preview.close()
            }

            fx {
                window.scene.root.applyCss(); window.scene.root.layout()
                val cell = nodes().filterIsInstance<javafx.scene.control.ListCell<*>>().first { it.item == imageId }
                cell.contextMenu.items.first { it.text == "Распознать заново" }.fire()
                val active = VaultFxApp::class.java.getDeclaredField("activeOcrId"); active.isAccessible = true; assertEquals(imageId, active.get(app))
                window.scene.root.applyCss(); window.scene.root.layout(); assertTrue(nodes().filterIsInstance<javafx.scene.control.ProgressIndicator>().any { it.accessibleText == "Распознавание документа" })
            }
            awaitStatus("Текст сохранён")
        } finally { fx { app.stop(); window.close() }; master.fill(0); prefs.removeNode(); server.stop() }
    }
}
