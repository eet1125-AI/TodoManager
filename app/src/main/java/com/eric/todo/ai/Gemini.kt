package com.eric.todo.ai

import android.util.Base64
import com.eric.todo.data.BUSINESS
import com.eric.todo.data.PERSONAL
import com.eric.todo.data.Task
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AiException(message: String) : Exception(message)

/** One task the AI pulled out of the notes. */
data class AiTask(
    val text: String,
    val list: String,
    val urgent: Boolean,
    val done: Boolean,
    val matchId: String,      // id of an existing task that's the same thing, or ""
)

/**
 * Google Gemini (REST, no extra libraries). Used to read messy notes and photos,
 * write each task as a short clear line, pick Business or Personal, and spot
 * tasks you already have.
 */
class Gemini(private val apiKey: String, private val model: String) {

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    fun check() {
        val conn = open("$base/models/$model", "GET")
        readJson(conn)
    }

    /**
     * [text] = pasted text (may be empty), [images] = JPEG photos of notes (may be empty),
     * [existing] = open and done tasks already in the app, so the AI can flag repeats.
     */
    fun extractTasks(text: String, images: List<ByteArray>, existing: List<Task>, defaultList: String): List<AiTask> {
        val existingText = existing.take(400).joinToString("\n") {
            "${it.id} | ${it.list} | ${if (it.done) "DONE" else "OPEN"} | ${it.text}"
        }
        val prompt = """
            You organize a to-do list for Eric Taylor, co-owner of ARMSCO Doors & Docks (a commercial door, loading dock and gate service company in Houston, TX).

            Read the notes below${if (images.isNotEmpty()) " and in the attached photo(s) (may be handwritten)" else ""} and pull out every task.
            Rules:
            - One item per task. Write each as a short, clear action in plain words (keep names, places, numbers, dates).
            - Don't invent tasks. Skip headings, summaries, greetings and transcripts.
            - list = "$BUSINESS" for anything about ARMSCO, customers, jobs, doors, docks, quotes, invoices, techs, suppliers, office; "$PERSONAL" for everything else. If unsure use "$defaultList". If the notes have Business/Personal headings, follow them.
            - urgent = true only if the notes say urgent, ASAP, today, or similar.
            - done = true only if the task is shown checked off / crossed out.
            - If the same task appears more than once in the notes, return it once.
            - match_id: if the task is the same thing as one of Eric's existing tasks below (same action, even if worded differently), put that task's id. Otherwise "".

            Eric's existing tasks (id | list | status | text):
            ${existingText.ifBlank { "(none)" }}

            Notes:
            ${text.ifBlank { "(see photos)" }}
        """.trimIndent()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        images.forEach { jpeg ->
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema())
                .put("temperature", 0.2))

        val conn = open("$base/models/$model:generateContent", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        writeBody(conn, body.toString())
        val res = readJson(conn)

        val candidate = res.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("The AI returned no answer.")
        val answerParts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val answer = StringBuilder()
        for (i in 0 until answerParts.length()) {
            val p = answerParts.getJSONObject(i)
            if (!p.optBoolean("thought") && p.has("text")) answer.append(p.getString("text"))
        }
        val json = try {
            JSONObject(answer.toString().trim().removePrefix("```json").removeSuffix("```").trim())
        } catch (e: Exception) {
            throw AiException("The AI's answer couldn't be read. Try again.")
        }
        val ids = existing.map { it.id }.toSet()
        val arr = json.optJSONArray("tasks") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val t = o.optString("text").trim()
            if (t.isEmpty()) return@mapNotNull null
            AiTask(
                text = t,
                list = if (o.optString("list").equals(PERSONAL, true)) PERSONAL
                       else if (o.optString("list").equals(BUSINESS, true)) BUSINESS else defaultList,
                urgent = o.optBoolean("urgent"),
                done = o.optBoolean("done"),
                matchId = o.optString("match_id").trim().takeIf { it in ids } ?: "",
            )
        }
    }

    private fun schema(): JSONObject {
        val item = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("text", JSONObject().put("type", "STRING"))
                .put("list", JSONObject().put("type", "STRING").put("format", "enum").put("enum", JSONArray(listOf(BUSINESS, PERSONAL))))
                .put("urgent", JSONObject().put("type", "BOOLEAN"))
                .put("done", JSONObject().put("type", "BOOLEAN"))
                .put("match_id", JSONObject().put("type", "STRING")))
            .put("required", JSONArray(listOf("text", "list", "urgent", "done", "match_id")))
            .put("propertyOrdering", JSONArray(listOf("text", "list", "urgent", "done", "match_id")))
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("tasks", JSONObject().put("type", "ARRAY").put("items", item)))
            .put("required", JSONArray(listOf("tasks")))
    }

    // ---------------------------------------------------------------------

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("x-goog-api-key", apiKey)
        }

    private fun writeBody(conn: HttpURLConnection, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(bytes.size)
        try {
            conn.outputStream.use { it.write(bytes) }
        } catch (e: IOException) {
            throw AiException("No internet connection to the AI.")
        }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw AiException("No internet connection to the AI.")
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code in 200..299) return if (text.isBlank()) JSONObject() else JSONObject(text)

        val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
        val msg = err?.optString("message").orEmpty()
        val status = err?.optString("status").orEmpty()
        throw when {
            msg.contains("API key", ignoreCase = true) || status == "UNAUTHENTICATED" ->
                AiException("Your Gemini API key isn't valid. Check it in Settings.")
            code == 404 -> AiException("The AI model \"$model\" isn't available. Pick another in Settings.")
            code == 429 -> AiException("The AI's free usage limit was reached. Try again later, or switch to Flash-Lite in Settings.")
            code >= 500 -> AiException("The AI is busy right now ($code). Try again in a minute.")
            else -> AiException("AI error $code: ${msg.ifEmpty { status }}")
        }
    }
}
