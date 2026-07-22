package com.ashtonhardy.piratesfilmcove.data.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TMDB_BASE = "https://api.themoviedb.org/3/"
    private const val STREAMING_BACKEND_BASE = "https://imdone-stream-backend.onrender.com"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val tmdbApi: TmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(TMDB_BASE)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    val streamingBackendApi: StreamingBackendApi by lazy {
        Retrofit.Builder()
            .baseUrl(STREAMING_BACKEND_BASE)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StreamingBackendApi::class.java)
    }
}
