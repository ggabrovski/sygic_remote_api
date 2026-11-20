package com.transportorganizer.sygic_remote_api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Self-contained ContentProvider serving cached SIF/route JSON files from
 * `cacheDir/sygic_routes/` to Sygic on Android 11+ Scoped Storage without
 * requiring an external res/xml file_paths resource.
 */
class SygicRouteContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val name = uri.lastPathSegment
            ?.replace("/", "")
            ?.replace("..", "")
            ?.takeIf { it.isNotEmpty() }
            ?: throw FileNotFoundException("Missing route filename")

        val dir = File(ctx.cacheDir, ROUTE_CACHE_DIR)
        val file = File(dir, name)
        if (!file.exists() || !file.canonicalPath.startsWith(dir.canonicalPath)) {
            throw FileNotFoundException("Route file not found: $name")
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val ROUTE_CACHE_DIR = "sygic_routes"
    }
}
