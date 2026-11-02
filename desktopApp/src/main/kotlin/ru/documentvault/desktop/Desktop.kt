package ru.documentvault.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import ru.documentvault.core.VaultCrypto
import ru.documentvault.core.DocumentDates
import ru.documentvault.core.OcrSearch
import java.awt.BorderLayout
import java.awt.FlowLayout
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

private val ROOT = System.getenv("VAULT_SERVER_URL") ?: "http://127.0.0.1:7070"
private const val MAX_FILE_SIZE = 15L * 1024 * 1024
private val json = ObjectMapper()
private val b64 = Base64.getEncoder()
private val b64decode = Base64.getDecoder()

class ApiException(val status: Int) : RuntimeException("Ошибка сервера: HTTP $status")

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
    fun logout(token: String) { request("DELETE", "/v1/sessions/current", token = token) }
}

class VaultWindow : JFrame("DocumentVault — локальный прототип") {
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

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(820, 480)
        setLocationRelativeTo(null)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                val id = value as? String ?: ""
                return super.getListCellRendererComponent(list, documentNames[id] ?: id, index, selected, focus)
            }
        }
        val auth = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Логин:")); add(user); add(JLabel("Пароль:")); add(password)
            add(button("Регистрация") { register() }); add(button("Войти") { login() }); add(button("Выйти") { logout() })
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(button("Разблокировать") { unlock() }); add(button("Заблокировать") { lock() })
            add(button("Обновить") { refresh() }); add(button("Добавить файл") { upload() }); add(button("Сохранить файл") { download() })
        }
        val search = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(button("Распознать выбранный") { analyze() }); add(JLabel("Поиск:")); add(query)
            add(button("Найти") { search() })
            add(button("Сроки") { showExpiries() })
            add(button("Изменить срок") { editExpiry() })
        }
        val reminders = JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(button("Включить фоновые уведомления") { installReminders() }) }
        val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(auth); add(actions); add(search); add(reminders) }
        contentPane.add(top, BorderLayout.NORTH)
        contentPane.add(JScrollPane(list), BorderLayout.CENTER)
        contentPane.add(status, BorderLayout.SOUTH)
        Timer(60_000) { notifyExpiries() }.start()
    }
    private fun button(label: String, action: () -> Unit): JButton = JButton(label).also { button ->
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
                        is java.net.ConnectException -> "Не удалось подключиться к серверу. Запустите :server:run."
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
                val chooser = JFileChooser().apply { selectedFile = java.io.File(document.first) }
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
            val dates = DocumentDates.find(text)
            val suggestions = if (dates.isEmpty()) "Даты не найдены." else dates.joinToString("\n") {
                "${it.date} ${if (it.likelyExpiry) "— возможный срок действия" else "— дата"}: ${it.excerpt}"
            }
            val content = JTextArea("Даты — проверьте вручную:\n$suggestions\n\nРаспознанный текст:\n$text", 25, 70).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; caretPosition = 0 }
            JOptionPane.showMessageDialog(this, JScrollPane(content), "Результат распознавания", JOptionPane.INFORMATION_MESSAGE)
            val proposed = expiryDates[id]?.toString() ?: dates.firstOrNull { it.likelyExpiry }?.date?.toString() ?: ""
            val entered = JOptionPane.showInputDialog(this, "Подтвердите дату окончания в формате ГГГГ-ММ-ДД. Оставьте пустым, если срока нет.", proposed)
            if (entered != null) {
                val expiry = if (entered.isBlank()) null else try { java.time.LocalDate.parse(entered.trim()) } catch (_: Exception) {
                    JOptionPane.showMessageDialog(this, "Неверная дата. Распознайте документ ещё раз для сохранения."); return@runTask
                }
                val saveKey = masterKey?.copyOf() ?: return@runTask
                val identity = accountIdentity
                SwingUtilities.invokeLater {
                    runTask("Сохранение зашифрованного текста…", {
                        try {
                            val bytes = json.writeValueAsBytes(mapOf("name" to name, "text" to text, "expiry" to (expiry?.toString() ?: "")))
                            try { api.saveMetadata(current, id, VaultCrypto.encrypt(saveKey, bytes, "metadata:$id".toByteArray())) } finally { bytes.fill(0) }
                            if (identity != null && System.getProperty("os.name").startsWith("Windows")) runCatching { WindowsReminderStore().put(identity, id, expiry) }.isSuccess else true
                        } finally { saveKey.fill(0) }
                    }) { reminderSaved -> if (expiry != null) expiryDates[id] = expiry else expiryDates.remove(id); status.text = if (reminderSaved) "Распознанный текст и срок сохранены в зашифрованном виде." else "Документ сохранён, но расписание уведомлений не обновлено." }
                }
            } else { status.text = "Текст доступен для поиска до блокировки. Для сохранения повторите распознавание." }
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
        JOptionPane.showMessageDialog(this, lines.ifEmpty { "Подтверждённые сроки не найдены. Обновите список после разблокировки." }, "Сроки документов", JOptionPane.INFORMATION_MESSAGE)
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
                val data = json.writeValueAsBytes(mapOf("name" to (existing?.path("name")?.asText("") ?: ""), "text" to (existing?.path("text")?.asText("") ?: ""), "expiry" to (date?.toString() ?: "")))
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
