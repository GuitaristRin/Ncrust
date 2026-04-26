package com.takahashirinta.ncrust.network

import android.content.Context
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://music.163.com/"
    private var currentCookie: String? = null

    fun init(context: Context) {
        currentCookie = CookieManager.getCookie(context)
    }

    fun updateCookie(cookie: String?) {
        currentCookie = cookie
    }

    fun getCookie(): String? = currentCookie

    val api: NcmApi by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(CookieInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NcmApi::class.java)
    }

    fun eapiPost(url: String, payload: Map<String, String>): Response {
        val anyPayload = payload.mapValues { it.value as Any }
        val params = EapiCrypto.encryptParams(url, anyPayload)
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val requestBody = FormBody.Builder()
            .add("params", params)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()

        return client.newCall(request).execute()
    }

    private class CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val cookie = currentCookie
            val newRequest = if (!cookie.isNullOrBlank()) {
                originalRequest.newBuilder()
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://music.163.com/")
                    .build()
            } else {
                originalRequest
            }
            return chain.proceed(newRequest)
        }
    }
}