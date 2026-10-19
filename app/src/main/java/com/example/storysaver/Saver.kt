package com.example.storysaver

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Guarda en la galería (MediaStore): Pictures/StorySaver o Movies/StorySaver. Llamar en un hilo de fondo. */
object Saver {

    /** @return true si guardó el archivo, false si ya existía (no duplica al repetir). */
    fun save(ctx: Context, m: StoryFetcher.Media, fallbackUser: String): Boolean {
        val user = m.user.ifEmpty { fallbackUser }
        val safe = "${user}_${m.id}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = safe + if (m.video) ".mp4" else ".jpg"
        val dir = if (m.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val collection = if (m.video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val resolver = ctx.contentResolver

        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$dir/StorySaver%"),
            null
        )?.use { c -> if (c.moveToFirst()) return false }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (m.video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/StorySaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw Exception("No se pudo crear el archivo")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                Ig.open(m.url).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return true
    }
}
