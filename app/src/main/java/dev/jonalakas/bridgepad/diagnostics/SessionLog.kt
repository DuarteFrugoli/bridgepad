package dev.jonalakas.bridgepad.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionLog {
    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun record(category: String, message: String) {
        val safeCategory = category.filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "APP" }
        entries.addLast("${dateFormat.format(Date())} [$safeCategory] $message")
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
