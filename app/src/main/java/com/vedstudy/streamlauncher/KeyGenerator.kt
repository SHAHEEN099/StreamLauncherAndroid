package com.vedstudy.streamlauncher

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class KeyGenerationResult(
    val success: Boolean,
    val sessionCookie: String? = null,
    val sessionExpiry: Long = 0L,
    val jwtKey: String? = null,
    val errorMessage: String? = null
)

class KeyGenerator {

    private val cookieStore = mutableMapOf<String, MutableMap<String, Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val domainMap = cookieStore.getOrPut(host) { mutableMapOf() }
            for (cookie in cookies) {
                domainMap[cookie.name] = cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val result = mutableListOf<Cookie>()
            cookieStore[host]?.values?.let { result.addAll(it) }
            // Include parent domain cookies (e.g. testuk.org)
            for ((savedHost, map) in cookieStore) {
                if (savedHost != host && (host.endsWith(".$savedHost") || savedHost.endsWith(".$host"))) {
                    result.addAll(map.values)
                }
            }
            return result
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    suspend fun runKeyGenerator(
        onProgress: (status: String, detail: String, pct: Float, etaSec: Int) -> Unit
    ): KeyGenerationResult = withContext(Dispatchers.IO) {
        try {
            onProgress("STARTING PIPELINE", "Loading cryptographic glyph definitions...", 0.02f, 295)

            // Step 0: Start key generation on stream.testuk.org
            onProgress("INITIALIZING FLOW", "Contacting stream.testuk.org to start session...", 0.05f, 290)
            val startRequest = Request.Builder()
                .url("https://stream.testuk.org/api/start-key-generation")
                .header("User-Agent", userAgent)
                .header("Origin", "https://stream.testuk.org")
                .header("Referer", "https://stream.testuk.org/keygenerate")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val r0 = client.newCall(startRequest).execute()
            val r0Body = r0.body?.string() ?: ""
            if (!r0.isSuccessful) {
                return@withContext KeyGenerationResult(false, errorMessage = "Init failed: ${r0.code}")
            }

            val startJson = JSONObject(r0Body)
            val redirectUrl = startJson.optString("redirect_url")
            if (redirectUrl.isEmpty()) {
                return@withContext KeyGenerationResult(false, errorMessage = "No redirect_url returned")
            }

            // Step 1: Visit prolink entry point
            onProgress("STARTING PROLINK", "Entering ProLink verification flow...", 0.10f, 280)
            val r1Request = Request.Builder()
                .url(redirectUrl)
                .header("User-Agent", userAgent)
                .header("Referer", "https://stream.testuk.org/")
                .get()
                .build()
            client.newCall(r1Request).execute().close()

            // Step 2+: Processing 7 steps
            var currUrl = "https://tipsguru.in/best-family-health-insurance-plans-2026-%E2%82%B95-25-lakh-coverage/"
            var stepNum = 1
            var finalJwtKey: String? = null

            while (stepNum <= 10) {
                val pageReq = Request.Builder()
                    .url(currUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", "https://tipsguru.in/")
                    .get()
                    .build()
                val pageResp = client.newCall(pageReq).execute()
                val pageHtml = pageResp.body?.string() ?: ""

                val pidMatcher = Pattern.compile("[\"']?postId[\"']?\\s*:\\s*(\\d+)").matcher(pageHtml)
                if (!pidMatcher.find()) {
                    break
                }
                val postId = pidMatcher.group(1) ?: break

                while (true) {
                    val formBody = FormBody.Builder()
                        .add("action", "wppro_add_post_cookie")
                        .add("post_id", postId)
                        .build()

                    val actionReq = Request.Builder()
                        .url("https://tipsguru.in/prolink-action.php")
                        .header("User-Agent", userAgent)
                        .header("Origin", "https://tipsguru.in")
                        .header("Referer", currUrl)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(formBody)
                        .build()

                    val actionResp = client.newCall(actionReq).execute()
                    val actionBody = actionResp.body?.string() ?: ""
                    val resJson = try { JSONObject(actionBody) } catch (e: Exception) { JSONObject() }

                    if (!resJson.optBoolean("success", false)) {
                        val remSec = resJson.optInt("remaining_seconds", 0)
                        if (remSec > 0) {
                            for (s in (remSec + 2) downTo 1) {
                                val currPct = minOf(0.92f, ((stepNum - 1) + (1.0f - s.toFloat() / (remSec + 3))) / 7.0f)
                                val estTime = s + (7 - stepNum) * 42
                                onProgress(
                                    "STEP $stepNum/7 TIMER ACTIVE",
                                    "Security countdown verification (${s}s remaining)...",
                                    currPct,
                                    estTime
                                )
                                delay(1000L)
                            }
                            continue
                        } else {
                            delay(5000L)
                            continue
                        }
                    }

                    onProgress(
                        "STEP $stepNum/7 VERIFIED",
                        "Step $stepNum accepted by verification server.",
                        minOf(0.90f, stepNum / 7.0f),
                        (7 - stepNum) * 42
                    )

                    if (resJson.optBoolean("captcha_required", false)) {
                        onProgress("SOLVING CAPTCHA", "Solving SVG vector CAPTCHA automatically...", 0.94f, 8)
                        val captchaId = resJson.optString("captcha_id")
                        val imgData = resJson.optString("captcha_image")

                        if (imgData.contains("base64,")) {
                            val b64Svg = imgData.split("base64,")[1]
                            val svgXml = String(Base64.decode(b64Svg, Base64.DEFAULT), Charsets.UTF_8)
                            val captchaAns = SvgCaptchaSolver.solve(svgXml)

                            onProgress("SUBMITTING CAPTCHA", "Submitting verification answer '$captchaAns'...", 0.96f, 5)
                            val capBody = FormBody.Builder()
                                .add("action", "wppro_add_post_cookie")
                                .add("post_id", postId)
                                .add("captcha_id", captchaId)
                                .add("captcha_answer", captchaAns)
                                .build()

                            val capReq = Request.Builder()
                                .url("https://tipsguru.in/prolink-action.php")
                                .header("User-Agent", userAgent)
                                .header("Origin", "https://tipsguru.in")
                                .header("Referer", currUrl)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .post(capBody)
                                .build()

                            val capResp = client.newCall(capReq).execute()
                            val capJson = try { JSONObject(capResp.body?.string() ?: "") } catch (e: Exception) { JSONObject() }

                            val finalUrl = capJson.optString("final_url")
                            if (finalUrl.isNotEmpty() && finalUrl.contains("key=")) {
                                finalJwtKey = extractJwt(finalUrl)
                                break
                            }
                        }
                    }

                    val finalUrl = resJson.optString("final_url")
                    if (finalUrl.isNotEmpty() && finalUrl.contains("key=")) {
                        finalJwtKey = extractJwt(finalUrl)
                        break
                    }

                    val nextUrl = resJson.optString("next_url")
                    if (nextUrl.isNotEmpty()) {
                        currUrl = nextUrl
                        stepNum++
                        break
                    } else {
                        break
                    }
                }

                if (finalJwtKey != null) break
            }

            if (finalJwtKey == null) {
                return@withContext KeyGenerationResult(false, errorMessage = "Flow did not return JWT key")
            }

            // Step Final: Verify Key with stream.testuk.org
            onProgress("VERIFYING 24H ACCESS", "Activating authenticated session on stream.testuk.org...", 0.98f, 2)
            val verifyPayload = JSONObject().apply {
                put("referrer", "https://tipsguru.in/")
                put("key", finalJwtKey)
            }

            val verifyReq = Request.Builder()
                .url("https://stream.testuk.org/api/verify-key")
                .header("User-Agent", userAgent)
                .header("Origin", "https://stream.testuk.org")
                .header("Referer", "https://stream.testuk.org/keyloginsuccess")
                .header("Content-Type", "application/json")
                .post(verifyPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val verifyResp = client.newCall(verifyReq).execute()
            val verifyBody = verifyResp.body?.string() ?: ""
            val verifyJson = try { JSONObject(verifyBody) } catch (e: Exception) { JSONObject() }

            val verifiedOk = verifyResp.isSuccessful && verifyJson.optBoolean("success", false)

            var sessionCookie: String? = null
            var sessionExpiry: Long = 0L

            for ((host, map) in cookieStore) {
                if (host.contains("testuk.org")) {
                    map["session"]?.let { sessionCookie = it.value }
                    map["session_expiry"]?.let { sessionExpiry = it.value.toLongOrNull() ?: 0L }
                }
            }

            if (sessionExpiry == 0L) {
                sessionExpiry = verifyJson.optLong("expiry", System.currentTimeMillis() + 86400000L)
            }

            if (verifiedOk && sessionCookie != null) {
                onProgress("ACCESS ACTIVATED", "24-Hour streaming key verified successfully!", 1.0f, 0)
                KeyGenerationResult(
                    success = true,
                    sessionCookie = sessionCookie,
                    sessionExpiry = sessionExpiry,
                    jwtKey = finalJwtKey
                )
            } else {
                KeyGenerationResult(false, errorMessage = "Verification rejected by backend")
            }

        } catch (e: Exception) {
            KeyGenerationResult(false, errorMessage = e.localizedMessage ?: "Unknown network error")
        }
    }

    private fun extractJwt(url: String): String? {
        val m = Pattern.compile("key=([A-Za-z0-9_\\-\\.]+)").matcher(url)
        return if (m.find()) m.group(1) else null
    }
}
