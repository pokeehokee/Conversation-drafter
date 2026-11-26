package com.devin.replydrafter

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands the prompt to the official Claude app with Android's standard Share intent, so the reply
 * is written on the user's own Claude subscription. Falls back to the system share sheet.
 */
object ClaudeHandoff {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Needs the <queries> entry for the Claude package in the manifest (Android 11+ package visibility). */
    fun isClaudeInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE) != null

    /** @return a short status line for the user. */
    fun share(context: Context, prompt: String, screenshot: Uri?): String {
        // Backup copy, in case the Claude app opens without the text filled in.
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Reply prompt", prompt))

        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, prompt)
            if (screenshot != null) {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, screenshot)
                clipData = ClipData.newRawUri("Chat screenshot", screenshot)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isClaudeInstalled(context)) {
            try {
                context.startActivity(Intent(send).setPackage(CLAUDE_PACKAGE))
                return "Opened Claude. Tap send there, then copy the reply."
            } catch (_: ActivityNotFoundException) {
                // This Claude version has no matching share target; use the share sheet.
            }
        }
        val chooser = Intent.createChooser(send, "Send to Claude").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return "Pick Claude in the share sheet. The prompt is also on your clipboard."
    }
}
