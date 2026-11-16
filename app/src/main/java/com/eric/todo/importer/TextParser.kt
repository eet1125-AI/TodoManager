package com.eric.todo.importer

import com.eric.todo.data.BUSINESS
import com.eric.todo.data.PERSONAL

/** One task found in pasted text or a photo, before review. */
data class Found(
    val text: String,
    val list: String?,       // null = use the list you're importing into
    val urgent: Boolean = false,
    val done: Boolean = false,
)

/**
 * Turns pasted text (Voice Log exports, notes, emails, OCR text) into tasks.
 * Understands checkboxes, bullets, numbering and BUSINESS / PERSONAL headings.
 */
object TextParser {

    private val bulletRegex = Regex(
        "^(?:" +
            "[☐☑☒✓✔✅□■▪▫●○•·◦\\-*–—>»]+" +           // checkbox and bullet characters
            "|\\[[ xX✓✔]?]" +                          // [ ] [x]
            "|\\(?\\d{1,3}[.)\\]:]" +                  // 1. 1) (1) 1:
            "|#\\d{1,3}" +                             // #1
            "|[a-hA-H][.)](?=\\s)" +                   // a) b.
            ")\\s*"
    )
    private val doneMark = Regex("^(?:[☑☒✓✔✅]|\\[[xX✓✔]])")
    private val urgentRegex = Regex("\\s*[–—-]?\\s*\\(?\\b(URGENT|ASAP)\\b\\)?!*\\s*$", RegexOption.IGNORE_CASE)
    private val urgentPrefix = Regex("^(?:\\(?\\b(URGENT|ASAP)\\b\\)?[:!\\s–—-]+)", RegexOption.IGNORE_CASE)

    private val businessHeading = Regex("\\b(business|work|armsco|office|company|jobs?)\\b", RegexOption.IGNORE_CASE)
    private val personalHeading = Regex("\\b(personal|home|family|house|private)\\b", RegexOption.IGNORE_CASE)
    private val otherHeading = Regex(
        "\\b(reminders?|contacts?|people to contact|ideas?|to-?do( list)?|tasks?|master list|notes?|summary|transcript)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(raw: String): List<Found> {
        val lines = raw.replace("\r", "").split("\n").map { it.trim() }
        val hasBullets = lines.count { it.isNotEmpty() && bulletRegex.containsMatchIn(it) && strip(it).isNotEmpty() } >= 1

        var section: String? = null
        var inIgnored = false     // e.g. after "Transcript" or "Summary" headings
        val out = mutableListOf<Found>()

        for (line in lines) {
            if (line.isEmpty()) continue
            val bulleted = bulletRegex.containsMatchIn(line) && bulletRegex.find(line)!!.range.first == 0

            val heading = headingOf(line, bulleted)
            if (heading != null) {
                when (heading) {
                    BUSINESS, PERSONAL -> { section = heading; inIgnored = false }
                    "IGNORE" -> inIgnored = true
                    else -> { section = null; inIgnored = false }    // Reminders, Ideas…: use the chosen list
                }
                continue
            }
            if (inIgnored) continue
            if (hasBullets && !bulleted) continue          // in a list, plain lines are titles/summaries
            if (isTitleLine(line)) continue

            var text = strip(line)
            var urgent = false
            if (urgentRegex.containsMatchIn(text)) { urgent = true; text = text.replace(urgentRegex, "") }
            if (urgentPrefix.containsMatchIn(text)) { urgent = true; text = text.replace(urgentPrefix, "") }
            text = text.trim().trimEnd(',', ';').trim()
            if (text.length < 2 || text.none { it.isLetter() }) continue

            out += Found(text, section, urgent, done = doneMark.containsMatchIn(line))
        }
        return out
    }

    /**
     * Text from a photo: lines that wrap are glued back together when the next line
     * clearly continues the sentence (starts lowercase, previous line has no ending).
     */
    fun joinWrappedLines(raw: String): String {
        val lines = raw.replace("\r", "").split("\n").map { it.trim() }
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty()) { out += ""; continue }
            val prev = out.lastOrNull()
            val prevLastWord = prev?.substringAfterLast(' ')?.lowercase().orEmpty()
            val continues = prev != null && prev.isNotEmpty() &&
                !bulletRegex.containsMatchIn(line) &&
                prev.last() !in ".!?:;" &&
                (line.first().isLowerCase() || prevLastWord in connectors)
            if (continues) out[out.size - 1] = "$prev $line" else out += line
        }
        return out.joinToString("\n")
    }

    private val connectors = setOf("the", "a", "an", "to", "for", "about", "and", "of", "with", "at", "on", "in", "from", "re", "by")

    private fun strip(line: String): String {
        var s = line
        repeat(3) { s = s.replace(bulletRegex, "") }   // "1. ☐ text" has two markers
        return s.trim()
    }

    /** Returns BUSINESS, PERSONAL, "IGNORE", "OTHER" or null when the line isn't a heading. */
    private fun headingOf(line: String, bulleted: Boolean): String? {
        if (bulleted) return null
        val s = line.trim().trimEnd(':', '-', '–', '—').trim()
        if (s.isEmpty() || s.length > 40) return null
        val marked = line.trimEnd().endsWith(":") || (s == s.uppercase() && s.count { it.isLetter() } >= 3)
        val bare = s.lowercase()
        val exactBusiness = bare in setOf("business", "business tasks", "business list", "work", "work tasks", "armsco", "armsco tasks", "office", "jobs")
        val exactPersonal = bare in setOf("personal", "personal tasks", "personal list", "home", "home tasks", "family")
        val exactOther = bare in setOf("reminders", "reminder", "ideas", "idea", "people to contact", "contacts", "to do", "to-do", "todo", "tasks", "to-do list", "todo list", "notes")
        val exactIgnore = bare in setOf("summary", "transcript")
        if (exactIgnore) return "IGNORE"
        if (exactBusiness) return BUSINESS
        if (exactPersonal) return PERSONAL
        if (exactOther) return "OTHER"
        if (!marked || s.split(Regex("\\s+")).size > 5) return null
        return when {
            Regex("\\b(summary|transcript)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "IGNORE"
            businessHeading.containsMatchIn(s) && personalHeading.containsMatchIn(s) -> "OTHER"
            businessHeading.containsMatchIn(s) -> BUSINESS
            personalHeading.containsMatchIn(s) -> PERSONAL
            otherHeading.containsMatchIn(s) -> "OTHER"
            else -> null
        }
    }

    /** "To-do list – Nov 14" style title lines at the top of exports. */
    private fun isTitleLine(line: String): Boolean =
        Regex("^(to-?do list|master list|business to-?do|personal to-?do)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
}
