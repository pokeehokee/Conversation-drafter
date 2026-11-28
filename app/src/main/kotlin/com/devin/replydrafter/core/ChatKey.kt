package com.devin.replydrafter.core

import java.security.MessageDigest

/**
 * A short, one-way key for "this chat in this app", used to remember the last tone per chat.
 * It's a hash, so the other person's name is never stored on the phone.
 */
object ChatKey {
    fun of(packageName: String, title: String?): String {
        val raw = packageName + "\u0000" + title?.trim()?.lowercase().orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
