package com.xc.checker

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

/** Microsoft device-code login -> Xbox Live user token -> XSTS token ("XBL3.0 x=uhs;token"). */
object Auth {
    private val http = OkHttpClient()
    private val JSON = "application/json".toMediaType()
    private const val MS = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val SCOPE = "XboxLive.signin offline_access"

    fun prefs(c: Context) = c.getSharedPreferences("xc", Context.MODE_PRIVATE)
    fun token(c: Context): String? = prefs(c).getString("xbl", null)

    fun setManual(c: Context, t: String) {
        var s = t.trim()
        if (s.isEmpty()) return
        if (!s.startsWith("XBL3.0")) s = "XBL3.0 $s"
        prefs(c).edit().putString("xbl", s).apply()
    }

    private fun post(url: String, body: RequestBody, vararg h: Pair<String, String>): Pair<Int, JSONObject> {
        val b = Request.Builder().url(url).post(body)
        h.forEach { b.header(it.first, it.second) }
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            return r.code to runCatching { JSONObject(t) }.getOrDefault(JSONObject())
        }
    }

    private fun form(vararg p: Pair<String, String>): RequestBody =
        FormBody.Builder().also { f -> p.forEach { f.add(it.first, it.second) } }.build()

    suspend fun startDeviceCode(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        val (code, j) = post("$MS/devicecode", form("client_id" to clientId, "scope" to SCOPE))
        if (code != 200) error(j.optString("error_description", "devicecode failed ($code)"))
        DeviceCode(
            j.getString("user_code"), j.getString("verification_uri"),
            j.getString("device_code"), j.optInt("interval", 5), j.optInt("expires_in", 900)
        )
    }

    suspend fun pollDeviceCode(c: Context, clientId: String, d: DeviceCode) = withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis() + d.expiresIn * 1000L
        while (System.currentTimeMillis() < end) {
            delay(d.interval * 1000L)
            val (code, j) = post(
                "$MS/token",
                form(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId, "device_code" to d.deviceCode
                )
            )
            if (code == 200) {
                exchange(c, clientId, j.getString("access_token"), j.optString("refresh_token"))
                return@withContext
            }
            val e = j.optString("error")
            if (e != "authorization_pending" && e != "slow_down") error(j.optString("error_description", e))
        }
        error("Code expired, try again")
    }

    private fun exchange(c: Context, clientId: String, msAccess: String, refresh: String) {
        val (c1, u) = post(
            "https://user.auth.xboxlive.com/user/authenticate",
            JSONObject()
                .put("Properties", JSONObject().put("AuthMethod", "RPS")
                    .put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=$msAccess"))
                .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT")
                .toString().toRequestBody(JSON),
            "x-xbl-contract-version" to "1"
        )
        if (c1 != 200) error("Xbox Live auth failed ($c1)")
        val (c2, x) = post(
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            JSONObject()
                .put("Properties", JSONObject().put("SandboxId", "RETAIL")
                    .put("UserTokens", JSONArray().put(u.getString("Token"))))
                .put("RelyingParty", "http://xboxlive.com").put("TokenType", "JWT")
                .toString().toRequestBody(JSON),
            "x-xbl-contract-version" to "1"
        )
        if (c2 != 200) error("XSTS failed ($c2, XErr ${x.optLong("XErr")})")
        val uhs = x.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
        val e = prefs(c).edit()
        e.putString("client", clientId)
        if (refresh.isNotEmpty()) e.putString("refresh", refresh)
        e.putString("xbl", "XBL3.0 x=$uhs;${x.getString("Token")}")
        e.apply()
    }

    /** Silent re-auth using the stored refresh token. */
    suspend fun refresh(c: Context): Boolean = withContext(Dispatchers.IO) {
        val p = prefs(c)
        val r = p.getString("refresh", null) ?: return@withContext false
        val cid = p.getString("client", "") ?: ""
        val (code, j) = post(
            "$MS/token",
            form("grant_type" to "refresh_token", "client_id" to cid, "refresh_token" to r, "scope" to SCOPE)
        )
        if (code != 200) return@withContext false
        runCatching { exchange(c, cid, j.getString("access_token"), j.optString("refresh_token")) }.isSuccess
    }
}
