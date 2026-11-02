package ru.documentvault.server

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

object DatabaseBackup {
    fun create(source: Path, destination: Path) {
        val from = source.toAbsolutePath().normalize()
        val to = destination.toAbsolutePath().normalize()
        require(Files.isRegularFile(from)) { "Source database does not exist" }
        require(!Files.exists(to)) { "Destination already exists" }
        require(Files.isDirectory(to.parent)) { "Destination directory does not exist" }
        DriverManager.getConnection("jdbc:sqlite:$from").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            connection.prepareStatement("VACUUM INTO ?").use { statement -> statement.setString(1, to.toString()); statement.execute() }
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: backup <new-destination-file>" }
    val source = Path.of(System.getenv("VAULT_DATABASE") ?: "documentvault.db")
    DatabaseBackup.create(source, Path.of(args[0]))
    println("Backup created: ${Path.of(args[0]).toAbsolutePath().normalize()}")
}
