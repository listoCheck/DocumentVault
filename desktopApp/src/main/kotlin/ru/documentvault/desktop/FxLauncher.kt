package ru.documentvault.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.FileChooser
import javafx.stage.Stage
import ru.documentvault.core.DocumentDates
import ru.documentvault.core.OcrSearch
import ru.documentvault.core.VaultCrypto
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.prefs.Preferences

private val SERVER = System.getenv("VAULT_SERVER_URL") ?: "https://95.174.95.143"
private val mapper = ObjectMapper()
private const val FILE_LIMIT = 15L * 1024 * 1024

/** JavaFX shell over the encrypted API and local OCR. */
class VaultFxApp(private val server: String = SERVER, private val prefs: Preferences = Preferences.userNodeForPackage(VaultWindow::class.java)) : Application() {
    private val api = VaultApi(server)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vault-worker").apply { isDaemon = true } }
    private var token: String? = null
    private var identity: String? = null
    private var key: ByteArray? = null
    private var busy = false
    private var reminderDay: LocalDate? = null
    private var light = prefs.getBoolean("lightTheme", false)
    private val documents = FXCollections.observableArrayList<String>()
    private val names = mutableMapOf<String, String>()
    private val texts = mutableMapOf<String, String>()
    private val dates = mutableMapOf<String, LocalDate>()
    private val folders = mutableMapOf<String, String>()
    private val savedFolders = sortedSetOf<String>()
    // null is the explicit all-documents view; the empty string is the root folder.
    private var selectedFolder: String? = ""
    private val folderBar = HBox(8.0)
    private val folderCards = FlowPane(8.0, 8.0)
    private val documentDragFormat = (javafx.scene.input.DataFormat.lookupMimeType("application/x-documentvault-document") ?: javafx.scene.input.DataFormat("application/x-documentvault-document"))
    private var activeOcrId: String? = null
    private val knownServerIds = mutableSetOf<String>()
    private var syncChecking = false
    private var syncTimer: javafx.animation.Timeline? = null
    private lateinit var stage: Stage
    private lateinit var root: BorderPane
    private val username = TextField()
    private val password = PasswordField()
    private val recoveryInput = PasswordField()
    private lateinit var authPane: VBox
    private lateinit var unlockPane: VBox
    private lateinit var openPane: VBox
    private lateinit var documentArea: VBox
    private val previewWindows = mutableSetOf<Stage>()
    private val search = TextField()
    private val status = Label("Войдите в аккаунт, чтобы открыть хранилище.")
    private val list = ListView<String>(documents)
    private val count = Label("0 документов")
    private lateinit var themeButton: Button
    private val actionButtons = mutableListOf<Button>()

    override fun start(stage: Stage) {
        this.stage = stage
        stage.title = "DocumentVault"
        root = BorderPane().apply { styleClass.add("app"); padding = Insets(24.0, 28.0, 20.0, 28.0) }
        val logo = Label("DV / ").apply { styleClass.add("logo") }
        val brand = Label("DOCUMENT VAULT").apply { styleClass.add("brand") }
        val caption = Label("PRIVATE SPACE").apply { styleClass.add("eyebrow") }
        val spacer = Region(); HBox.setHgrow(spacer, Priority.ALWAYS)
        themeButton = button("", false) { light = !light; prefs.putBoolean("lightTheme", light); applyTheme() }
        val header = HBox(10.0, logo, brand, caption, spacer, themeButton).apply { alignment = Pos.CENTER_LEFT; padding = Insets(0.0, 0.0, 22.0, 0.0) }
        root.top = header

        username.promptText = "Ваш логин"; username.accessibleText = "Логин"
        password.promptText = "Пароль входа"; password.accessibleText = "Пароль входа"; password.setOnAction { login() }
        recoveryInput.promptText = "Пароль восстановления"; recoveryInput.accessibleText = "Пароль восстановления"; recoveryInput.setOnAction { unlock() }
        authPane = VBox(10.0, eyebrow("01 / ВХОД"), label("Логин"), username, label("Пароль входа"), password,
            button("Войти", true, ::login), button("Создать аккаунт", false, ::register))
        unlockPane = VBox(10.0, eyebrow("02 / РАЗБЛОКИРОВКА"), label("Пароль восстановления"), recoveryInput,
            button("Открыть хранилище", true, ::unlock), button("Выйти из аккаунта", false, ::logout))
        openPane = VBox(10.0, eyebrow("ДОСТУП ОТКРЫТ"), label("Хранилище разблокировано"),
            button("Заблокировать", false, ::lock), button("Выйти из аккаунта", false, ::logout))
        val sidebar = VBox(10.0, authPane, unlockPane, openPane,
            Region().apply { VBox.setVgrow(this, Priority.ALWAYS) }, Label("AES-256 / ЛОКАЛЬНОЕ ШИФРОВАНИЕ").apply { styleClass.add("tiny") }).apply {
            styleClass.add("card"); prefWidth = 260.0; minWidth = 230.0
        }
        val title = Label("Документы").apply { styleClass.add("title") }
        val subtitle = Label("Всё важное — в одном защищённом месте.").apply { styleClass.add("muted") }
        search.promptText = "Поиск по названию и тексту"; search.accessibleText = "Поиск по документам"
        search.textProperty().addListener { _, _, _ -> filter() }
        HBox.setHgrow(search, Priority.ALWAYS)
        val tools = HBox(8.0, search, button("Обновить", false, ::refresh), button("+ Добавить файл", true, ::upload)).apply { alignment = Pos.CENTER_LEFT }
        list.placeholder = Label("Здесь появятся ваши документы.\nВойдите и разблокируйте хранилище.").apply { styleClass.add("muted"); alignment = Pos.CENTER }
        list.setCellFactory {
            object : ListCell<String>() {
                private val menu = ContextMenu().apply {
                    styleClass.add("vault-context-menu"); scene.stylesheets.add(VaultFxApp::class.java.getResource("/vault-fx.css")!!.toExternalForm())
                    fun item(title: String, action: () -> Unit) = MenuItem(title).apply { setOnAction { if (!isEmpty && !busy) { list.selectionModel.select(index); action() } } }
                    items.addAll(item("Открыть документ", ::preview), item("Просмотр с текстом OCR", ::previewWithOcr), SeparatorMenuItem(), item("Переименовать", ::renameDocument), item("Редактировать текст OCR", ::editOcr),
                        item("Переместить в папку", ::chooseFolder), item("Изменить срок", ::editExpiry), item("Распознать заново", ::analyze), item("Сохранить файл", ::download), SeparatorMenuItem(), item("Удалить", ::confirmDelete))
                }
                init {
                    setOnDragDetected { event -> if (!isEmpty && !busy && key != null) {
                        list.selectionModel.select(index); val content = javafx.scene.input.ClipboardContent(); content[documentDragFormat] = item
                        startDragAndDrop(javafx.scene.input.TransferMode.MOVE).setContent(content); event.consume()
                    } }
                    setOnContextMenuRequested { if (!isEmpty) list.selectionModel.select(index) }
                    setOnMouseClicked { event -> if (!isEmpty && event.button == javafx.scene.input.MouseButton.PRIMARY && event.clickCount == 2 && !busy) { list.selectionModel.select(index); preview() } }
                }
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else (names[item] ?: item) + (dates[item]?.let { "     ·     до $it" } ?: "")
                    contextMenu = if (empty || item == null) null else menu
                    graphic = when {
                        empty || item == null -> null
                        item == activeOcrId -> ProgressIndicator().apply { prefWidth = 20.0; prefHeight = 20.0; accessibleText = "Распознавание документа" }
                        else -> javafx.scene.shape.SVGPath().apply { content = "M4 1 H14 L20 7 V25 H4 Z M14 1 V7 H20 M8 13 H16 M8 17 H16 M8 21 H14"; styleClass.add("file-icon") }
                    }
                }
            }
        }
        val emptyMenu = ContextMenu().apply {
            styleClass.add("vault-context-menu"); scene.stylesheets.add(VaultFxApp::class.java.getResource("/vault-fx.css")!!.toExternalForm())
            items.add(MenuItem("Создать папку").apply { setOnAction { createFolderDialog() } })
        }
        list.addEventHandler(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED) { event ->
            var node = event.target as? javafx.scene.Node; var fileCell = false
            while (node != null && node !== list) { if (node is ListCell<*> && !node.isEmpty) { fileCell = true; break }; node = node.parent }
            if (!fileCell && key != null && !busy) { emptyMenu.show(list, event.screenX, event.screenY); event.consume() }
        }
        folderBar.alignment = Pos.CENTER_LEFT
        val folderScroll = ScrollPane(folderBar).apply { isFitToHeight = true; hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED; vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER; styleClass.add("folder-scroll"); maxHeight = 56.0 }
        renderFolders()
        VBox.setVgrow(list, Priority.ALWAYS)
        count.styleClass.add("muted")
        val documentHeader = HBox(eyebrow("МОИ ДОКУМЕНТЫ"), Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, count).apply { alignment = Pos.CENTER_LEFT }
        val documentCard = VBox(10.0, documentHeader, folderCards, list).apply { styleClass.add("card"); VBox.setVgrow(this, Priority.ALWAYS) }
        val actions = FlowPane(8.0, 8.0).apply {
            children.addAll(button("Сохранить файл", false, ::download), button("Распознать текст", false, ::analyze),
                button("Сроки", false, ::showExpiries), button("Изменить срок", false, ::editExpiry), button("Фоновые уведомления", false, ::installReminders))
        }
        val main = VBox(12.0, eyebrow("ВАШЕ ПРОСТРАНСТВО / 01"), title, subtitle, tools, folderScroll, documentCard, actions).apply { HBox.setHgrow(this, Priority.ALWAYS) }
        documentArea = main; updateAuthState()
        root.center = HBox(20.0, sidebar, main).apply { HBox.setHgrow(main, Priority.ALWAYS) }
        status.styleClass.add("status"); status.isWrapText = true; BorderPane.setMargin(status, Insets(16.0, 0.0, 0.0, 0.0)); root.bottom = status
        stage.scene = Scene(root, 1160.0, 800.0).apply { stylesheets.add(VaultFxApp::class.java.getResource("/vault-fx.css")!!.toExternalForm()) }
        stage.minWidth = 900.0; stage.minHeight = 650.0; applyTheme(); stage.show()
        javafx.animation.Timeline(javafx.animation.KeyFrame(javafx.util.Duration.minutes(1.0), { notifyExpiries() })).apply { cycleCount = javafx.animation.Animation.INDEFINITE; play() }
        syncTimer = javafx.animation.Timeline(javafx.animation.KeyFrame(javafx.util.Duration.seconds(5.0), { checkForRemoteChanges() })).apply { cycleCount = javafx.animation.Animation.INDEFINITE; play() }
    }
    override fun stop() { syncTimer?.stop(); previewWindows.toList().forEach { it.close() }; key?.fill(0); key = null; executor.shutdownNow() }
    private fun updateAuthState() {
        fun visible(node: javafx.scene.Node, shown: Boolean) { node.isVisible = shown; node.isManaged = shown }
        visible(authPane, token == null); visible(unlockPane, token != null && key == null); visible(openPane, token != null && key != null)
        documentArea.isDisable = key == null
    }
    private fun applyTheme() { root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("light"), light); themeButton.text = if (light) "Тёмная тема" else "Светлая тема" }
    private fun label(value: String) = Label(value).apply { styleClass.add("muted") }
    private fun eyebrow(value: String) = Label(value).apply { styleClass.add("eyebrow") }
    private fun button(text: String, primary: Boolean, action: () -> Unit): Button = Button(text).apply {
        styleClass.add(if (primary) "primary" else "secondary"); maxWidth = Double.MAX_VALUE
        setOnAction { if (!busy) action() }; actionButtons.add(this)
    }
    private fun <T> themeDialog(dialog: Dialog<T>) {
        dialog.dialogPane.styleClass.addAll("vault-dialog", if (light) "vault-dialog-light" else "vault-dialog-dark")
        dialog.dialogPane.stylesheets.add(VaultFxApp::class.java.getResource("/vault-fx.css")!!.toExternalForm())
    }
    private fun error(message: String) { Alert(Alert.AlertType.ERROR, message, ButtonType.OK).apply { initOwner(stage); headerText = "Операция не выполнена"; themeDialog(this) }.showAndWait() }
    private fun askSecret(title: String): CharArray? {
        val input = PasswordField(); val dialog = Dialog<String>().apply {
            initOwner(stage); this.title = title; headerText = title; dialogPane.content = input; themeDialog(this)
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL); setResultConverter { if (it == ButtonType.OK) input.text else null }
        }
        return dialog.showAndWait().orElse(null)?.toCharArray().also { input.clear() }
    }
    private fun <T> task(message: String, work: () -> T, ocrId: String? = null, done: (T) -> Unit) {
        if (busy) return
        busy = true; activeOcrId = ocrId; list.refresh(); actionButtons.forEach { it.isDisable = true }; status.text = message
        executor.execute {
            try {
                val value = work()
                Platform.runLater { busy = false; activeOcrId = null; list.refresh(); actionButtons.forEach { it.isDisable = false }; done(value) }
            } catch (e: Exception) {
                Platform.runLater { busy = false; activeOcrId = null; list.refresh(); actionButtons.forEach { it.isDisable = false }; status.text = "Операция не выполнена."; error(when (e) {
                    is ApiException -> if (e.status == 401) "Неверный пароль или сессия завершена." else e.message ?: "Ошибка сервера"
                    is javax.crypto.AEADBadTagException -> "Неверный пароль восстановления или данные повреждены."
                    else -> e.message ?: e.javaClass.simpleName
                }) }
            }
        }
    }
    private fun login() {
        if (token != null) { error("Сначала выйдите из текущего аккаунта."); return }
        val name = username.text.trim(); val pass = password.text; password.clear()
        task("Выполняем вход…", { api.login(name, pass) }) { token = it; identity = server + "\u0000" + name.lowercase(java.util.Locale.ROOT); username.clear(); updateAuthState(); status.text = "Вход выполнен. Введите пароль восстановления."; recoveryInput.requestFocus() }
    }
    private fun register() {
        val name = username.text.trim(); val pass = password.text
        val recovery = askSecret("Создайте пароль восстановления") ?: return
        if (recovery.size < 12 || recovery.contentEquals(pass.toCharArray())) { recovery.fill('\u0000'); error("Пароль восстановления должен содержать не менее 12 символов и отличаться от пароля входа."); return }
        val repeat = askSecret("Повторите пароль восстановления") ?: run { recovery.fill('\u0000'); return }
        val matches = recovery.contentEquals(repeat); repeat.fill('\u0000')
        if (!matches) { recovery.fill('\u0000'); error("Пароли восстановления не совпадают."); return }
        password.clear(); task("Создаём аккаунт…", { api.register(name, pass, recovery) }) { status.text = "Аккаунт создан. Войдите и разблокируйте хранилище." }
    }
    private fun unlock() {
        val current = token ?: return error("Сначала войдите в аккаунт.")
        val recovery = recoveryInput.text.toCharArray(); recoveryInput.clear()
        if (recovery.isEmpty()) return error("Введите пароль восстановления.")
        task("Разблокируем хранилище…", { api.unlock(current, recovery) }) { key?.fill(0); key = it; updateAuthState(); status.text = "Хранилище разблокировано."; refresh() }
    }
    private fun lock() {
        previewWindows.toList().forEach { it.close() }; recoveryInput.clear(); key?.fill(0); key = null
        names.clear(); texts.clear(); dates.clear(); folders.clear(); savedFolders.clear(); knownServerIds.clear(); selectedFolder = ""; documents.clear(); allIds.clear(); search.clear(); renderFolders(); count.text = "0 документов"
        updateAuthState(); status.text = "Хранилище заблокировано."
    }
    private fun logout() {
        val current = token ?: return
        task("Выходим из аккаунта…", { api.logout(current) }) { token = null; identity = null; lock(); status.text = "Вы вышли из аккаунта." }
    }
    private data class Listing(val ids: List<String>, val metadata: Map<String, com.fasterxml.jackson.databind.JsonNode>, val remindersOk: Boolean)
    private fun refresh() {
        val current = token ?: return error("Сначала войдите в аккаунт.")
        val secret = key?.copyOf(); val account = identity
        task("Обновляем документы…", {
            try {
                val ids = api.list(current)
                val meta = if (secret == null) emptyMap() else ids.mapNotNull { id -> api.metadata(current, id)?.let { encrypted ->
                    val plain = VaultCrypto.decrypt(secret, encrypted, "metadata:$id".toByteArray())
                    try { id to mapper.readTree(plain) } finally { plain.fill(0) }
                } }.toMap()
                val ok = if (secret != null && account != null && System.getProperty("os.name").startsWith("Windows")) runCatching {
                    WindowsReminderStore().replaceAccount(account, meta.mapNotNull { (id, v) -> v.path("expiry").asText("").takeIf(String::isNotBlank)?.let { id to LocalDate.parse(it) } }.toMap())
                }.isSuccess else true
                Listing(ids, meta, ok)
            } finally { secret?.fill(0) }
        }) { result ->
            knownServerIds.clear(); knownServerIds.addAll(result.ids)
            names.clear(); texts.clear(); dates.clear(); folders.clear(); savedFolders.clear()
            result.metadata.forEach { (id, value) ->
                val folder = value.path("folder").asText("")
                if (value.path("recordType").asText("") == "documentvault.folder.v1") { if (folder.isNotBlank()) savedFolders.add(folder); return@forEach }
                if (folder.isNotBlank()) folders[id] = folder
                value.path("name").asText("").takeIf(String::isNotBlank)?.let { names[id] = it }
                value.path("text").asText("").takeIf(String::isNotBlank)?.let { texts[id] = it }
                value.path("expiry").asText("").takeIf(String::isNotBlank)?.let { dates[id] = LocalDate.parse(it) }
            }
            allIds.clear(); allIds.addAll(result.ids.filter { result.metadata[it]?.path("recordType")?.asText() != "documentvault.folder.v1" }); renderFolders(); filter()
            status.text = if (result.remindersOk) "Документы обновлены." else "Документы обновлены, но напоминания не синхронизированы."
            notifyExpiries()
        }
    }
    private fun checkForRemoteChanges() {
        val current = token ?: return
        if (key == null || busy || syncChecking) return
        val known = knownServerIds.toSet(); syncChecking = true
        executor.execute {
            val changed = runCatching {
                val remote = api.list(current).toSet()
                // Upload and metadata are separate requests. Wait until new records are complete.
                remote != known && (remote - known).all { api.metadata(current, it) != null }
            }.getOrDefault(false)
            Platform.runLater {
                syncChecking = false
                if (changed && token == current && key != null && !busy) refresh()
            }
        }
    }
    private val allIds = mutableListOf<String>()
    private fun filter() {
        val q = search.text.trim(); folderCards.isVisible = (selectedFolder == null || selectedFolder == "") && q.isEmpty() && folderCards.children.isNotEmpty(); folderCards.isManaged = folderCards.isVisible; documents.setAll(allIds.filter { id -> (q.isNotEmpty() || selectedFolder == null || folders.getOrDefault(id, "") == selectedFolder) && (q.isEmpty() || OcrSearch.matches("${names[id] ?: id} ${texts[id] ?: ""}", q)) }); count.text = "Документов: ${documents.size}"
    }
    private fun renderFolders() {
        folderBar.children.clear(); folderCards.children.clear()
        fun configureDrop(target: Button, folder: String) {
            target.setOnDragOver { event ->
                val id = event.dragboard.getContent(documentDragFormat) as? String
                if (!busy && key != null && id != null && id in allIds && event.gestureSource is ListCell<*>) event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE)
                event.consume()
            }
            target.setOnDragDropped { event ->
                val id = event.dragboard.getContent(documentDragFormat) as? String
                val valid = !busy && key != null && id != null && id in allIds && event.gestureSource is ListCell<*>
                if (valid) moveDocument(id!!, folder)
                event.isDropCompleted = valid; event.consume()
            }
        }
        fun chip(title: String, folder: String?, acceptFiles: Boolean) {
            val chip = Button(title).apply {
                styleClass.add(if (selectedFolder == folder) "folder-active" else "folder-chip")
                if (folder != null && folder.isNotEmpty()) graphic = folderIcon()
                setOnAction { selectedFolder = folder; renderFolders(); filter() }
            }
            if (acceptFiles) configureDrop(chip, folder.orEmpty())
            folderBar.children.add(chip)
        }
        if (!selectedFolder.isNullOrEmpty()) chip("..", "", true)
        chip("Все документы", null, false); chip("Корень", "", true)
        (savedFolders + folders.values).filter(String::isNotBlank).distinct().sorted().forEach { name ->
            chip(name, name, true)
            val tile = Button(name).apply {
                styleClass.add("folder-tile"); accessibleText = "Открыть папку $name"
                graphic = folderIcon()
                contentDisplay = ContentDisplay.TOP; graphicTextGap = 10.0
                setOnAction { selectedFolder = name; renderFolders(); filter() }
            }
            configureDrop(tile, name); folderCards.children.add(tile)
        }
        folderCards.isVisible = (selectedFolder == null || selectedFolder == "") && search.text.isNullOrBlank() && folderCards.children.isNotEmpty(); folderCards.isManaged = folderCards.isVisible
        folderBar.children.add(Button("+ Папка").apply { styleClass.add("folder-chip"); setOnAction { if (!busy) createFolderDialog() } })
    }
    private fun folderIcon() = javafx.scene.shape.SVGPath().apply { content = "M2 5 C2 3.9 2.9 3 4 3 H14 L18 7 H34 C35.1 7 36 7.9 36 9 V25 C36 26.1 35.1 27 34 27 H4 C2.9 27 2 26.1 2 25 Z"; styleClass.add("folder-icon") }
    private fun createFolderDialog() {
        if (key == null || token == null) return error("Сначала разблокируйте хранилище.")
        val input = TextInputDialog().apply { initOwner(stage); title = "Новая папка"; headerText = "Название папки"; themeDialog(this) }.showAndWait().orElse(null) ?: return
        createFolder(input.trim())
    }
    private fun createFolder(name: String) {
        if (name.isEmpty() || name.length > 80 || name.contains('/') || name.contains('\\')) return error("Название папки: от 1 до 80 символов, без слешей.")
        if (name in savedFolders) { selectedFolder = name; renderFolders(); filter(); status.text = "Папка уже существует."; return }
        val current = token ?: return error("Сначала войдите в аккаунт."); val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище.")
        task("Создаём зашифрованную папку…", {
            try {
                val id = UUID.randomUUID().toString(); val payload = DocumentPayload.encode("folder", byteArrayOf())
                try { api.upload(current, id, VaultCrypto.encrypt(secret, payload, "document:$id".toByteArray())) } finally { payload.fill(0) }
                val metadata = mapper.writeValueAsBytes(mapOf("recordType" to "documentvault.folder.v1", "folder" to name))
                try {
                    try { api.saveMetadata(current, id, VaultCrypto.encrypt(secret, metadata, "metadata:$id".toByteArray())) }
                    catch (failure: Exception) { try { api.delete(current, id) } catch (_: Exception) { }; throw failure }
                } finally { metadata.fill(0) }
            } finally { secret.fill(0) }
        }) { savedFolders.add(name); selectedFolder = name; renderFolders(); filter(); status.text = "Папка создана и синхронизирована." }
    }
    private data class FolderChoice(val folder: String, val label: String) { override fun toString() = label }
    private fun chooseFolder() {
        val id = selected() ?: return
        val options = listOf(FolderChoice("", "Без папки (корень)")) + (savedFolders + folders.values).filter(String::isNotBlank).distinct().sorted().map { FolderChoice(it, it) }
        val dialog = ChoiceDialog(options.first(), options).apply { initOwner(stage); title = "Переместить документ"; headerText = "Выберите папку"; themeDialog(this) }
        val chosen = dialog.showAndWait().orElse(null) ?: return
        moveDocument(id, chosen.folder)
    }
    private fun moveDocument(id: String, folder: String) {
        if (id !in allIds || key == null || token == null || busy) return
        saveMetadata(id, "Перемещаем документ…", { it.put("folder", folder) }) {
            if (folder.isEmpty()) folders.remove(id) else folders[id] = folder
            renderFolders(); filter(); status.text = "Документ перемещён."
        }
    }

    private fun selected(): String? = list.selectionModel.selectedItem ?: run { error("Выберите документ."); null }
    private fun upload() {
        val current = token ?: return error("Сначала войдите в аккаунт."); val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище.")
        val file = FileChooser().apply { title = "Выберите документ" }.showOpenDialog(stage) ?: run { secret.fill(0); return }
        val destination = selectedFolder.orEmpty()
        task("Шифруем и загружаем файл…", {
            try {
                require(Files.size(file.toPath()) <= FILE_LIMIT) { "Размер файла превышает 15 МиБ." }
                val id = UUID.randomUUID().toString(); val content = Files.readAllBytes(file.toPath())
                val payload = try { DocumentPayload.encode(file.name, content) } finally { content.fill(0) }
                try { api.upload(current, id, VaultCrypto.encrypt(secret, payload, "document:$id".toByteArray())) } finally { payload.fill(0) }
                val meta = mapper.writeValueAsBytes(mapOf("name" to file.name, "text" to "", "expiry" to "", "folder" to destination))
                val saved = try { api.saveMetadata(current, id, VaultCrypto.encrypt(secret, meta, "metadata:$id".toByteArray())); true } catch (_: Exception) { false } finally { meta.fill(0) }
                id to saved
            } finally { secret.fill(0) }
        }) { (id, saved) ->
            names[id] = file.name; if (destination.isNotEmpty()) folders[id] = destination; allIds.add(id); renderFolders(); filter(); list.selectionModel.select(id)
            status.text = if (saved) "Файл загружен. Запускаем распознавание." else "Файл загружен, но метаданные не сохранены."
            analyze()
        }
    }
    private fun download() {
        val current = token ?: return error("Сначала войдите в аккаунт."); val id = selected() ?: return
        val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище.")
        val target = FileChooser().apply { title = "Сохранить документ"; initialFileName = names[id] ?: "document" }.showSaveDialog(stage) ?: run { secret.fill(0); return }
        task("Загружаем и расшифровываем…", {
            try {
                val plain = VaultCrypto.decrypt(secret, api.download(current, id), "document:$id".toByteArray())
                val file = try { DocumentPayload.decode(plain) } finally { plain.fill(0) }
                try { Files.write(target.toPath(), file.second) } finally { file.second.fill(0) }
            } finally { secret.fill(0) }
        }) { status.text = "Файл сохранён: ${target.name}" }
    }
    private fun analyze() {
        val current = token ?: return error("Сначала войдите в аккаунт."); val id = selected() ?: return
        val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище."); val previous = dates[id]; val account = identity; val displayName = names[id]
        task("Распознаём текст…", {
            try {
                val plain = VaultCrypto.decrypt(secret, api.download(current, id), "document:$id".toByteArray())
                val document = try { DocumentPayload.decode(plain) } finally { plain.fill(0) }
                val recognition = try { LocalOcr.recognizeDetailed(document.first, document.second) } finally { document.second.fill(0) }
                val text = recognition.text
                val expiry = previous ?: DocumentDates.automaticExpiry(text)
                val existing = api.metadata(current, id)?.let { encrypted ->
                    val bytes = VaultCrypto.decrypt(secret, encrypted, "metadata:$id".toByteArray()); try { mapper.readTree(bytes) } finally { bytes.fill(0) }
                }
                val data = if (existing is ObjectNode) existing.deepCopy() else mapper.createObjectNode()
                data.put("name", displayName ?: document.first); data.put("text", text); data.put("expiry", expiry?.toString() ?: "")
                if (recognition.layout == null) data.remove("ocrLayout") else data.set<com.fasterxml.jackson.databind.JsonNode>("ocrLayout", recognition.layout)
                val bytes = mapper.writeValueAsBytes(data); try { api.saveMetadata(current, id, VaultCrypto.encrypt(secret, bytes, "metadata:$id".toByteArray())) } finally { bytes.fill(0) }
                val reminderOk = if (account != null && System.getProperty("os.name").startsWith("Windows")) runCatching { WindowsReminderStore().put(account, id, expiry) }.isSuccess else true
                Triple(text, expiry, reminderOk)
            } finally { secret.fill(0) }
        }, id) { (text, expiry, reminderOk) ->
            texts[id] = text; if (expiry == null) dates.remove(id) else dates[id] = expiry; list.refresh()
            status.text = if (!reminderOk) "Текст сохранён, но напоминание не обновлено." else if (expiry == null) "Текст сохранён. Срок не найден." else "Текст сохранён. Срок: $expiry. Проверьте дату в документе."
        }
    }
    private data class PreviewPage(val width: Int, val height: Int, val pixels: IntArray, val pageCount: Int, val textLayer: com.fasterxml.jackson.databind.JsonNode? = null)
    private fun renderPreview(bytes: ByteArray, name: String, pageIndex: Int): PreviewPage {
        fun pixels(image: java.awt.image.BufferedImage, pages: Int): PreviewPage {
            val values = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            return PreviewPage(image.width, image.height, values, pages)
        }
        if (name.endsWith(".pdf", true)) {
            org.apache.pdfbox.Loader.loadPDF(bytes).use { pdf ->
                require(pdf.numberOfPages in 1..20) { "Поддерживаются PDF не более 20 страниц." }
                require(pageIndex in 0 until pdf.numberOfPages) { "Страница PDF не найдена." }
                val box = pdf.getPage(pageIndex).cropBox
                val rendered = org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImage(pageIndex, LocalOcr.pdfScale(box.width, box.height), org.apache.pdfbox.rendering.ImageType.RGB)
                try { return pixels(rendered, pdf.numberOfPages).copy(textLayer = runCatching { PdfTextLayer.page(pdf, pageIndex) }.getOrNull()) } finally { rendered.flush() }
            }
        }
        val image = LocalOcr.decodeImage(bytes); var oriented: java.awt.image.BufferedImage? = null
        try { oriented = LocalOcr.orientImage(image, LocalOcr.readOrientation(bytes)); return pixels(oriented, 1) }
        finally { if (oriented !== image) oriented?.flush(); image.flush() }
    }
    private data class LoadedPreview(val name: String, val contents: ByteArray, val layout: com.fasterxml.jackson.databind.JsonNode?, val text: String?)
    private fun preview() = openPreview(false)
    private fun previewWithOcr() = openPreview(true)
    private fun openPreview(withOcr: Boolean) {
        val current = token ?: return error("Сначала войдите в аккаунт."); val id = selected() ?: return
        val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище."); val displayName = names[id]
        task("Загружаем документ для просмотра…", {
            var contents: ByteArray? = null
            try {
                val plain = VaultCrypto.decrypt(secret, api.download(current, id), "document:$id".toByteArray())
                val document = try { DocumentPayload.decode(plain) } finally { plain.fill(0) }; contents = document.second
                val name = displayName ?: document.first
                var recognizedText: String? = null
                val layout = if (!withOcr || name.endsWith(".pdf", true)) null else {
                    val encrypted = api.metadata(current, id) ?: throw IllegalStateException("Сначала распознайте документ заново.")
                    val metadata = VaultCrypto.decrypt(secret, encrypted, "metadata:$id".toByteArray())
                    try { val data = mapper.readTree(metadata); recognizedText = data.path("text").asText(""); data.path("ocrLayout").takeUnless { it.isMissingNode || it.isNull || it.path("words").size() == 0 } ?: throw IllegalStateException("Координаты текста не найдены. Распознайте изображение заново.") }
                    finally { metadata.fill(0) }
                }
                LoadedPreview(name, document.second, layout, recognizedText).also { contents = null }
            } finally { contents?.fill(0); secret.fill(0) }
        }, if (withOcr) id else null) { document ->
            if (key == null) { document.contents.fill(0); return@task }
            showPreview(document.name, document.contents, document.layout, document.text, if (withOcr) id else null)
        }
    }
    private fun renderOcrPreview(bytes: ByteArray, layout: com.fasterxml.jackson.databind.JsonNode): PreviewPage {
        val source = LocalOcr.decodeImage(bytes); var oriented: java.awt.image.BufferedImage? = null; var overlay: java.awt.image.BufferedImage? = null
        try {
            oriented = LocalOcr.orientImage(source, LocalOcr.readOrientation(bytes)); overlay = OcrImageOverlay.render(oriented, layout)
            return PreviewPage(overlay.width, overlay.height, overlay.getRGB(0, 0, overlay.width, overlay.height, null, 0, overlay.width), 1)
        } finally { overlay?.flush(); if (oriented !== source) oriented?.flush(); source.flush() }
    }
    private fun showPreview(name: String, contents: ByteArray, layout: com.fasterxml.jackson.databind.JsonNode?, recognizedText: String?, ocrId: String?) {
        val window = Stage(); window.initOwner(stage); window.title = name; previewWindows.add(window)
        val image = javafx.scene.image.ImageView().apply { isPreserveRatio = true; isSmooth = true; fitWidth = 720.0 }
        val highlights = javafx.scene.Group().apply { isMouseTransparent = true }
        val page = javafx.scene.Group(image, highlights)
        val scroll = ScrollPane(page).apply { isFitToWidth = false; isFitToHeight = false; isPannable = false; prefViewportWidth = 760.0; prefViewportHeight = 620.0; styleClass.add("preview-pane") }
        var selection = layout?.let(::OcrTextSelection); var selectionStart = -1; var selectionEnd = -1; var selecting = false
        fun drawSelection() {
            highlights.children.clear()
            val width = image.boundsInLocal.width; val height = image.boundsInLocal.height
            selection?.selected(selectionStart, selectionEnd)?.forEach { word ->
                highlights.children.add(javafx.scene.shape.Rectangle(word.left * width, word.top * height, (word.right - word.left) * width, (word.bottom - word.top) * height).apply { fill = javafx.scene.paint.Color.rgb(100, 150, 255, 0.38) })
            }
        }
        image.boundsInLocalProperty().addListener { _, _, _ -> drawSelection() }
        fun copySelection() {
            val text = selection?.text(selectionStart, selectionEnd).orEmpty()
            if (text.isNotEmpty()) { val content = javafx.scene.input.ClipboardContent(); content.putString(text); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); status.text = "Текст скопирован." }
        }
        val pageLabel = Label(""); val previous = Button("Назад"); val next = Button("Далее"); val smaller = Button("−"); val larger = Button("+"); val fit = Button("По ширине")
        val pageControls = HBox(8.0, previous, pageLabel, next).apply { alignment = Pos.CENTER }
        val zoomControls = HBox(8.0, smaller, fit, larger, Label("Колесо — масштаб · ЛКМ — перемещение").apply { styleClass.add("muted") }).apply { alignment = Pos.CENTER }
        var currentPage = 0; var totalPages = 1; var closed = false
        fun render() {
            if (closed || busy) return
            val copy = contents.copyOf(); val index = currentPage
            task("Открываем страницу…", { try { if (layout == null) renderPreview(copy, name, index) else renderOcrPreview(copy, layout) } finally { copy.fill(0) } }, ocrId) { result ->
                try {
                    if (!closed) {
                        val frame = javafx.scene.image.WritableImage(result.width, result.height)
                        frame.pixelWriter.setPixels(0, 0, result.width, result.height, javafx.scene.image.PixelFormat.getIntArgbInstance(), result.pixels, 0, result.width)
                        image.image = frame; totalPages = result.pageCount; pageLabel.text = "Страница ${index + 1} из $totalPages"
                        if (layout == null) { selection = result.textLayer?.let(::OcrTextSelection); selectionStart = -1; selectionEnd = -1; drawSelection() }
                        previous.isDisable = index == 0; next.isDisable = index + 1 >= totalPages
                        pageControls.isVisible = totalPages > 1; pageControls.isManaged = totalPages > 1
                        status.text = "Документ открыт."
                    }
                } finally { result.pixels.fill(0) }
            }
        }
        previous.setOnAction { if (!busy && currentPage > 0) { currentPage--; render() } }
        next.setOnAction { if (!busy && currentPage + 1 < totalPages) { currentPage++; render() } }
        fun changeZoom(newWidth: Double, anchorX: Double, anchorY: Double) {
            val viewport = scroll.viewportBounds; val oldWidth = image.boundsInParent.width; val oldHeight = image.boundsInParent.height
            if (oldWidth <= 0.0 || oldHeight <= 0.0 || viewport.width <= 0 || viewport.height <= 0) return
            val oldOverflowX = (oldWidth - viewport.width).coerceAtLeast(0.0); val oldOverflowY = (oldHeight - viewport.height).coerceAtLeast(0.0)
            val imageX = (scroll.hvalue * oldOverflowX + anchorX.coerceIn(0.0, viewport.width)) / oldWidth
            val imageY = (scroll.vvalue * oldOverflowY + anchorY.coerceIn(0.0, viewport.height)) / oldHeight
            image.fitWidth = newWidth.coerceIn(180.0, 5000.0); image.applyCss(); image.parent?.layout()
            val newWidthActual = image.boundsInParent.width; val newHeightActual = image.boundsInParent.height
            val overflowX = (newWidthActual - viewport.width).coerceAtLeast(0.0); val overflowY = (newHeightActual - viewport.height).coerceAtLeast(0.0)
            scroll.hvalue = if (overflowX > 0) ((imageX * newWidthActual - anchorX) / overflowX).coerceIn(0.0, 1.0) else 0.0
            scroll.vvalue = if (overflowY > 0) ((imageY * newHeightActual - anchorY) / overflowY).coerceIn(0.0, 1.0) else 0.0
        }
        fun centerZoom(value: Double) = changeZoom(value, scroll.viewportBounds.width / 2, scroll.viewportBounds.height / 2)
        smaller.setOnAction { centerZoom(image.fitWidth / 1.25) }
        larger.setOnAction { centerZoom(image.fitWidth * 1.25) }
        fit.setOnAction { centerZoom((scroll.viewportBounds.width - 20).coerceAtLeast(180.0)) }
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL) { event ->
            if (image.image != null && event.deltaY != 0.0) {
                val point = scroll.sceneToLocal(event.sceneX, event.sceneY)
                changeZoom(image.fitWidth * if (event.deltaY > 0) 1.15 else 1.0 / 1.15, point.x, point.y); event.consume()
            }
        }
        var dragX = 0.0; var dragY = 0.0; var startH = 0.0; var startV = 0.0
        image.setOnMousePressed { event -> if (event.button == javafx.scene.input.MouseButton.PRIMARY) {
            val hit = if (image.boundsInLocal.width > 0 && image.boundsInLocal.height > 0) selection?.hit(event.x / image.boundsInLocal.width, event.y / image.boundsInLocal.height) ?: -1 else -1
            if (hit >= 0) { selectionStart = hit; selectionEnd = hit; selecting = true; drawSelection(); image.cursor = javafx.scene.Cursor.TEXT; event.consume(); return@setOnMousePressed }
            selecting = false
            dragX = event.sceneX; dragY = event.sceneY; startH = scroll.hvalue; startV = scroll.vvalue
            image.cursor = javafx.scene.Cursor.CLOSED_HAND; event.consume()
        } }
        image.setOnMouseDragged { event -> if (event.isPrimaryButtonDown) {
            if (selecting) { selectionEnd = selection?.nearest(event.x / image.boundsInLocal.width, event.y / image.boundsInLocal.height) ?: -1; drawSelection(); event.consume(); return@setOnMouseDragged }
            val overflowX = (image.boundsInParent.width - scroll.viewportBounds.width).coerceAtLeast(0.0)
            val overflowY = (image.boundsInParent.height - scroll.viewportBounds.height).coerceAtLeast(0.0)
            if (overflowX > 0) scroll.hvalue = (startH - (event.sceneX - dragX) / overflowX).coerceIn(0.0, 1.0)
            if (overflowY > 0) scroll.vvalue = (startV - (event.sceneY - dragY) / overflowY).coerceIn(0.0, 1.0)
            event.consume()
        } }
        image.setOnMouseMoved { event ->
            val hit = if (image.boundsInLocal.width > 0 && image.boundsInLocal.height > 0) selection?.hit(event.x / image.boundsInLocal.width, event.y / image.boundsInLocal.height) ?: -1 else -1
            image.cursor = if (hit >= 0) javafx.scene.Cursor.TEXT else javafx.scene.Cursor.OPEN_HAND
        }
        image.setOnMouseReleased { event ->
            selecting = false
            val hit = if (image.boundsInLocal.width > 0 && image.boundsInLocal.height > 0) selection?.hit(event.x / image.boundsInLocal.width, event.y / image.boundsInLocal.height) ?: -1 else -1
            image.cursor = if (hit >= 0) javafx.scene.Cursor.TEXT else javafx.scene.Cursor.OPEN_HAND
        }
        image.cursor = javafx.scene.Cursor.OPEN_HAND
        val display: javafx.scene.Node = scroll
        if (selection != null || name.endsWith(".pdf", true)) {
            val copy = Button("Копировать выделенное").apply { styleClass.add("secondary"); setOnAction { copySelection() } }
            zoomControls.children.add(copy)
            window.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED) { event -> if (event.isShortcutDown && event.code == javafx.scene.input.KeyCode.C) { copySelection(); event.consume() } }
        }
        val content = VBox(10.0, display, zoomControls, pageControls).apply { padding = Insets(12.0); VBox.setVgrow(display, Priority.ALWAYS) }
        content.styleClass.add("app"); content.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("light"), light)
        listOf(previous, next, smaller, larger, fit).forEach { it.styleClass.add("secondary") }
        window.scene = Scene(content, 820.0, 760.0).apply { stylesheets.add(VaultFxApp::class.java.getResource("/vault-fx.css")!!.toExternalForm()) }; window.minWidth = 420.0; window.minHeight = 400.0
        window.setOnHidden { closed = true; image.image = null; highlights.children.clear(); contents.fill(0); previewWindows.remove(window) }
        window.show(); render()
    }
    private fun renameDocument() {
        val id = selected() ?: return; val old = names[id] ?: id
        val chosen = TextInputDialog(old).apply { initOwner(stage); title = "Переименовать документ"; headerText = "Новое название"; themeDialog(this) }.showAndWait().orElse(null) ?: return
        var name = chosen.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.toByteArray(Charsets.UTF_8).size > 240) return error("Укажите название до 240 байт без слешей.")
        val ext = old.substringAfterLast('.', ""); if (ext.isNotEmpty() && !name.endsWith(".$ext", true)) name += ".$ext"
        if (name.toByteArray(Charsets.UTF_8).size > 240) return error("Название слишком длинное.")
        val newName = name
        saveMetadata(id, "Переименовываем документ…", { it.put("name", newName) }) { names[id] = newName; filter(); list.selectionModel.select(id); list.refresh(); status.text = "Название сохранено." }
    }
    private fun editOcr() {
        val id = selected() ?: return; if (key == null) return error("Сначала разблокируйте хранилище.")
        val input = TextArea(texts[id] ?: "").apply { prefRowCount = 14; prefColumnCount = 60; isWrapText = true }
        val dialog = Dialog<String>().apply {
            initOwner(stage); title = "Редактирование текста OCR"; headerText = "Изменения относятся к распознанному тексту, исходный файл не меняется."; themeDialog(this)
            dialogPane.content = input; dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL); setResultConverter { if (it == ButtonType.OK) input.text else null }
        }
        val corrected = dialog.showAndWait().orElse(null) ?: return
        saveMetadata(id, "Сохраняем текст…", { data ->
            if (data.path("text").asText("") != corrected) {
                val layout = data.path("ocrLayout"); val words = layout.path("words")
                val tokens = corrected.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()
                val visibleWords = if (words.isArray) words.filter { it.isArray && it.size() == 5 && it[0].asText().isNotEmpty() } else emptyList()
                if (words.isArray && words.all { it.isArray && it.size() == 5 } && visibleWords.size == tokens.size) {
                    visibleWords.forEachIndexed { index, word -> (word as com.fasterxml.jackson.databind.node.ArrayNode).set(0, mapper.nodeFactory.textNode(tokens[index])) }
                } else data.remove("ocrLayout")
            }
            data.put("text", corrected)
        }) { texts[id] = corrected; filter(); list.selectionModel.select(id); status.text = "Текст OCR сохранён. Если число слов изменилось, распознайте изображение заново для наложения." }
    }
    private fun saveMetadata(id: String, message: String, update: (ObjectNode) -> Unit, complete: () -> Unit) {
        val current = token ?: return error("Сначала войдите в аккаунт.")
        val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище.")
        task(message, {
            try {
                val existing = api.metadata(current, id)?.let { encrypted -> val plain = VaultCrypto.decrypt(secret, encrypted, "metadata:$id".toByteArray()); try { mapper.readTree(plain) } finally { plain.fill(0) } }
                val data = if (existing is ObjectNode) existing.deepCopy() else mapper.createObjectNode(); update(data)
                val plain = mapper.writeValueAsBytes(data); try { api.saveMetadata(current, id, VaultCrypto.encrypt(secret, plain, "metadata:$id".toByteArray())) } finally { plain.fill(0) }
            } finally { secret.fill(0) }
        }) { complete() }
    }
    private fun confirmDelete() {
        val id = selected() ?: return; if (key == null) return error("Сначала разблокируйте хранилище.")
        val answer = Alert(Alert.AlertType.CONFIRMATION, "«${names[id] ?: id}» будет удалён с сервера и со всех устройств. Восстановить его не получится.", ButtonType.CANCEL, ButtonType.OK).apply { initOwner(stage); title = "Удалить документ?"; headerText = "Удалить документ?"; themeDialog(this) }.showAndWait().orElse(ButtonType.CANCEL)
        if (answer != ButtonType.OK) return
        deleteDocument(id)
    }
    private fun deleteDocument(id: String) {
        val current = token ?: return error("Сначала войдите в аккаунт."); val account = identity
        task("Удаляем документ…", {
            api.delete(current, id)
            if (account != null && System.getProperty("os.name").startsWith("Windows")) runCatching { WindowsReminderStore().put(account, id, null) }.isSuccess else true
        }) { reminderOk ->
            allIds.remove(id); names.remove(id); texts.remove(id); dates.remove(id); folders.remove(id); renderFolders(); filter()
            status.text = if (reminderOk) "Документ удалён." else "Документ удалён, но напоминание не обновлено."
        }
    }

    private fun showExpiries() {
        if (key == null) return error("Сначала разблокируйте хранилище.")
        val message = dates.entries.sortedBy { it.value }.joinToString("\n") { "${it.value} — ${names[it.key] ?: it.key}" }.ifEmpty { "Сроки не найдены." }
        Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK).apply { initOwner(stage); title = "Сроки документов"; headerText = null; themeDialog(this) }.showAndWait()
    }
    private fun editExpiry() {
        val current = token ?: return error("Сначала войдите в аккаунт."); val id = selected() ?: return
        val secret = key?.copyOf() ?: return error("Сначала разблокируйте хранилище.")
        val input = TextInputDialog(dates[id]?.toString() ?: "").apply { initOwner(stage); title = "Срок документа"; headerText = "Введите дату ГГГГ-ММ-ДД или оставьте пустым"; themeDialog(this) }.showAndWait().orElse(null) ?: run { secret.fill(0); return }
        val date = if (input.isBlank()) null else try { LocalDate.parse(input.trim()) } catch (_: Exception) { secret.fill(0); return error("Неверный формат даты.") }
        val account = identity
        task("Сохраняем срок…", {
            try {
                val existing = api.metadata(current, id)?.let { encrypted -> val plain = VaultCrypto.decrypt(secret, encrypted, "metadata:$id".toByteArray()); try { mapper.readTree(plain) } finally { plain.fill(0) } }
                val data = if (existing is ObjectNode) existing.deepCopy() else mapper.createObjectNode(); data.put("expiry", date?.toString() ?: "")
                val bytes = mapper.writeValueAsBytes(data); try { api.saveMetadata(current, id, VaultCrypto.encrypt(secret, bytes, "metadata:$id".toByteArray())) } finally { bytes.fill(0) }
                if (account != null && System.getProperty("os.name").startsWith("Windows")) runCatching { WindowsReminderStore().put(account, id, date) }.isSuccess else true
            } finally { secret.fill(0) }
        }) { ok -> if (date == null) dates.remove(id) else dates[id] = date; reminderDay = null; list.refresh(); status.text = if (ok) "Срок сохранён." else "Срок сохранён, но напоминание не обновлено." }
    }
    private fun notifyExpiries() {
        if (key == null || busy) return
        val today = LocalDate.now(); if (reminderDay == today) return
        if (dates.values.none { !it.isAfter(today.plusDays(30)) }) return
        reminderDay = today
        Alert(Alert.AlertType.INFORMATION, "Срок действия документов истёк или скоро истечёт. Откройте раздел «Сроки».", ButtonType.OK).apply {
            initOwner(stage); title = "Напоминание"; headerText = null; themeDialog(this)
        }.show()
    }
    private fun installReminders() { task("Настраиваем уведомления Windows…", { WindowsReminderTask.install() }) { status.text = if (it) "Фоновые уведомления включены." else "Фоновые уведомления уже настроены." } }
}

fun main(args: Array<String>) { Application.launch(VaultFxApp::class.java, *args) }
