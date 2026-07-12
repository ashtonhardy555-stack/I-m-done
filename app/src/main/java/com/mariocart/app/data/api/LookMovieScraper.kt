package com.mariocart.app.data.api

import okhttp3.*
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import android.util.Log

class LookMovieScraper {
    private val TAG = "LookMovieScraper"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Referer", "https://www.lookmovie2.to/")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    fun search(query: String): List<String> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.lookmovie2.to/search/?q=$encodedQuery"
        
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Search failed: ${response.code}")
                    return emptyList()
                }
                val html = response.body.string()
                val doc = Jsoup.parse(html)
                return doc.select("a[href^=/movie/], a[href^=/tv/]").map { it.attr("abs:href") }.distinct()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            return emptyList()
        }
    }

    fun getStreamUrl(pageUrl: String): String? {
        val request = Request.Builder()
            .url(pageUrl)
            .headers(headers)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body.string()
                
                val hlsRegex = """(https?://[^\s"']+\.m3u8[^\s"']*)""".toRegex()
                hlsRegex.find(html)?.value?.let { 
                    Log.d(TAG, "Found HLS: $it")
                    return it 
                }
                
                val mp4Regex = """(https?://[^\s"']+\.(mp4|webm)[^\s"']*)""".toRegex()
                mp4Regex.find(html)?.value?.let { return it }
                
                Log.w(TAG, "No stream found on page")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream extraction error", e)
            return null
        }
    }
}