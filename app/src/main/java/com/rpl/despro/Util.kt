package com.rpl.despro

object Util {
    fun parseNdefText(payload: ByteArray): String {
        val languageLength = payload[0].toInt() and 0x3F
        return String(payload, 1 + languageLength, payload.size - 1 - languageLength, Charsets.UTF_8)
    }
}