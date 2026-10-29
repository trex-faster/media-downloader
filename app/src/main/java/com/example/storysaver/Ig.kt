package com.example.storysaver

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Ig {
    // Mismo User-Agent en el WebView de login y en el WebView que lee las stories.
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Flujo + tamaño en bytes (-1 si el servidor no lo indica). */
    class Sized(val stream: InputStream, val length: Long)

    /** Descarga del archivo en el CDN (la URL ya viene firmada, no necesita cookies). */
    fun open(url: String): InputStream = openSized(url).stream

    fun openSized(url: String): Sized {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://www.instagram.com/")
        if (c.responseCode != 200) throw Exception("Descarga HTTP ${c.responseCode}")
        return Sized(c.inputStream, c.contentLengthLong)
    }
}