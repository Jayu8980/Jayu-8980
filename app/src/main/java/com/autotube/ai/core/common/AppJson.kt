package com.autotube.ai.core.common

import kotlinx.serialization.json.Json

/** The one Json instance used for Room blobs, settings and LLM payloads. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    allowTrailingComma = true
}

/**
 * Pulls the first complete JSON object or array out of an LLM reply that may contain prose or
 * ```json fences. Returns the trimmed input when no brackets are found.
 */
fun extractJson(text: String): String {
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)
    val source = (fenced ?: text).trim()
    val start = source.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return source
    val open = source[start]
    val close = if (open == '{') '}' else ']'
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until source.length) {
        val c = source[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return source.substring(start, i + 1)
            }
        }
    }
    return source.substring(start)
}
