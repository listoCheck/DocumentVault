package ru.documentvault.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import ru.documentvault.core.VaultCrypto
import ru.documentvault.core.DocumentDates
import ru.documentvault.core.OcrSearch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Color
import java.awt.Font
import java.awt.Dimension
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.SwingWorker

private val ROOT = System.getenv("VAULT_SERVER_URL") ?: "https://95.174.95.143"
private const val MAX_FILE_SIZE = 15L * 1024 * 1024
private val json = ObjectMapper()
private val b64 = Base64.getEncoder()
private val b64decode = Base64.getDecoder()

class ApiException(val status: Int) : RuntimeException(when (status) {
    413 -> "Превышен размер файла или лимит хранилища."
    429 -> "Слишком много попыток. Подождите и повторите."
    else -> "Ошибка сервера: HTTP $status"
})

class VaultApi(private val root: String = ROOT) {
    init {
        val uri = URI.create(root)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "Некорректный адрес сервера." }
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1"))) { "Для удалённого сервера требуется HTTPS." }
    }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun request(method: String, path: String, bytes: ByteArray = byteArrayOf(), token: String? = null, documentId: String? = null, jsonBody: Boolean = false): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofSeconds(60))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (documentId != null) builder.header("X-Document-Id", documentId)
        if (jsonBody) builder.header("Content-Type", "application/json")
        val publisher = if (bytes.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(bytes)
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) throw ApiException(response.statusCode())
        return response.body()
    }
    fun register(username: String, loginPassword: String, recoveryPassword: CharArray) {
        val master = VaultCrypto.newMasterKey()
        val salt = VaultCrypto.newSalt()
        val recoveryKey = VaultCrypto.deriveRecoveryKey(recoveryPassword, salt)
        try {
            val wrapped = VaultCrypto.encrypt(recoveryKey, master, "master-key".toByteArray())
            val body = json.writeValueAsBytes(mapOf("username" to username, "password" to loginPassword, "recoverySalt" to b64.encodeToString(salt), "wrappedKey" to b64.encodeToString(wrapped)))
            request("POST", "/v1/accounts", body, jsonBody = true)
        } finally { master.fill(0); recoveryKey.fill(0); recoveryPassword.fill('\u0000') }
    }
    fun login(username: String, password: String): String {
        val body = json.writeValueAsBytes(mapOf("username" to username, "password" to password))
        return json.readTree(request("POST", "/v1/sessions", body, jsonBody = true))["token"].asText()
    }
    fun unlock(token: String, password: CharArray): ByteArray {
        try {
            val vault = json.readTree(request("GET", "/v1/vault", token = token))
            val key = VaultCrypto.deriveRecoveryKey(password, b64decode.decode(vault["recoverySalt"].asText()))
            return try { VaultCrypto.decrypt(key, b64decode.decode(vault["wrappedKey"].asText()), "master-key".toByteArray()) }
            finally { key.fill(0) }
        } finally { password.fill('\u0000') }
    }
    fun list(token: String): List<String> = json.readTree(request("GET", "/v1/documents", token = token)).map { it["id"].asText() }
    fun upload(token: String, id: String, data: ByteArray) { request("POST", "/v1/documents", data, token, id) }
    fun download(token: String, id: String): ByteArray = request("GET", "/v1/documents/$id", token = token)
    fun metadata(token: String, id: String): ByteArray? = try { request("GET", "/v1/documents/$id/metadata", token = token) } catch (e: ApiException) { if (e.status == 404) null else throw e }
    fun saveMetadata(token: String, id: String, data: ByteArray) { request("PUT", "/v1/documents/$id/metadata", data, token) }
    fun delete(token: String, id: String) { request("DELETE", "/v1/documents/$id", token = token) }
    fun logout(token: String) { request("DELETE", "/v1/sessions/current", token = token) }
}

class VaultWindow(private val themePrefs: java.util.prefs.Preferences = java.util.prefs.Preferences.userNodeForPackage(VaultWindow::class.java)) : JFrame("DocumentVault") {
    private val api = VaultApi()
    private var token: String? = null
    private var accountIdentity: String? = null
    private var masterKey: ByteArray? = null
    private var busy = false
    private val user = JTextField(16)
    private val password = JPasswordField(16)
    private val model = DefaultListModel<String>()
    private val list = JList(model)
    private val recognized = mutableMapOf<String, String>()
    private val documentNames = mutableMapOf<String, String>()
    private val expiryDates = mutableMapOf<String, java.time.LocalDate>()
    private var reminderDay: java.time.LocalDate? = null
    private val query = JTextField(20)
    private val status = JLabel("Сервер: $ROOT. Выполните вход или регистрацию.")
    private val buttons = mutableListOf<JButton>()

    private val background = Color(15, 17, 23)
    private val surface = Color(25, 28, 37)
    private val surfaceAlt = Color(33, 36, 48)
    private val foreground = Color(242, 242, 236)
    private val muted = Color(157, 163, 172)
    private val acid = Color(200, 255, 69)
    private val violet = Color(180, 157, 255)
    private var lightTheme = themePrefs.getBoolean("lightTheme", false)
    private lateinit var themeButton: JButton

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(900, 650)
        setSize(1160, 800)
        setLocationRelativeTo(null)
        contentPane.background = this@VaultWindow.background
        contentPane.layout = BorderLayout(0, 0)

        fun caption(text: String, size: Int = 12, color: Color = muted, bold: Boolean = false) = JLabel(text).apply {
            foreground = color; font = Font("Segoe UI", if (bold) Font.BOLD else Font.PLAIN, size); alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
        fun panel(color: Color = surface) = JPanel().apply { background = color; border = EmptyBorder(20, 20, 20, 20) }
        fun stack(color: Color = surface) = panel(color).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        fun gap(height: Int) = Box.createVerticalStrut(height)
        fun leftButton(text: String, action: () -> Unit) = button(text, action).apply {
            alignmentX = LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 42)
        }
        fun styleInput(field: JTextField) {
            field.font = Font("Segoe UI", Font.PLAIN, 14); field.foreground = foreground; field.caretColor = acid
            field.background = surfaceAlt; field.border = CompoundBorder(LineBorder(Color(65, 68, 79), 1, true), EmptyBorder(10, 12, 10, 12))
            field.alignmentX = java.awt.Component.LEFT_ALIGNMENT; field.maximumSize = Dimension(Int.MAX_VALUE, 42)
        }
        styleInput(user); styleInput(password); styleInput(query)
        password.addActionListener { login() }; query.addActionListener { search() }
        user.toolTipText = "Логин"; password.toolTipText = "Пароль входа"; query.toolTipText = "Поиск по названию и тексту"

        val header = JPanel(BorderLayout()).apply { background = this@VaultWindow.background; border = EmptyBorder(24, 28, 20, 28) }
        val brand = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            background = this@VaultWindow.background
            add(caption("DV /", 20, acid, true)); add(caption("DOCUMENT VAULT", 16, this@VaultWindow.foreground, true))
            add(caption("  PRIVATE SPACE", 11, violet, true))
        }
        header.add(brand, BorderLayout.WEST)
        themeButton = button("", ::toggleTheme).apply { preferredSize = Dimension(145, 38); toolTipText = "Переключить цветовую тему" }
        val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
            background = this@VaultWindow.background
            add(caption("ЗАЩИЩЁННОЕ ХРАНИЛИЩЕ  ●", 11, acid, true)); add(themeButton)
        }
        header.add(headerActions, BorderLayout.EAST)
        contentPane.add(header, BorderLayout.NORTH)

        val sidebar = stack().apply { preferredSize = Dimension(270, 0); border = EmptyBorder(22, 22, 22, 22) }
        sidebar.add(caption("01 / АККАУНТ", 11, violet, true)); sidebar.add(gap(18))
        sidebar.add(caption("Логин", 12, muted)); sidebar.add(gap(6)); sidebar.add(user); sidebar.add(gap(12))
        sidebar.add(caption("Пароль", 12, muted)); sidebar.add(gap(6)); sidebar.add(password); sidebar.add(gap(14))
        sidebar.add(leftButton("Войти") { login() }.apply { background = acid; foreground = this@VaultWindow.background }); sidebar.add(gap(8))
        sidebar.add(leftButton("Создать аккаунт") { register() }); sidebar.add(gap(8)); sidebar.add(leftButton("Выйти") { logout() })
        sidebar.add(gap(28)); sidebar.add(caption("02 / ДОСТУП", 11, violet, true)); sidebar.add(gap(14))
        sidebar.add(leftButton("Разблокировать") { unlock() }); sidebar.add(gap(8)); sidebar.add(leftButton("Заблокировать") { lock() })
        sidebar.add(Box.createVerticalGlue()); sidebar.add(gap(20))
        sidebar.add(caption("AES-256 / ЛОКАЛЬНОЕ ШИФРОВАНИЕ", 10, muted, true))

        val main = panel(background).apply { layout = BorderLayout(0, 16); border = EmptyBorder(0, 20, 0, 0) }
        val top = stack(background).apply { border = EmptyBorder(0, 0, 0, 0) }
        top.add(caption("ВАШЕ ПРОСТРАНСТВО / 01", 11, violet, true)); top.add(gap(8))
        top.add(caption("Документы", 34, foreground, true)); top.add(gap(6))
        top.add(caption("Всё важное — в одном защищённом месте.", 14, muted)); top.add(gap(20))
        top.add(caption("ПОИСК ПО НАЗВАНИЮ И ТЕКСТУ", 10, muted, true)); top.add(gap(8))
        val toolbar = JPanel(BorderLayout(10, 0)).apply { background = this@VaultWindow.background }
        toolbar.add(query, BorderLayout.CENTER)
        val searchActions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            background = this@VaultWindow.background; add(button("Найти") { search() }); add(button("Обновить") { refresh() })
            add(button("+ Добавить файл") { upload() }.apply { background = acid; foreground = this@VaultWindow.background })
        }
        toolbar.add(searchActions, BorderLayout.EAST); toolbar.alignmentX = java.awt.Component.LEFT_ALIGNMENT; toolbar.maximumSize = Dimension(Int.MAX_VALUE, 44); top.add(toolbar)
        main.add(top, BorderLayout.NORTH)

        list.background = surface; list.foreground = foreground; list.selectionBackground = Color(55, 56, 70)
        list.selectionForeground = acid; list.font = Font("Segoe UI", Font.PLAIN, 15); list.fixedCellHeight = 58
        list.border = EmptyBorder(8, 8, 8, 8)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                val id = value as? String ?: ""
                return super.getListCellRendererComponent(list, "  ◇   " + (documentNames[id] ?: id), index, selected, focus).apply {
                    border = EmptyBorder(8, 12, 8, 12); font = Font("Segoe UI", Font.PLAIN, 15)
                }
            }
        }
        val documents = JPanel(BorderLayout()).apply {
            background = surface; border = CompoundBorder(LineBorder(Color(51, 54, 65), 1, true), EmptyBorder(18, 18, 18, 18))
            add(caption("МОИ ДОКУМЕНТЫ", 11, violet, true).apply { border = EmptyBorder(0, 0, 12, 0) }, BorderLayout.NORTH)
            val documentContent = JPanel(java.awt.CardLayout()).apply {
                background = surface
                add(JScrollPane(list).apply { border = null; viewport.background = surface }, "list")
                add(JLabel("<html><center>Здесь появятся ваши документы<br><br>Войдите, разблокируйте хранилище и нажмите «Обновить»<br>или добавьте первый файл.</center></html>", SwingConstants.CENTER).apply {
                    foreground = muted; font = Font("Segoe UI", Font.PLAIN, 14)
                }, "empty")
            }
            fun showContent() { (documentContent.layout as java.awt.CardLayout).show(documentContent, if (model.isEmpty) "empty" else "list") }
            model.addListDataListener(object : javax.swing.event.ListDataListener {
                override fun intervalAdded(e: javax.swing.event.ListDataEvent) = showContent()
                override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = showContent()
                override fun contentsChanged(e: javax.swing.event.ListDataEvent) = showContent()
            })
            showContent(); add(documentContent, BorderLayout.CENTER)
        }
        main.add(documents, BorderLayout.CENTER)
        val tools = JPanel(java.awt.GridLayout(0, 3, 8, 8)).apply {
            background = this@VaultWindow.background
            add(button("Сохранить файл") { download() }); add(button("Распознать текст") { analyze() }); add(button("Сроки") { showExpiries() })
            add(button("Изменить срок") { editExpiry() }); add(button("Фоновые уведомления") { installReminders() })
        }
        main.add(tools, BorderLayout.SOUTH)
        val body = JPanel(BorderLayout()).apply { background = this@VaultWindow.background; border = EmptyBorder(0, 28, 0, 28); add(sidebar, BorderLayout.WEST); add(main, BorderLayout.CENTER) }
        contentPane.add(body, BorderLayout.CENTER)
        status.foreground = muted; status.font = Font("Segoe UI", Font.PLAIN, 12); status.border = EmptyBorder(16, 30, 20, 30)
        contentPane.add(status, BorderLayout.SOUTH)
        applyTheme()
        Timer(60_000) { notifyExpiries() }.start()
    }
    private fun toggleTheme() {
        lightTheme = !lightTheme
        try { themePrefs.putBoolean("lightTheme", lightTheme) } catch (_: SecurityException) { status.text = "Не удалось сохранить тему." }
        applyTheme()
    }
    private fun applyTheme() {
        val palette = mapOf(
            background to Color(244, 244, 249), surface to Color(255, 255, 255),
            surfaceAlt to Color(232, 231, 240), foreground to Color(28, 27, 36),
            muted to Color(88, 87, 103), violet to Color(92, 58, 153),
            Color(65, 68, 79) to Color(185, 183, 197), Color(51, 54, 65) to Color(218, 216, 227),
            Color(55, 56, 70) to Color(220, 213, 244)
        )
        fun mapped(color: Color?, text: Boolean = false): Color? {
            if (color == null || !lightTheme) return color
            if (text && color == acid) return Color(60, 93, 5)
            return palette[color] ?: color
        }
        fun recolor(component: java.awt.Component) {
            if (component is JComponent) {
                if (component.getClientProperty("dv.background") == null) component.putClientProperty("dv.background", component.background)
                if (component.getClientProperty("dv.foreground") == null) component.putClientProperty("dv.foreground", component.foreground)
                val initialBg = component.getClientProperty("dv.background") as? Color
                val initialFg = component.getClientProperty("dv.foreground") as? Color
                component.background = mapped(initialBg)
                component.foreground = if (component is JButton && initialBg == acid) Color(15, 17, 23) else mapped(initialFg, true)
                if (component is JTextField) {
                    component.caretColor = if (lightTheme) Color(43, 41, 55) else acid
                    component.border = CompoundBorder(LineBorder(if (lightTheme) Color(185, 183, 197) else Color(65, 68, 79), 1, true), EmptyBorder(10, 12, 10, 12))
                }
                if (component is JList<*>) {
                    component.selectionBackground = if (lightTheme) Color(220, 213, 244) else Color(55, 56, 70)
                    component.selectionForeground = if (lightTheme) Color(43, 33, 61) else acid
                }
                if (component is JPanel && component.border is CompoundBorder) {
                    component.border = CompoundBorder(LineBorder(if (lightTheme) Color(218, 216, 227) else Color(51, 54, 65), 1, true), EmptyBorder(18, 18, 18, 18))
                }
            }
            if (component is java.awt.Container) component.components.forEach(::recolor)
        }
        recolor(contentPane)
        themeButton.text = if (lightTheme) "Тёмная тема" else "Светлая тема"
        contentPane.repaint()
    }
    private fun button(label: String, action: () -> Unit): JButton = object : JButton(label) {
        override fun paintComponent(graphics: java.awt.Graphics) {
            val g = graphics.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val base = if (!isEnabled) background.darker() else if (model.isPressed) background.darker() else if (model.isRollover) background.brighter() else background
                g.color = base; g.fillRoundRect(0, 0, width, height, 12, 12)
            } finally { g.dispose() }
            super.paintComponent(graphics)
        }
    }.also { button ->
        button.background = surfaceAlt; button.foreground = foreground; button.font = Font("Segoe UI", Font.BOLD, 13)
        button.isFocusPainted = false; button.isBorderPainted = false; button.isContentAreaFilled = false; button.isOpaque = false
        button.border = EmptyBorder(10, 14, 10, 14); button.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        buttons.add(button); button.addActionListener { action() }
    }
    private fun recoveryPrompt(title: String): CharArray? {
        val input = JPasswordField(24)
        val result = JOptionPane.showConfirmDialog(this, input, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        return if (result == JOptionPane.OK_OPTION) input.password else null
    }
    private fun <T> runTask(message: String, action: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; buttons.forEach { it.isEnabled = false }; status.text = message
        object : SwingWorker<T, Unit>() {
            override fun doInBackground(): T = action()
            override fun done() {
                try { done(get()) } catch (e: Exception) {
                    val cause = e.cause ?: e
                    status.text = "Операция не выполнена"
                    val messageText = when (cause) {
                        is ApiException -> when (cause.status) { 401 -> "Неверный пароль или сессия завершена."; 409 -> "Такая запись уже существует."; 400 -> "Некорректные данные. Логин: 3–40 латинских символов, цифр или _.−; пароль: от 12 символов, до 72 байт UTF-8."; else -> cause.message }
                        is javax.crypto.AEADBadTagException -> "Неверный пароль восстановления или повреждены данные."
                        is java.net.ConnectException -> "Не удалось подключиться к серверу. Проверьте интернет и адрес сервера."
                        else -> cause.message ?: cause.javaClass.simpleName
                    }
                    JOptionPane.showMessageDialog(this@VaultWindow, messageText, "Ошибка", JOptionPane.ERROR_MESSAGE)
                } finally { busy = false; buttons.forEach { it.isEnabled = true } }
            }
        }.execute()
    }
    private fun register() {
        val name = user.text.trim(); val pass = String(password.password)
        val recovery = recoveryPrompt("Создайте отдельный пароль восстановления") ?: return
        if (recovery.size < 12) { recovery.fill('\u0000'); JOptionPane.showMessageDialog(this, "Пароль восстановления должен содержать не менее 12 символов."); return }
        val confirm = recoveryPrompt("Повторите пароль восстановления") ?: run { recovery.fill('\u0000'); return }
        val matches = recovery.contentEquals(confirm); confirm.fill('\u0000')
        if (!matches) { recovery.fill('\u0000'); JOptionPane.showMessageDialog(this, "Пароли восстановления не совпадают."); return }
        runTask("Создание аккаунта…", { api.register(name, pass, recovery) }) { status.text = "Аккаунт создан. Войдите и разблокируйте хранилище."; password.text = "" }
    }
    private fun login() {
        if (token != null) { JOptionPane.showMessageDialog(this, "Сначала выйдите из текущего аккаунта."); return }
        val name = user.text.trim(); val pass = String(password.password)
        runTask("Вход…", { api.login(name, pass) }) { token = it; accountIdentity = ROOT + "\u0000" + name.lowercase(java.util.Locale.ROOT); password.text = ""; model.clear(); status.text = "Вход выполнен. Разблокируйте хранилище." }
    }
    private fun unlock() {
        val current = token ?: run { JOptionPane.showMessageDialog(this, "Сначала войдите в аккаунт."); return }
        val recovery = recoveryPrompt("Введите пароль восстановления") ?: return
        runTask("Разблокировка…", { api.unlock(current, recovery) }) { key -> masterKey?.fill(0); masterKey = key; status.text = "Хранилище разблокировано. Нажмите «Обновить»." }
    }
    private fun lock() { masterKey?.fill(0); masterKey = null; recognized.clear(); expiryDates.clear(); documentNames.clear(); list.repaint(); status.text = "Хранилище заблокировано. Распознанный текст удалён из памяти." }
    private fun logout() {
        val current = token ?: return
        runTask("Выход…", { api.logout(current) }) { token = null; accountIdentity = null; lock(); model.clear(); status.text = "Вы вышли из аккаунта." }
    }
    private fun installReminders() {
        runTask("Настраиваем уведомления Windows…", { WindowsReminderTask.install() }) { created ->
            status.text = if (created) "Фоновые уведомления Windows включены." else "Задание DocumentVault уже существует."
        }
    }
    private fun refresh() {
        val current = token ?: run { JOptionPane.showMessageDialog(this, "Сначала войдите в аккаунт."); return }
        val key = masterKey?.copyOf(); val identity = accountIdentity
        runTask("Получение списка…", {
            try {
                val ids = api.list(current)
                val metadata = if (key == null) emptyMap() else ids.mapNotNull { id ->
                    api.metadata(current, id)?.let { encrypted ->
                        val plain = VaultCrypto.decrypt(key, encrypted, "metadata:$id".toByteArray())
                        try { id to json.readTree(plain) } finally { plain.fill(0) }
                    }
                }.toMap()
                var reminderError = false
                if (key != null && identity != null && System.getProperty("os.name").startsWith("Windows")) try {
                    val dates = metadata.mapNotNull { (id, data) -> data.path("expiry").asText("").takeIf { it.isNotEmpty() }?.let { id to java.time.LocalDate.parse(it) } }.toMap()
                    WindowsReminderStore().replaceAccount(identity, dates)
                } catch (_: Exception) { reminderError = true }
                Triple(ids, metadata, reminderError)
            } finally { key?.fill(0) }
        }) { (ids, metadata, reminderError) ->
            recognized.clear(); expiryDates.clear(); documentNames.clear()
            metadata.forEach { (id, data) ->
                data.path("text").asText("").takeIf { it.isNotEmpty() }?.let { recognized[id] = it }
                data.path("name").asText("").takeIf { it.isNotEmpty() }?.let { documentNames[id] = it }
                data.path("expiry").asText("").takeIf { it.isNotEmpty() }?.let { expiryDates[id] = java.time.LocalDate.parse(it) }
            }
            model.clear(); ids.forEach(model::addElement)
            status.text = if (reminderError) "Документы загружены, но расписание уведомлений не обновлено." else "Документов: ${ids.size}. Текстов для поиска: ${recognized.size}."
            notifyExpiries()
        }
    }
    private fun upload() {
        val current = token ?: run { JOptionPane.showMessageDialog(this, "Сначала войдите в аккаунт."); return }
        val key = masterKey?.copyOf() ?: run { JOptionPane.showMessageDialog(this, "Сначала разблокируйте хранилище."); return }
        val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Документы и изображения", "pdf", "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff") }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { key.fill(0); return }
        val path = chooser.selectedFile.toPath()
        runTask("Шифрование и загрузка…", {
            try {
                require(Files.size(path) <= MAX_FILE_SIZE) { "Размер файла превышает 15 МиБ." }
                val id = UUID.randomUUID().toString()
                val plaintext = DocumentPayload.encode(path.fileName.toString(), Files.readAllBytes(path))
                try { api.upload(current, id, VaultCrypto.encrypt(key, plaintext, "document:$id".toByteArray())) } finally { plaintext.fill(0) }
                val metadata = json.writeValueAsBytes(mapOf("name" to path.fileName.toString(), "text" to "", "expiry" to ""))
                val saved = try { api.saveMetadata(current, id, VaultCrypto.encrypt(key, metadata, "metadata:$id".toByteArray())); true }
                    catch (_: Exception) { false } finally { metadata.fill(0) }
                id to saved
            } finally { key.fill(0) }
        }) { (id, saved) ->
            documentNames[id] = path.fileName.toString(); model.addElement(id)
            status.text = if (saved) "Документ зашифрован и загружен." else "Документ загружен, но название не сохранено. Повторите распознавание."
            list.setSelectedValue(id, true)
            SwingUtilities.invokeLater { analyze() }
        }
    }
    private fun download() {
        val current = token ?: run { JOptionPane.showMessageDialog(this, "Сначала войдите в аккаунт."); return }
        val selected = list.selectedValue ?: run { JOptionPane.showMessageDialog(this, "Выберите документ."); return }
        val key = masterKey?.copyOf() ?: run { JOptionPane.showMessageDialog(this, "Сначала разблокируйте хранилище."); return }
        runTask("Загрузка и расшифровка…", {
            try {
                val plaintext = VaultCrypto.decrypt(key, api.download(current, selected), "document:$selected".toByteArray())
                try { DocumentPayload.decode(plaintext) } finally { plaintext.fill(0) }
            } finally { key.fill(0) }
        }) { document ->
            try {
                val chooser = JFileChooser().apply { selectedFile = java.io.File((documentNames[selected] ?: document.first).replace('\\', '/').substringAfterLast('/')) }
                if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    val target = chooser.selectedFile.toPath()
                    if (!Files.exists(target) || JOptionPane.showConfirmDialog(this, "Заменить существующий файл?", "Подтверждение", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        Files.write(target, document.second); status.text = "Файл сохранён: $target"
                    }
                }
            } finally { document.second.fill(0) }
        }
    }
    private fun analyze() {
        val current = token ?: run { JOptionPane.showMessageDialog(this, "Сначала войдите в аккаунт."); return }
        val id = list.selectedValue ?: run { JOptionPane.showMessageDialog(this, "Выберите документ."); return }
        val key = masterKey?.copyOf() ?: run { JOptionPane.showMessageDialog(this, "Сначала разблокируйте хранилище."); return }
        runTask("Локальное распознавание…", {
            try {
                val plaintext = VaultCrypto.decrypt(key, api.download(current, id), "document:$id".toByteArray())
                val document = try { DocumentPayload.decode(plaintext) } finally { plaintext.fill(0) }
                try { document.first to LocalOcr.recognize(document.first, document.second) } finally { document.second.fill(0) }
            } finally { key.fill(0) }
        }) { (name, text) ->
            documentNames[id] = name; list.repaint()
            recognized[id] = text
            val expiry = expiryDates[id] ?: DocumentDates.automaticExpiry(text)
                val saveKey = masterKey?.copyOf() ?: return@runTask
                val identity = accountIdentity
                SwingUtilities.invokeLater {
                    runTask("Сохранение зашифрованного текста…", {
                        try {
                            val existing = api.metadata(current, id)?.let { encrypted ->
                                val plain = VaultCrypto.decrypt(saveKey, encrypted, "metadata:$id".toByteArray())
                                try { json.readTree(plain) } finally { plain.fill(0) }
                            }
                            val data = if (existing is com.fasterxml.jackson.databind.node.ObjectNode) existing.deepCopy() else json.createObjectNode()
                            data.put("name", name); data.put("text", text); data.put("expiry", expiry?.toString() ?: "")
                            val bytes = json.writeValueAsBytes(data)
                            try { api.saveMetadata(current, id, VaultCrypto.encrypt(saveKey, bytes, "metadata:$id".toByteArray())) } finally { bytes.fill(0) }
                            if (identity != null && System.getProperty("os.name").startsWith("Windows")) runCatching { WindowsReminderStore().put(identity, id, expiry) }.isSuccess else true
                        } finally { saveKey.fill(0) }
                    }) { reminderSaved -> if (expiry != null) expiryDates[id] = expiry else expiryDates.remove(id); status.text = if (reminderSaved) (if (expiry == null) "Текст сохранён. Срок действия автоматически не найден." else "Текст сохранён. Срок: $expiry. Проверьте его в документе.") else "Документ сохранён, но расписание уведомлений не обновлено." }
                }
        }
    }
    private fun search() {
        if (masterKey == null) { JOptionPane.showMessageDialog(this, "Сначала разблокируйте хранилище."); return }
        val text = query.text.trim()
        if (text.isEmpty()) { JOptionPane.showMessageDialog(this, "Введите поисковый запрос."); return }
        val matches = (recognized.keys + documentNames.keys).filter { id -> OcrSearch.matches("${documentNames[id] ?: ""} ${recognized[id] ?: ""}", text) }
        model.clear(); matches.forEach(model::addElement)
        status.text = "Найдено: ${matches.size}. Поиск выполнен по ${recognized.size} распознанным документам."
    }
    private fun showExpiries() {
        if (masterKey == null) { JOptionPane.showMessageDialog(this, "Сначала разблокируйте хранилище."); return }
        val lines = expiryDates.entries.sortedBy { it.value }.joinToString("\n") { "${it.value} — ${documentNames[it.key] ?: it.key}${if (it.value.isBefore(java.time.LocalDate.now())) " (истёк)" else ""}" }
        JOptionPane.showMessageDialog(this, lines.ifEmpty { "Сроки не найдены. Обновите список после разблокировки." }, "Сроки документов", JOptionPane.INFORMATION_MESSAGE)
    }
    private fun editExpiry() {
        val current = token ?: run { JOptionPane.showMessageDialog(this, "Сначала войдите в аккаунт."); return }
        val id = list.selectedValue ?: run { JOptionPane.showMessageDialog(this, "Выберите документ."); return }
        val key = masterKey?.copyOf() ?: run { JOptionPane.showMessageDialog(this, "Сначала разблокируйте хранилище."); return }
        val input = JOptionPane.showInputDialog(this, "Дата окончания: ГГГГ-ММ-ДД. Пустое поле удаляет срок.", expiryDates[id]?.toString() ?: "")
        if (input == null) { key.fill(0); return }
        val date = if (input.isBlank()) null else try { java.time.LocalDate.parse(input.trim()) } catch (_: Exception) {
            key.fill(0); JOptionPane.showMessageDialog(this, "Неверный формат даты."); return
        }
        val identity = accountIdentity
        runTask("Сохранение срока…", {
            try {
                val existing = api.metadata(current, id)?.let { encrypted ->
                    val plain = VaultCrypto.decrypt(key, encrypted, "metadata:$id".toByteArray())
                    try { json.readTree(plain) } finally { plain.fill(0) }
                }
                val updated = if (existing is com.fasterxml.jackson.databind.node.ObjectNode) existing.deepCopy() else json.createObjectNode()
                updated.put("expiry", date?.toString() ?: "")
                val data = json.writeValueAsBytes(updated)
                try { api.saveMetadata(current, id, VaultCrypto.encrypt(key, data, "metadata:$id".toByteArray())) } finally { data.fill(0) }
                if (identity != null && System.getProperty("os.name").startsWith("Windows")) runCatching { WindowsReminderStore().put(identity, id, date) }.isSuccess else true
            } finally { key.fill(0) }
        }) { reminderSaved -> if (date == null) expiryDates.remove(id) else expiryDates[id] = date; reminderDay = null; status.text = if (reminderSaved) "Срок сохранён." else "Срок сохранён, но расписание уведомлений не обновлено." }
    }
    private fun notifyExpiries() {
        if (masterKey == null || busy) return
        val today = java.time.LocalDate.now()
        if (reminderDay == today) return
        val count = expiryDates.values.count { it.isBefore(today) || DocumentDates.dueWithin(it, today) }
        if (count == 0) return
        reminderDay = today
        JOptionPane.showMessageDialog(this, "Срок действия документов истёк или скоро истечёт. Откройте раздел «Сроки».", "Напоминание", JOptionPane.INFORMATION_MESSAGE)
    }
}

object DocumentPayload {
    fun encode(name: String, bytes: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size in 1..4096) { "Некорректное имя файла." }
        return java.nio.ByteBuffer.allocate(4 + nameBytes.size + bytes.size).putInt(nameBytes.size).put(nameBytes).put(bytes).array()
    }
    fun decode(payload: ByteArray): Pair<String, ByteArray> {
        require(payload.size >= 5) { "Повреждён документ." }
        val buffer = java.nio.ByteBuffer.wrap(payload)
        val length = buffer.int
        require(length in 1..4096 && length <= buffer.remaining()) { "Повреждён документ." }
        val name = ByteArray(length).also(buffer::get).toString(Charsets.UTF_8)
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').takeUnless { it == "." || it == ".." || it.isBlank() } ?: "document"
        return safeName to ByteArray(buffer.remaining()).also(buffer::get)
    }
}

fun main() { SwingUtilities.invokeLater { VaultWindow().isVisible = true } }
