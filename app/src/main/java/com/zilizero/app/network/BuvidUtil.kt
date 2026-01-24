package com.zilizero.app.network

import java.security.MessageDigest
import java.util.UUID

/**
 * Utility to handle Buvid3/4 generation and retrieval.
 */
object BuvidUtil {

    /**
     * Generates a temporary local Buvid3 if server one is not available.
     * Logic: XY + MD5(UUID) in uppercase.
     */
    fun generateLocalBuvid3(): String {
        val randomStr = UUID.randomUUID().toString()
        val md5 = md5(randomStr).uppercase()
        return "XY$md5"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
