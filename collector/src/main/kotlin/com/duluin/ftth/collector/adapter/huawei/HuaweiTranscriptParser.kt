package com.duluin.ftth.collector.adapter.huawei

object HuaweiTranscriptParser {
    const val NO_MATCH = "% No matching configuration."

    private val prompt = Regex("^(?:<[A-Za-z0-9][A-Za-z0-9._-]{0,31}>|\\[\\*?[A-Za-z0-9][A-Za-z0-9._-]{0,31}(?:-gpon-lineprofile-[1-9][0-9]*|-vlan[1-9][0-9]*)?])$")
    private val unsafePrompt = Regex("(?i)(?:are you sure|continue|confirm).*(?:y/n|yes/no|\\[confirm]|\\[y/n])")
    private val commandError = Regex("(?im)^(?:error:|failure:|%\\s*(?:error|invalid|unknown|incomplete|ambiguous)).*$")
    private val pager = Regex("(?m)^\\s*-{2,}\\s*More\\s*\\([^\\n]*\\)\\s*-{2,}\\s*$", RegexOption.IGNORE_CASE)
    private val product = Regex("(?m)^PRODUCT\\s*:\\s*(SmartAX MA5800-X7)\\s*$")
    private val version = Regex("(?m)^VERSION\\s*:\\s*(MA5800V[0-9A-Z]+)\\s*$")
    private val labelledSecret = Regex("(?i)\\b(password|secret|community|token|username)\\s*[:=]?\\s*\\S+")
    private val bearer = Regex("(?i)\\bbearer\\s+\\S+")
    private val uriCredential = Regex("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@")

    fun commandBody(command: String, transcript: String): String {
        val normalized = transcript.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]"), "")
            .replace(pager, "")
            .replace("\b", "")
            .trim()
        if (unsafePrompt.containsMatchIn(normalized)) {
            throw HuaweiAdapterException(HuaweiFailureCode.UNSAFE_PROMPT, "Huawei requested interactive confirmation")
        }
        val lines = normalized.lines()
        if (lines.isEmpty() || !prompt.matches(lines.last())) {
            throw HuaweiAdapterException(
                HuaweiFailureCode.UNRECOGNIZED_TRANSCRIPT,
                "Unknown Huawei prompt in ${scrub(normalized)}",
            )
        }
        val bodyLines = lines.dropLast(1).toMutableList()
        if (bodyLines.firstOrNull()?.trim() == command) bodyLines.removeAt(0)
        val body = bodyLines.joinToString("\n").trim()
        if (body != NO_MATCH && commandError.containsMatchIn(body)) {
            throw HuaweiAdapterException(HuaweiFailureCode.COMMAND_ERROR, "Huawei command failed: ${scrub(body)}")
        }
        return body
    }

    fun profileKey(body: String): HuaweiProfileKey {
        val family = product.find(body)?.groupValues?.get(1)
        val firmware = version.find(body)?.groupValues?.get(1)
        if (family == null || firmware == null) {
            throw HuaweiAdapterException(
                HuaweiFailureCode.UNKNOWN_PROFILE,
                "Huawei identity response did not contain an exact family and firmware",
            )
        }
        return HuaweiProfileKey(family, firmware)
    }

    fun scrub(value: String): String = uriCredential.replace(value, "$1[REDACTED]@")
        .let { bearer.replace(it, "Bearer [REDACTED]") }
        .let { labelledSecret.replace(it) { match -> "${match.groupValues[1]} [REDACTED]" } }
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
        .take(240)
}
