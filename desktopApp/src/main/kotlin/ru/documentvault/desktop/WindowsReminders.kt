package ru.documentvault.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.jna.platform.win32.Crypt32Util
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64

/** DPAPI protects the reminder dates with the current Windows account. */
class WindowsReminderStore(private val directory: Path = defaultDirectory()) {
    companion object {
        fun defaultDirectory(): Path = System.getProperty("documentvault.reminders.dir")?.let(Path::of)
            ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "DocumentVault")
        private val json = ObjectMapper()
    }
    private val dataPath get() = directory.resolve("reminders.bin")

    private fun <T> locked(block: (ObjectNode) -> T): T {
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("reminders.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val root = if (Files.exists(dataPath)) {
                    val plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(dataPath))
                    try { json.readTree(plain) as ObjectNode } finally { plain.fill(0) }
                } else json.createObjectNode().apply { set<ObjectNode>("accounts", json.createObjectNode()) }
                return block(root)
            }
        }
    }
    private fun save(root: ObjectNode) {
        val plain = json.writeValueAsBytes(root)
        val encrypted = try { Crypt32Util.cryptProtectData(plain) } finally { plain.fill(0) }
        val temporary = Files.createTempFile(directory, "reminders-", ".tmp")
        try {
            Files.write(temporary, encrypted)
            try { Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun account(identity: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))

    fun replaceAccount(identity: String, dates: Map<String, LocalDate>) = locked { root ->
        val accounts = root.withObject("/accounts")
        val values = json.createObjectNode(); dates.forEach { (id, date) -> values.put(id, date.toString()) }
        accounts.set<ObjectNode>(account(identity), values); save(root)
    }
    fun put(identity: String, id: String, date: LocalDate?) = locked { root ->
        val accounts = root.withObject("/accounts")
        val values = (accounts.get(account(identity)) as? ObjectNode) ?: json.createObjectNode().also { accounts.set<ObjectNode>(account(identity), it) }
        if (date == null) values.remove(id) else values.put(id, date.toString())
        save(root)
    }
    fun dueCount(today: LocalDate): Int = locked { root ->
        root.path("accounts").elements().asSequence().sumOf { account ->
            account.elements().asSequence().count { !LocalDate.parse(it.asText()).isAfter(today.plusDays(30)) }
        }
    }
    fun claimDue(today: LocalDate): Boolean = locked { root ->
        val count = root.path("accounts").elements().asSequence().sumOf { account ->
            account.elements().asSequence().count { !LocalDate.parse(it.asText()).isAfter(today.plusDays(30)) }
        }
        if (count == 0 || root.path("lastNotification").asText() == today.toString()) return@locked false
        root.put("lastNotification", today.toString()); save(root); true
    }
    internal fun isNotified(today: LocalDate): Boolean = locked { root -> root.path("lastNotification").asText() == today.toString() }
}

/** Invoked by Windows Task Scheduler in the interactive user session. */
object ReminderRunner {
    @JvmStatic fun main(args: Array<String>) {
        if (!System.getProperty("os.name").startsWith("Windows") || !SystemTray.isSupported()) return
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics(); graphics.color = Color(22, 97, 81); graphics.fillRoundRect(0, 0, 32, 32, 9, 9)
        graphics.color = Color.WHITE; graphics.drawString("D", 11, 22); graphics.dispose()
        val tray = SystemTray.getSystemTray(); val icon = TrayIcon(image, "DocumentVault").apply { isImageAutoSize = true }
        try { tray.add(icon); if (WindowsReminderStore().claimDue(LocalDate.now())) { icon.displayMessage("DocumentVault", "Проверьте сроки документов", TrayIcon.MessageType.INFO); Thread.sleep(8000) } }
        finally { tray.remove(icon); image.flush() }
        kotlin.system.exitProcess(0)
    }
}

object WindowsReminderTask {
    const val TASK_NAME = "DocumentVault-Reminders"
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    internal fun taskXml(javaw: Path, classpath: String, user: String, testDirectory: Path? = null): String = """<?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo><Description>DocumentVault: локальная проверка сроков документов</Description></RegistrationInfo>
          <Triggers>
            <LogonTrigger><Enabled>true</Enabled><UserId>${xmlEscape(user)}</UserId></LogonTrigger>
            <CalendarTrigger><StartBoundary>2026-01-01T09:00:00</StartBoundary><Enabled>true</Enabled><ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay></CalendarTrigger>
          </Triggers>
          <Principals><Principal id="User"><UserId>${xmlEscape(user)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
          <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><StartWhenAvailable>true</StartWhenAvailable><Enabled>true</Enabled><ExecutionTimeLimit>PT2M</ExecutionTimeLimit></Settings>
          <Actions Context="User"><Exec><Command>${xmlEscape(javaw.toString())}</Command><Arguments>${testDirectory?.let { "-Ddocumentvault.reminders.dir=&quot;${xmlEscape(it.toString())}&quot; " } ?: ""}-cp &quot;${xmlEscape(classpath)}&quot; ru.documentvault.desktop.ReminderRunner</Arguments></Exec></Actions>
        </Task>""".trimIndent()

    private fun distributionLib(): Path {
        val location = Path.of(ReminderRunner::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath()
        if (Files.isRegularFile(location) && location.parent.fileName.toString() == "lib") return location.parent
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return listOf(cwd.resolve("build/install/desktopApp/lib"), cwd.resolve("desktopApp/build/install/desktopApp/lib"))
            .firstOrNull { Files.isDirectory(it) && Files.exists(it.resolve("desktopApp.jar")) }
            ?: error("Сначала соберите установленную версию: gradlew.bat :desktopApp:installDist")
    }
    private fun execute(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); error("Планировщик Windows не отвечает") }
        return process.exitValue() to process.inputStream.bufferedReader(java.nio.charset.Charset.forName("Cp866")).use { it.readText().trim() }
    }
    fun install(taskName: String = TASK_NAME, testDirectory: Path? = null): Boolean {
        require(System.getProperty("os.name").startsWith("Windows")) { "Поддерживается только Windows" }
        if (execute("schtasks.exe", "/Query", "/TN", taskName).first == 0) return false
        val javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe")
        require(Files.isRegularFile(javaw)) { "Не найден javaw.exe" }
        val user = listOfNotNull(System.getenv("USERDOMAIN")?.takeIf { it.isNotBlank() }, System.getProperty("user.name")).joinToString("\\")
        val directory = WindowsReminderStore.defaultDirectory(); Files.createDirectories(directory)
        val xml = Files.createTempFile(directory, "task-", ".xml")
        try {
            val xmlBytes = taskXml(javaw, distributionLib().toString() + java.io.File.separator + "*", user, testDirectory).toByteArray(Charsets.UTF_16LE)
            Files.write(xml, byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xmlBytes)
            val result = execute("schtasks.exe", "/Create", "/TN", taskName, "/XML", xml.toString())
            check(result.first == 0) { "Не удалось создать задание Windows: ${result.second}" }
        } finally { Files.deleteIfExists(xml) }
        return true
    }
}

/** Setup entry point for the installed desktop distribution. */
object ReminderSetup {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty()) { "Не нужны аргументы" }
        println(if (WindowsReminderTask.install()) "Задание фоновых напоминаний создано." else "Задание с таким именем уже существует; оно не изменено.")
    }
}
