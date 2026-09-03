package com.duluin.ftth.collector.adapter.zte

object ZteTranscriptParser {
    const val NO_MATCH = "% No matching configuration."

    private val prompt = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}(\\((?:config|config-if|config-vlan[0-9]+)\\))?#$")
    private val destructivePrompt = Regex("(?i)(confirm|are you sure|continue).*(?:yes/no|y/n|\\[confirm])")
    private val commandError = Regex("(?im)^(?:%\\s*(?:error|invalid|unknown|incomplete|ambiguous)|error:).*$")
    private val product = Regex("(?m)^Product Name\\s*:\\s*(ZXA10 C(?:300|320))\\s*$")
    private val software = Regex("(?m)^Software Version\\s*:\\s*([A-Za-z0-9._-]+)\\s*$")
    private val sensitive = Regex("(?i)\\b(password|secret|community|token|username)\\s*[:=]?\\s*\\S+")

    fun commandBody(command: String, transcript: String): String {
        val normalized = transcript.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (destructivePrompt.containsMatchIn(normalized)) {
            throw ZteAdapterException(ZteFailureCode.DESTRUCTIVE_PROMPT, "ZTE requested interactive confirmation")
        }
        val lines = normalized.lines()
        if (lines.isEmpty() || !prompt.matches(lines.last())) {
            throw ZteAdapterException(
                ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
                "Unknown ZTE prompt in ${scrub(normalized)}",
            )
        }
        val bodyLines = lines.dropLast(1).toMutableList()
        if (bodyLines.firstOrNull()?.trim() == command) bodyLines.removeAt(0)
        val body = bodyLines.joinToString("\n").trim()
        if (body != NO_MATCH && commandError.containsMatchIn(body)) {
            throw ZteAdapterException(
                ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
                "ZTE command failed: ${scrub(body)}",
            )
        }
        return body
    }

    fun profileKey(body: String): ZteProfileKey {
        val family = product.find(body)?.groupValues?.get(1)
        val firmware = software.find(body)?.groupValues?.get(1)
        if (family == null || firmware == null) {
            throw ZteAdapterException(
                ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
                "ZTE identity response did not contain an exact family and firmware",
            )
        }
        return ZteProfileKey(family, firmware)
    }

    fun scrub(value: String): String = sensitive.replace(value) { "${it.groupValues[1]} [REDACTED]" }
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
        .take(240)
}
