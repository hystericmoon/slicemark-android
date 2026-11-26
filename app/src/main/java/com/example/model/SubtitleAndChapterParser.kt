package com.example.model

object SubtitleAndChapterParser {

    private val timeRegex = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?[.,](\d{1,3})""")
    private val chapterLineRegex = Regex("""^\s*(?:[-•*]\s*)?(\d{1,2}:\d{2}(?::\d{2})?)\s*[-–—:|]?\s*(.+?)\s*$""")

    fun parseSubtitles(raw: String): List<SubtitleCue> {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()
        var idx = 1

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines.first().startsWith("WEBVTT", ignoreCase = true) ||
                lines.first().startsWith("NOTE", ignoreCase = true)
            ) continue

            val arrowLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (arrowLineIndex == -1) continue

            val timingLine = lines[arrowLineIndex]
            val parts = timingLine.split("-->")
            if (parts.size < 2) continue

            val startMs = parseSubtitleTimestampMs(parts[0].trim()) ?: continue
            val endMs = parseSubtitleTimestampMs(parts[1].trim().substringBefore(" ")) ?: continue
            if (endMs <= startMs) continue

            val text = lines.drop(arrowLineIndex + 1)
                .joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (text.isNotEmpty()) {
                cues.add(SubtitleCue(index = idx++, startMs = startMs, endMs = endMs, text = text))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseSubtitleTimestampMs(token: String): Long? {
        val m = timeRegex.find(token) ?: return null
        val p1 = m.groupValues[1].toLongOrNull() ?: return null
        val p2 = m.groupValues[2].toLongOrNull() ?: return null
        val p3Str = m.groupValues[3]
        val msStr = m.groupValues[4].padEnd(3, '0').take(3)
        val ms = msStr.toLongOrNull() ?: 0L
        return if (p3Str.isNotEmpty()) {
            val p3 = p3Str.toLongOrNull() ?: 0L
            ((p1 * 3600L) + (p2 * 60L) + p3) * 1000L + ms
        } else {
            ((p1 * 60L) + p2) * 1000L + ms
        }
    }

    fun activeCueAt(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        return cues.firstOrNull { positionMs in it.startMs..it.endMs }
    }

    fun sliceAndRetimeSrtForClip(
        cues: List<SubtitleCue>,
        clipStartMs: Long,
        clipEndMs: Long,
        speed: Float = 1f,
    ): String {
        val safeSpeed = speed.coerceIn(0.5f, 2.0f)
        val sb = StringBuilder()
        var counter = 1
        for (cue in cues) {
            if (cue.endMs <= clipStartMs || cue.startMs >= clipEndMs) continue
            val localStart = ((cue.startMs.coerceAtLeast(clipStartMs) - clipStartMs) / safeSpeed).toLong()
            val localEnd = ((cue.endMs.coerceAtMost(clipEndMs) - clipStartMs) / safeSpeed).toLong()
            if (localEnd <= localStart) continue
            sb.append(counter++).append('\n')
            sb.append(formatSrtTimestamp(localStart))
                .append(" --> ")
                .append(formatSrtTimestamp(localEnd))
                .append('\n')
            sb.append(cue.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val mins = (safe % 3_600_000L) / 60_000L
        val secs = (safe % 60_000L) / 1000L
        val millis = safe % 1000L
        return String.format("%02d:%02d:%02d,%03d", hours, mins, secs, millis)
    }

    fun parseChapters(raw: String, totalDurationMs: Long): List<ChapterMark> {
        val marks = mutableListOf<ChapterMark>()
        for (line in raw.lines()) {
            val match = chapterLineRegex.find(line) ?: continue
            val ts = parseChapterTimestampMs(match.groupValues[1]) ?: continue
            val title = match.groupValues[2].trim().ifEmpty { "Chapter ${marks.size + 1}" }
            if (ts < totalDurationMs) {
                marks.add(ChapterMark(startMs = ts, title = title))
            }
        }
        return marks.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    private fun parseChapterTimestampMs(token: String): Long? {
        val parts = token.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> null
        }
    }

    fun exportChaptersText(clips: List<ClipSegment>): String {
        return clips.mapIndexed { idx, clip ->
            val title = clip.chapterTitle?.takeIf { it.isNotBlank() }
                ?: clip.label.ifBlank { "Part ${idx + 1}" }
            "${formatTimecode(clip.startMs, includeCentiseconds = false)} $title"
        }.joinToString("\n")
    }
}
