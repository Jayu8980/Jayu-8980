package com.autotube.ai.data.files

/**
 * Masks anything that looks like a credential before text reaches a log file or Logcat:
 * provider keys (sk-..., sk-ant-..., r8_..., AIza..., ya29....), bearer tokens, `key=` style query
 * parameters, JSON/header key-value pairs and long hex/base64-like tokens.
 */
internal object SecretRedactor {

    private const val MASK = "***"

    private val bearer = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/\-]+=*""")

    private val queryParam = Regex(
        """(?i)([?&](?:key|api_key|apikey|access_token|refresh_token|token|client_secret|signature)=)[^&\s"'#]+""",
    )

    private val namedValue = Regex(
        """(?i)("?\b(?:api[_-]?key|x-api-key|xi-api-key|x-goog-api-key|authorization|access_token|refresh_token|id_token|client_secret|password)"?\s*[:=]\s*"?)([^"\s,;&}]+)""",
    )

    /** Known key prefixes; the prefix is kept so logs still show which provider was involved. */
    private val prefixedKey = Regex("""\b(sk-ant-|sk-proj-|sk-|sk_|r8_|xi-|AIza|ghp_|gho_|hf_)[A-Za-z0-9_\-]{8,}""")

    private val googleAccessToken = Regex("""\b(ya29\.)[A-Za-z0-9_.\-]{8,}""")

    private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")

    /** 32+ token characters not part of a path segment or file name; masked only when mixed-case with digits. */
    private val longToken = Regex("""(?<![A-Za-z0-9_\-./+=])[A-Za-z0-9_\-+=]{32,}(?![A-Za-z0-9_\-./+=])""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = bearer.replace(out, "\$1$MASK")
        out = queryParam.replace(out, "\$1$MASK")
        out = namedValue.replace(out, "\$1$MASK")
        out = prefixedKey.replace(out, "\$1$MASK")
        out = googleAccessToken.replace(out, "\$1$MASK")
        out = longHex.replace(out, MASK)
        out = longToken.replace(out) { match ->
            if (looksLikeSecret(match.value)) MASK else match.value
        }
        return out
    }

    private fun looksLikeSecret(token: String): Boolean {
        var digit = false
        var upper = false
        var lower = false
        for (c in token) {
            when {
                c in '0'..'9' -> digit = true
                c in 'A'..'Z' -> upper = true
                c in 'a'..'z' -> lower = true
            }
        }
        return digit && upper && lower
    }
}
