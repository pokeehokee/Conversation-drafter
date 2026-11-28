package com.devin.replydrafter

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle

/** The AI apps the user can draft in. Each one runs on the user's own account and plan. */
enum class AiTarget(val label: String, val packageName: String?) {
    CLAUDE("Claude", "com.anthropic.claude"),
    CHATGPT("ChatGPT", "com.openai.chatgpt"),
    GEMINI("Gemini", "com.google.android.apps.bard"),
    COPILOT("Copilot", "com.microsoft.copilot"),
    PERPLEXITY("Perplexity", "ai.perplexity.app.android"),
    ASK("Ask every time", null),
}

/**
 * Hands the prompt to the AI app the user picked, with Android's standard Share intent, so the reply
 * is written on their own subscription. Never calls an API and never drives the other app.
 *
 * Order of tries:
 * 1. The picked app takes shared text (or the screenshot): share straight into it.
 * 2. It's installed but takes no shared text: copy the prompt (marked sensitive, so keyboards and
 *    clipboard previews hide it) and open the app, for the user to paste.
 * 3. Not installed, or "Ask every time": the system share sheet.
 */
object AiHandoff {
    /** Package names of every AI app we know, so the reader ignores their windows. */
    val packages: Set<String> = AiTarget.entries.mapNotNull { it.packageName }.toSet()

    /** Needs the <queries> entries in the manifest (Android 11+ package visibility). */
    fun isInstalled(context: Context, target: AiTarget): Boolean {
        val pkg = target.packageName ?: return true
        return context.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    /** @return a short status line for the user. */
    fun share(context: Context, target: AiTarget, prompt: String, screenshot: Uri?): String {
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

        val pkg = target.packageName
        if (pkg != null && isInstalled(context, target)) {
            val direct = Intent(send).setPackage(pkg)
            if (direct.resolveActivity(context.packageManager) != null) {
                try {
                    context.startActivity(direct)
                    return "Opened ${target.label}. Send it there, copy the reply, then come back and tap Paste reply."
                } catch (_: ActivityNotFoundException) {
                    // Fall through to the next try.
                }
            }
            // A screenshot can't go on the clipboard, so that case uses the share sheet below.
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (screenshot == null && launch != null) {
                copySensitive(context, prompt)
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Opened ${target.label}. Paste the prompt (it's on your clipboard), send it, then copy the reply."
            }
        }

        val chooser = Intent.createChooser(send, "Draft the reply in…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return when {
            pkg != null && !isInstalled(context, target) -> "${target.label} isn't installed, so pick an AI app here."
            else -> "Pick your AI app. Send the prompt there, then copy the reply."
        }
    }

    /** The prompt holds both sides of the chat, so keep it out of clipboard history and paste previews. */
    private fun copySensitive(context: Context, text: String) {
        val clip = ClipData.newPlainText("Reply prompt", text).apply {
            // ClipDescription.EXTRA_IS_SENSITIVE (Android 13+); older versions ignore it.
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }
}
