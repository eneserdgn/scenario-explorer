package com.scenarioexplorer.parser

object ErrorParser {

    data class ParsedError(
        val summary: String,     // kısa, okunabilir özet (1-2 satır)
        val groupingKey: String  // normalize edilmiş gruplama anahtarı
    )

    // Noise satırları — bu prefix'lerle başlayan satırlar atlanır
    private val NOISE_PREFIXES = listOf(
        "Build info:",
        "System info:",
        "Driver info:",
        "Command:",
        "Capabilities ",
        "Session ID:",
        "For documentation on this error",
        "For documentation, read documentation",
    )

    fun parse(raw: String): ParsedError {
        val lines = raw.lines()

        // 1. Ana exception satırı: stack trace olmayan ilk anlamlı satır
        val mainLine = lines.firstOrNull { line ->
            val t = line.trimStart()
            t.isNotBlank() && !t.startsWith("at ") && !t.startsWith("... ")
        } ?: raw.take(200)

        // 2. "Caused by:" satırlarının ilki
        val causedByLine = lines.firstOrNull { it.trimStart().startsWith("Caused by:") }
            ?.trimStart()
            ?.removePrefix("Caused by:")
            ?.trim()

        val mainShort = shortenExceptionLine(mainLine)
        val causedShort = causedByLine
            ?.let { shortenExceptionLine(it) }
            ?.takeIf { it != mainShort }

        val summary = buildString {
            append(mainShort)
            if (causedShort != null) {
                append("\nCaused by: $causedShort")
            }
        }

        return ParsedError(summary = summary, groupingKey = normalize(summary))
    }

    /**
     * "org.openqa.selenium.TimeoutException: Expected condition failed: waiting for … (tried for 30 second(s)…)"
     *   → "TimeoutException: waiting for presence of element located by: By.xpath: …"
     *
     * - Paket prefix'ini kaldırır
     * - "Expected condition failed:" önemsiz prefix'ini siler
     * - "(tried for N second(s)…)" gibi timeout boilerplate'i siler
     * - Mesajı 150 karaktere kırpar
     */
    private fun shortenExceptionLine(line: String): String {
        val trimmed = line.trim()
        val colonIdx = trimmed.indexOf(": ")
        if (colonIdx < 0) return trimmed

        val exPart = trimmed.substring(0, colonIdx).trim()
        val msgPart = trimmed.substring(colonIdx + 2).trim()

        if (NOISE_PREFIXES.any { msgPart.startsWith(it) || exPart.startsWith(it) }) return ""

        val className = exPart.substringAfterLast(".")

        // "Expected condition failed: " önemsiz prefix'ini at
        val cleanMsg = msgPart
            .removePrefix("Expected condition failed: ")
            // "(tried for N second(s) with N milliseconds interval)" suffix'ini kaldır
            // second(s) içindeki parantez nedeniyle [^)]* yerine [\s\S]*? kullanılıyor
            .replace(Regex("\\s*\\(tried for \\d+[\\s\\S]*?interval\\)\\s*$"), "")
            .trim()

        val shortMsg = if (cleanMsg.length > 150) "${cleanMsg.take(150)}…" else cleanMsg

        return "$className: $shortMsg"
    }

    /**
     * Aynı hatanın farklı koşumlarda değişen dinamik kısımlarını normalize eder:
     * UUID'ler, zaman damgalı path'ler, satır numaraları.
     */
    private fun normalize(text: String): String = text
        // UUID (session ID vb.)
        .replace(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            "{uuid}"
        )
        // Timestamp'li path parçaları: _2026-05-09T14-08-28_T3
        .replace(Regex("_\\d{4}-\\d{2}-\\d{2}T\\d{2}[-:]\\d{2}[-:]\\d{2}[^/\\s]*"), "_{ts}")
        // /tmp/xyz-run_... gibi geçici dizinler
        .replace(Regex("/tmp/[^/\\s]+"), "/tmp/{dir}")
        // Java satır numaraları: (Foo.java:123) → (Foo.java)
        .replace(Regex("\\(([A-Za-z]+\\.java):\\d+\\)"), "($1)")
        .trim()
}
