package io.github.kyotobyte.cdmplus.engine

import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A failure that retrying can't fix (bad link, empty file, redirect loop...). */
open class PermanentException(msg: String) : IOException(msg)

/** Phone storage is full or the file can't be written. */
class StorageException(msg: String, cause: Throwable? = null) : PermanentException(msg) { init { cause?.let { initCause(it) } } }

/** Classifies download failures: what's worth an automatic retry, and what the user reads. */
object DownloadErrors {
    fun isStorage(e: Throwable): Boolean {
        if (e is StorageException) return true
        var c: Throwable? = e
        while (c != null) {
            val m = c.message?.lowercase() ?: ""
            if ("enospc" in m || "no space left" in m || "disk full" in m || "file too large" in m || "efbig" in m || "edquot" in m) return true
            c = c.cause
        }
        return false
    }

    fun isRedirectLoop(e: Throwable) = e is ProtocolException && (e.message?.contains("follow-up", true) == true || e.message?.contains("redirect", true) == true)

    /** Network trouble worth retrying automatically. */
    fun retryable(e: Throwable): Boolean {
        if (e is PermanentException || isStorage(e) || isRedirectLoop(e)) return false
        if (e is HttpStatusException || e is EncryptedStreamException) return false
        if (e.message?.contains("Size mismatch") == true) return false
        return e is UnknownHostException || e is SocketTimeoutException || e is ConnectException || e is SocketException || e is SSLException || e is IOException
    }

    fun message(e: Throwable): String = when {
        isStorage(e) -> if (e is StorageException) e.message ?: "Phone storage is full" else "Phone storage is full - free some space, then resume"
        isRedirectLoop(e) -> "The link keeps redirecting in a loop - open it in the browser to get a fresh link"
        e is UnknownHostException -> "No internet / host not found"
        e is SocketTimeoutException -> "Connection timed out"
        e is ConnectException -> "Can't connect to the server - check your internet"
        e is SSLException -> "Secure connection failed (SSL)"
        e is IllegalArgumentException -> "Not a valid link"
        e is HttpStatusException -> when (e.code) {
            403 -> "Link refused (HTTP 403) - it may have expired"
            404 -> "File not found (HTTP 404)"
            410 -> "Link expired (HTTP 410)"
            416 -> "The server has no data for this link (HTTP 416)"
            509 -> "MEGA transfer limit reached, try later"
            else -> "Server error (HTTP ${e.code})"
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        else -> "${b / 1024} KB"
    }
}
