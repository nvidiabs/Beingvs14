package com.xc.checker

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

object ScanState {
    val running = MutableStateFlow(false)
    val log = MutableStateFlow("Idle")
    val hits = MutableStateFlow<List<String>>(emptyList())
    val checked = MutableStateFlow(0L)
    val total = MutableStateFlow(0L)
}

object Checker {
    sealed class R {
        object Free : R()
        object Taken : R()
        class Limited(val waitMs: Long) : R()
        object AuthFail : R()
        class Err(val m: String) : R()
    }

    private val http = OkHttpClient()
    private val VALID = Regex("^[A-Za-z][A-Za-z0-9 ]{0,14}$")
    fun valid(s: String) = VALID.matches(s)

    /** 404 on the profile lookup = nobody has that gamertag right now. */
    fun check(tag: String, token: String): R = try {
        val req = Request.Builder()
            .url("https://profile.xboxlive.com/users/gt($tag)/profileUsers")
            .header("Authorization", token)
            .header("x-xbl-contract-version", "2")
            .header("Accept-Language", "en-US")
            .build()
        http.newCall(req).execute().use { r ->
            when (r.code) {
                200 -> R.Taken
                404 -> R.Free
                400 -> R.Taken // invalid/filtered name, skip
                401 -> R.AuthFail
                429 -> R.Limited((r.header("Retry-After")?.toLongOrNull() ?: 30L) * 1000)
                else -> R.Err("HTTP ${r.code}")
            }
        }
    } catch (e: Exception) {
        R.Err(e.message ?: "network error")
    }

    // ---- name generation ----
    // Pattern chars: ? = letter or digit, @ = letter, # = digit, anything else = literal.
    private const val AL = "abcdefghijklmnopqrstuvwxyz"
    private const val DG = "0123456789"
    private fun pool(ch: Char) = when (ch) { '?' -> AL + DG; '@' -> AL; '#' -> DG; else -> ch.toString() }

    fun count(p: String): Long = p.fold(1L) { a, c -> a * pool(c).length }

    fun at(p: String, i: Long): String {
        var n = i
        val out = CharArray(p.length)
        for (k in p.indices.reversed()) {
            val pl = pool(p[k])
            out[k] = pl[(n % pl.length).toInt()]
            n /= pl.length
        }
        return String(out)
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** Multiplier for a cheap bijective shuffle of 0 until n (so random order never repeats). */
    fun shuffleMult(n: Long): Long {
        var a = 100003L
        while (gcd(a, n) != 1L) a += 2
        return a
    }
}
