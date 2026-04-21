package com.ketan.slam

/**
 * Cleans LLM output so Android TextToSpeech doesn't read markdown, code
 * fences, leftover JSON, emojis, or other decorations aloud. Also used on
 * the on-screen reply card so the text looks clean.
 *
 * This is a defensive layer — even though we ask the model for plain text
 * in our system prompts, GLM/Gemini variants sometimes wrap the answer in
 * code fences or prepend labels like "Answer:" or "[assistant]".
 */
object TtsSanitizer {

    /** Max characters read aloud. Past this we cut at a sentence boundary. */
    private const val MAX_SPOKEN_CHARS = 320

    // Markdown / meta patterns
    private val FENCED_CODE  = Regex("```[^`]*```", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE  = Regex("`([^`]+)`")
    private val MD_LINK      = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val MD_BOLD      = Regex("\\*\\*([^*]+)\\*\\*")
    private val MD_ITALIC    = Regex("(?<![*_])[*_]([^*_]+)[*_](?![*_])")
    private val MD_HEADING   = Regex("^\\s*#{1,6}\\s*", RegexOption.MULTILINE)
    private val MD_BULLET    = Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE)
    private val MD_BLOCKQUOTE = Regex("^\\s*>\\s?", RegexOption.MULTILINE)
    private val HTML_TAG     = Regex("<[^>]+>")

    // Meta-text & role prefixes the LLM sometimes leaks through
    private val ROLE_PREFIX  = Regex(
        "^\\s*(?:answer|assistant|system|response|reply|ai|bot|model)\\s*[:\\-]\\s*",
        RegexOption.IGNORE_CASE
    )
    private val BRACKET_META = Regex("\\[(?:note|meta|system|assistant|tool|thinking|reasoning)[^\\]]*\\]", RegexOption.IGNORE_CASE)
    private val PARENS_META  = Regex("\\((?:note|meta|reasoning|thought|internal)[^)]*\\)", RegexOption.IGNORE_CASE)

    // Emoji + symbol ranges we don't want TTS to read
    private val EMOJI_RANGES = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|[\u2600-\u27BF]|[\uFE00-\uFE0F]")

    // Stray JSON fragments the model may emit before/after the answer
    private val JSON_WRAPPER = Regex("^\\s*\\{[^{}]*\"answer\"\\s*:\\s*\"(.*?)\"[^{}]*\\}\\s*\$",
        RegexOption.DOT_MATCHES_ALL)

    private val MULTI_SPACE = Regex("\\s+")

    /**
     * Clean LLM text for both on-screen display and TTS. Returns a trimmed,
     * single-line-friendly string safe to feed to [android.speech.tts.TextToSpeech].
     */
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()

        // If the whole thing is a JSON envelope with an "answer" field, extract it.
        JSON_WRAPPER.matchEntire(s)?.groupValues?.get(1)?.let { s = it }

        // Strip code fences first — they can hide further markdown inside.
        s = FENCED_CODE.replace(s, " ")
        s = INLINE_CODE.replace(s, "$1")

        // Markdown formatting → plain text
        s = MD_LINK.replace(s, "$1")
        s = MD_BOLD.replace(s, "$1")
        s = MD_ITALIC.replace(s, "$1")
        s = MD_HEADING.replace(s, "")
        s = MD_BULLET.replace(s, "")
        s = MD_BLOCKQUOTE.replace(s, "")
        s = HTML_TAG.replace(s, "")

        // Meta/role noise
        s = ROLE_PREFIX.replace(s, "")
        s = BRACKET_META.replace(s, "")
        s = PARENS_META.replace(s, "")

        // Emoji and pictographs — TTS pronunciations are awful ("smiling face")
        s = EMOJI_RANGES.replace(s, "")

        // Escaped unicode like \n, \t that sometimes slip through JSON decode
        s = s.replace("\\n", " ").replace("\\t", " ").replace("\\\"", "\"")

        // Collapse whitespace, drop leading punctuation
        s = MULTI_SPACE.replace(s, " ").trim()
        s = s.trimStart(':', '-', '·', '—', '–', '.', ',', ' ')

        // Cap length at a sentence boundary where possible
        if (s.length > MAX_SPOKEN_CHARS) {
            val cut = s.substring(0, MAX_SPOKEN_CHARS)
            val boundary = maxOf(
                cut.lastIndexOf('.'),
                cut.lastIndexOf('!'),
                cut.lastIndexOf('?'),
                cut.lastIndexOf(';'),
            )
            s = if (boundary > MAX_SPOKEN_CHARS / 2) cut.substring(0, boundary + 1)
                else cut.trimEnd() + "…"
        }

        return s
    }
}
