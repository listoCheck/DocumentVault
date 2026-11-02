package ru.documentvault.desktop

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsReminderStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `encrypts dates and claims one notification per day`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val today = LocalDate.of(2030, 1, 1)
        val store = WindowsReminderStore(directory)
        store.replaceAccount("synthetic-account", mapOf("synthetic-document" to today.plusDays(5)))
        val persisted = Files.readAllBytes(directory.resolve("reminders.bin")).toString(Charsets.ISO_8859_1)
        assertFalse(persisted.contains("synthetic-document")); assertFalse(persisted.contains("2030-01-06"))
        assertEquals(1, WindowsReminderStore(directory).dueCount(today))
        assertTrue(store.claimDue(today)); assertFalse(store.claimDue(today))
        store.put("synthetic-account", "synthetic-document", null)
        assertEquals(0, store.dueCount(today))
    }

    @Test fun `task XML escapes names and runs a dedicated reminder worker`() {
        val xml = WindowsReminderTask.taskXml(Path.of("C:/A & B/javaw.exe"), "C:\\A & B\\lib\\*", "DOMAIN\\User")
        assertTrue(xml.contains("A &amp; B")); assertTrue(xml.contains("ru.documentvault.desktop.ReminderRunner")); assertTrue(xml.contains("InteractiveToken"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    }

    @Test fun `creates and removes an isolated scheduler test task`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getenv("DV_TEST_SCHEDULER") == "1")
        val name = "DocumentVault-Verification-${java.util.UUID.randomUUID()}"
        val store = WindowsReminderStore(directory.resolve("isolated")); val today = LocalDate.now()
        store.replaceAccount("synthetic", mapOf("test" to today.plusDays(1)))
        var created = false
        try {
            created = WindowsReminderTask.install(name, directory.resolve("isolated"))
            assertTrue(created)
            val query = ProcessBuilder("schtasks.exe", "/Query", "/TN", name).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(query.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, query.exitValue())
            val run = ProcessBuilder("schtasks.exe", "/Run", "/TN", name).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(run.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, run.exitValue())
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            while (!store.isNotified(today) && System.nanoTime() < deadline) Thread.sleep(200)
            assertTrue(store.isNotified(today), "Scheduled reminder worker did not process the test date")
        } finally {
            if (created) {
                val remove = ProcessBuilder("schtasks.exe", "/Delete", "/TN", name, "/F").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                assertTrue(remove.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, remove.exitValue())
            }
        }
    }

    @Test fun `background worker reads protected schedule and claims due reminder`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getenv("DV_TEST_TRAY") == "1" && java.awt.SystemTray.isSupported())
        val appDirectory = directory.resolve("DocumentVault")
        val store = WindowsReminderStore(appDirectory); val today = LocalDate.now()
        store.replaceAccount("synthetic", mapOf("synthetic-document" to today.plusDays(1)))
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        val lib = Path.of("build", "install", "desktopApp", "lib").toAbsolutePath().toString() + java.io.File.separator + "*"
        val builder = ProcessBuilder(javaExecutable, "-cp", lib, "ru.documentvault.desktop.ReminderRunner").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment()["LOCALAPPDATA"] = directory.toString()
        val worker = builder.start()
        assertTrue(worker.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, worker.exitValue())
        assertFalse(store.claimDue(today))
    }
}
