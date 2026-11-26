package com.devin.replydrafter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the one fallback screenshot to the Claude app, read-only, by temporary URI grant.
 * A tiny stand-in for androidx FileProvider so the app has no library dependencies.
 */
class ShotProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.lastPathSegment != SHOT_NAME) throw FileNotFoundException(uri.toString())
        val f = File(ctx.cacheDir, SHOT_NAME)
        if (!f.exists()) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val f = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(SHOT_NAME, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.devin.replydrafter.shots"
        const val SHOT_NAME = "chat.jpg"
        val SHOT_URI: Uri = Uri.parse("content://$AUTHORITY/$SHOT_NAME")
    }
}
