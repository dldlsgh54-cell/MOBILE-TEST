package com.example.shortsauto.automation

object ClipboardPromptParser {
    fun parse(raw: String): List<String> {
        val normalized = raw.replace("\r", "").trim()
        if (normalized.isBlank()) return emptyList()

        val labeled = parseLabeledBlocks(normalized)

        if (labeled.isNotEmpty()) return labeled.take(6)

        val paragraphSplit = normalized
            .split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (paragraphSplit.size >= 2) return paragraphSplit.take(6)

        return normalized.lines()
            .map { it.trim().trimStart('-', '*', ' ') }
            .filter { it.length >= 8 }
            .take(6)
    }

    fun parseScreenText(raw: String): List<String> {
        val labeled = parseLabeledBlocks(raw.replace("\r", "").trim())
        if (labeled.size >= 6) return labeled.take(6)

        val normalized = raw.replace("\r", "").trim()
        if (normalized.isBlank()) return emptyList()

        val mergedLines = mergeWrappedLines(normalized.lines())
        return mergedLines
            .asSequence()
            .map { it.trim().trim('"', '\'', '`', '-', '*', ' ') }
            .filter { it.length >= 80 }
            .filter { englishRatio(it) >= 0.65f }
            .filterNot { isChatGptUiNoise(it) }
            .filter { promptScore(it) >= 2 }
            .distinctBy { it.take(120).lowercase() }
            .take(6)
            .toList()
    }

    private fun parseLabeledBlocks(text: String): List<String> {
        val labelPattern = Regex(
            """^(?:#{1,6}\s*)?(?:프롬프트|prompt|cut)?\s*([1-6])\s*(?:[\).:\-]|번)?\s*(.*)$""",
            RegexOption.IGNORE_CASE
        )
        val prompts = mutableListOf<String>()
        val current = StringBuilder()
        var collecting = false

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotBlank()) prompts += value
            current.clear()
            collecting = false
        }

        text.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                if (current.isNotBlank()) current.append('\n')
                return@forEach
            }
            val match = labelPattern.find(line)
            val isLabel = match != null &&
                (line.contains("프롬프트", ignoreCase = true) ||
                    line.contains("prompt", ignoreCase = true) ||
                    line.contains("cut", ignoreCase = true) ||
                    Regex("""^[1-6]\s*[\).:\-]""").containsMatchIn(line))
            if (isLabel) {
                flush()
                collecting = true
                val inline = match?.groupValues?.getOrNull(2).orEmpty().trim()
                if (inline.isNotBlank()) current.append(inline)
            } else if (collecting) {
                if (current.isNotEmpty() && current[current.length - 1] != '\n') current.append('\n')
                current.append(line)
            }
        }
        flush()
        return prompts.filter { it.length >= 8 }.take(6)
    }

    private fun mergeWrappedLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val value = current.toString().trim()
            if (value.isNotBlank()) result += value
            current.clear()
        }

        lines.map { it.trim() }.forEach { line ->
            if (line.isBlank()) {
                flush()
                return@forEach
            }
            val looksLikeNewPrompt = Regex("""^(?:프롬프트|prompt|cut)?\s*[1-6]\s*[\).:\-번]""", RegexOption.IGNORE_CASE)
                .containsMatchIn(line)
            if (looksLikeNewPrompt) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            if (line.endsWith(".") && current.length > 120) flush()
        }
        flush()
        return result
    }

    private fun englishRatio(value: String): Float {
        val letters = value.count { it.isLetter() }
        if (letters == 0) return 0f
        val asciiLetters = value.count { it in 'A'..'Z' || it in 'a'..'z' }
        return asciiLetters.toFloat() / letters
    }

    private fun promptScore(value: String): Int {
        val lower = value.lowercase()
        val keywords = listOf(
            "image", "cinematic", "portrait", "photo", "photorealistic", "illustration",
            "lighting", "composition", "background", "style", "camera", "lens",
            "ultra", "detailed", "high resolution", "vertical", "9:16", "shorts"
        )
        return keywords.count { lower.contains(it) } * 10 + (value.length / 40).coerceAtMost(20)
    }

    private fun isChatGptUiNoise(value: String): Boolean {
        val lower = value.lowercase()
        val noise = listOf(
            "chatgpt", "message chatgpt", "regenerate", "copy", "share",
            "disclaimer", "terms", "privacy", "log in", "sign up", "new chat",
            "stop generating", "send message"
        )
        return noise.any { lower == it || lower.startsWith("$it ") }
    }
}
