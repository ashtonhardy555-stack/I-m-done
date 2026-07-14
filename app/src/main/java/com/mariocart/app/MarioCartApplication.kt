package com.mariocart.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.size.Precision
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * MarioCartApplication — app-level setup.
 *
 * The single most important thing configured here is the Coil [ImageLoader]:
 *
 *  • A generous **memory cache** (25 % of available RAM) so posters that have
 *    already been decoded don't get re-decoded on every scroll pass.
 *  • A persistent **disk cache** (50 MB) so images survive process death and
 *    aren't re-downloaded on every cold launch.
 *  • Hardware-friendly **INEXACT precision** — Coil decodes at the exact
 *    pixel size of the target, not the full source resolution. Combined with
 *    the w185 poster URLs in [TmdbItem], this is the single biggest win for
 *    home-screen scroll smoothness.
 *  • Crossfade enabled so images fade in instead of popping.
 *
 * Without this, Coil uses its default loader which has no disk cache and
 * defaults to AUTOMATIC precision (full-resolution decode), which is why the
 * home screen was janky: every AsyncImage re-decoded a 342px-wide PNG from
 * the network on every recomposition.
 */
class MarioCartApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        // A shared OkHttpClient with sensible timeouts for image downloads.
        val imageClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50 MB
                    .build()
            }
            // Decode at the exact size of the ImageView — never the full
            // source resolution. This saves both memory and CPU on scroll.
            .precision(Precision.INEXACT)
            // Crossfade so images fade in smoothly instead of popping.
            .crossfade(true)
            // Aggressive caching: both memory and disk are enabled for reads
            // and writes so repeated scroll passes hit the cache.
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
