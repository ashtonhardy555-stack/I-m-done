package com.freeflix.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.freeflix.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Talks to the GitHub Releases API for the project repo and enables real in-app
 * self-update: it downloads the APK asset straight to the device cache and then
 * launches the system package installer.
 *
 * The CI workflow publishes releases tagged `v<versionName>-build<runNumber>`
 * (for example `v1.1.1-build444`) with a single `app-debug.apk` asset. The
 * run-number suffix is what we use to decide whether a release is newer than
 * the installed build — the semantic version part (1.1.1) is usually identical
 * across consecutive CI builds, so comparing it alone would always report
 * "up to date". We therefore compare [BuildConfig.VERSION_CODE] (which the
 * workflow injects as the run number) against the build number parsed from the
 * latest tag, falling back to semantic comparison when no build number exists.
 */
object AutoUpdater {

    private const val TAG = "AutoUpdater"

    private const val RELEASES_API =
        "https://api.github.com/repos/ashtonhardy555-stack/I-m-done/releases/latest"

    private const val USER_AGENT = "Netflix-App/${BuildConfig.VERSION_NAME} (Android)"

    /** Outcome of a single release check. */
    sealed class CheckResult {
        /** A newer release is available on GitHub. */
        data class UpdateAvailable(
            val latestTag: String,
            val releaseName: String,
            val releaseNotes: String,
            val apkUrl: String,
            val releaseUrl: String,
            val publishedAt: String,
            val apkSize: Long
        ) : CheckResult()

        /** Installed build matches or is newer than the latest release. */
        object UpToDate : CheckResult()

        /** Network / parsing failure. */
        data class Error(val message: String) : CheckResult()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetches the latest release metadata from GitHub and compares it to the
     * currently installed build. Always runs off the main thread.
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("User-Agent", USER_AGENT)          // required by GitHub
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CheckResult.Error(
                        "GitHub returned HTTP ${response.code}"
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext CheckResult.Error("Empty response from GitHub")

                val obj = JSONObject(body)
                val tag = obj.optString("tag_name", "")
                if (tag.isBlank()) {
                    return@withContext CheckResult.Error("Release has no tag name")
                }

                // Locate the .apk asset (CI publishes exactly one).
                val apkUrl: String?
                val apkSize: Long
                val assets = obj.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    var url: String? = null
                    var size = 0L
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size", 0L)
                            break
                        }
                    }
                    apkUrl = url
                    apkSize = size
                } else {
                    apkUrl = null
                    apkSize = 0L
                }

                val releaseUrl = obj.optString("html_url", "")
                val releaseName = obj.optString("name", tag)
                val releaseNotes = obj.optString("body", "No release notes.")
                val publishedAt = obj.optString("published_at", "").take(10)

                if (apkUrl.isNullOrBlank()) {
                    return@withContext CheckResult.Error("Latest release has no APK asset")
                }

                if (isNewerRelease(tag, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
                    CheckResult.UpdateAvailable(
                        latestTag = tag,
                        releaseName = releaseName,
                        releaseNotes = releaseNotes,
                        apkUrl = apkUrl,
                        releaseUrl = releaseUrl,
                        publishedAt = publishedAt,
                        apkSize = apkSize
                    )
                } else {
                    CheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            CheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Downloads the given APK URL into the app cache and returns the local
     * [File]. Reports progress via [onProgress] as a 0–100 percentage.
     */
    suspend fun downloadApk(
        apkUrl: String,
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "APK download failed: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val total = body.contentLength()

                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val outFile = File(dir, "mario-cart-update.apk")
                if (outFile.exists()) outFile.delete()

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                outFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed", e)
            null
        }
    }

    /**
     * Launches the system package installer for a downloaded APK file.
     */
    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch installer", e)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Version comparison
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Decides whether the GitHub release [releaseTag] is newer than the running
     * app whose semantic version is [currentVersion] and build number is
     * [currentBuildCode].
     *
     * Release tags look like `v1.1.1-build444`. We parse out the build number
     * (`444`) and compare it against [currentBuildCode]; when a build number is
     * present on both sides that is authoritative because the semantic portion
     * is often identical across consecutive CI builds. If either side lacks a
     * build number we fall back to a dotted-numeric semantic comparison.
     */
    private fun isNewerRelease(
        releaseTag: String,
        currentVersion: String,
        currentBuildCode: Int
    ): Boolean {
        val releaseBuild = parseBuildNumber(releaseTag)
        if (releaseBuild != null && releaseBuild > 0) {
            // Build number wins: compare against the installed versionCode.
            // currentBuildCode is injected by CI as the run number; for local
            // builds it is the value in build.gradle.kts (e.g. 3), which is
            // always less than any CI run number, so a release is correctly
            // seen as newer.
            return releaseBuild > currentBuildCode
        }
        // No build number → semantic comparison of the versionName portion.
        val releaseSem = stripBuildSuffix(releaseTag).trimStart('v')
        return isNewerSemantic(releaseSem, currentVersion.trimStart('v'))
    }

    /** Extracts the integer after `-build` in a tag, e.g. `v1.1.1-build444` → `444`. */
    private fun parseBuildNumber(tag: String): Int? {
        val idx = tag.indexOf("build", ignoreCase = true)
        if (idx < 0) return null
        val after = tag.substring(idx + "build".length)
        val num = after.takeWhile { it.isDigit() }
        return num.toIntOrNull()
    }

    /** Removes a trailing `-buildNNN` suffix, leaving the semantic version. */
    private fun stripBuildSuffix(tag: String): String {
        val idx = tag.indexOf("build", ignoreCase = true)
        return if (idx > 0) {
            // walk back over any '-' separator
            val cut = tag.lastIndexOf('-', idx)
            if (cut > 0) tag.substring(0, cut) else tag.substring(0, idx)
        } else tag
    }

    /** Dotted-numeric semantic comparison: `1.12` > `1.2`. */
    private fun isNewerSemantic(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.substringBefore("-").toIntOrNull() }
        val c = current.split(".").mapNotNull { it.substringBefore("-").toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Convenience: one-shot check + prompt (used on app launch)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Checks for an update and, if one is available, shows a simple alert
     * asking the user to download. Kept for the lightweight startup path; the
     * full UI lives in [com.freeflix.app.ui.updates.UpdatesScreen].
     */
    suspend fun checkAndPrompt(context: Context) = withContext(Dispatchers.Main) {
        when (val result = checkForUpdate()) {
            is CheckResult.UpdateAvailable -> {
                AlertDialog.Builder(context)
                    .setTitle("Update Available")
                    .setMessage(
                        "Version ${result.releaseName} is ready.\n" +
                            "Download and install the update now?"
                    )
                    .setPositiveButton("Download") { _, _ ->
                        // Hand off to the Updates screen for the full flow.
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(result.releaseUrl)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
            else -> { /* UpToDate / Error: stay silent on startup */ }
        }
    }

    @Suppress("unused")
    private fun logBuildInfo() {
        Log.i(
            TAG,
            "Installed: versionName=${BuildConfig.VERSION_NAME} " +
                "versionCode=${BuildConfig.VERSION_CODE} (Build.VERSION.SDK_INT=${Build.VERSION.SDK_INT})"
        )
    }
}
