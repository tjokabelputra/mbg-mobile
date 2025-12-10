package com.rpl.despro

object Util {
    fun parseNdefText(payload: ByteArray): String {
        val languageLength = payload[0].toInt() and 0x3F
        return String(payload, 1 + languageLength, payload.size - 1 - languageLength, Charsets.UTF_8)
    }
    fun extractMessage(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)

            when {
                obj.has("message") -> obj.getString("message")
                obj.has("msg") -> obj.getString("msg")
                obj.has("status") -> obj.getString("status")
                else -> json
            }

        } catch (e: Exception) {
            json
        }
    }
}