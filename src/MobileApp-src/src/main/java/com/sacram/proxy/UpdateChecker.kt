package com.sacram.proxy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Shared update-check/download logic, used by both the manual "Check for
 * updates" button in [MainActivity] and the hourly background [UpdateWorker].
 *
 * Background checks NEVER call the installer - they only download the APK and
 * flip [AppState.updateAvailable], so the user always makes the final call to
 * install. This mirrors what a manual check does, minus the auto-launch.
 */
object UpdateChecker {
    private const val REPO_API = "https://api.github.com/repos/SynacNipo/Sacram/releases/latest"
    private const val WORK_NAME = "sacram_update_check"

    /** Parsed release metadata for the release picker dialog. */
    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val publishedAt: String,
        val isBeta: Boolean,
        val apkSize: Long,
        val apkUrl: String
    ) {
        val version: String
            get() {
                val m = Regex("""v?(\d+\.\d+)""").find(tag)
                return m?.groupValues?.get(1) ?: tag
            }

        val label: String
            get() = name.ifEmpty { tag }

        val sizeLabel: String
            get() = when {
                apkSize <= 0 -> "—"
                apkSize < 1024 -> "$apkSize B"
                apkSize < 1048576 -> "${apkSize / 1024} KB"
                else -> "${"%.1f".format(apkSize / 1048576.0)} MB"
            }

        val dateLabel: String
            get() = try {
                val raw = publishedAt.substringBefore("+").substringBefore("Z").substringBefore(".")
                val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .parse(raw)
                if (parsed != null) {
                    java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.US).format(parsed)
                } else {
                    publishedAt.substringBefore("T")
                }
            } catch (_: Exception) {
                publishedAt.substringBefore("T")
            }
    }

    /** Fetch all releases (stable + beta) and return parsed [ReleaseInfo] list. */
    fun fetchAllReleases(): List<ReleaseInfo> {
        return try {
            val conn = URL(REPO_LIST).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Sacram-App")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            try {
                if (conn.responseCode != 200) return emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val releases = mutableListOf<ReleaseInfo>()

                // Split the JSON array into per-release objects by matching top-level braces.
                // Each chunk starts after a "{" and ends at the matching "}". This avoids
                // needing a full JSON parser while still correctly handling nested objects
                // (e.g. asset arrays with their own braces).
                val chunks = mutableListOf<String>()
                var depth = 0
                var start = -1
                for (i in body.indices) {
                    when (body[i]) {
                        '{' -> { if (depth == 0) start = i; depth++ }
                        '}' -> { depth--; if (depth == 0 && start >= 0) { chunks.add(body.substring(start, i + 1)); start = -1 } }
                    }
                }

                for (chunk in chunks) {
                    if (Regex(""""draft"\s*:\s*true""").containsMatchIn(chunk)) continue
                    val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: continue
                    val name = Regex(""""name"\s*:\s*"([^"]*)"""").find(chunk)?.groupValues?.get(1) ?: ""
                    val publishedAt = Regex(""""published_at"\s*:\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: ""
                    val isBeta = "networkingpatch" in tag
                    // Find the APK asset inside the release's assets array.
                    val assetsMatch = Regex(""""assets"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(chunk)
                    val assetsSection = assetsMatch?.groupValues?.get(1) ?: ""
                    val apkMatch = Regex(""""browser_download_url"\s*:\s*"([^"]*sacram\.apk[^"]*)"""").find(assetsSection)
                    val apkUrl = apkMatch?.groupValues?.get(1)
                        ?: "https://github.com/SynacNipo/Sacram/releases/download/$tag/sacram.apk"
                    val sizeMatch = Regex(""""size"\s*:\s*(\d+)""").find(assetsSection)
                    val apkSize = sizeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    releases.add(ReleaseInfo(tag, name, publishedAt, isBeta, apkSize, apkUrl))
                }
                releases
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Schedule (or re-affirm) the background update check at [intervalHours].
     * Pass 0 (or less) to disable background checks entirely - any previously
     * scheduled work is cancelled. Uses REPLACE so changing the interval (or
     * disabling) takes effect immediately instead of waiting for the old
     * window to elapse.
     */
    fun scheduleCheck(context: Context, intervalHours: Int) {
        try {
            if (intervalHours <= 0) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(intervalHours.toLong().coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        } catch (_: Exception) {
        }
    }

    fun fetchLatestTag(): String? {
        return try {
            val conn = URL(REPO_API).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Sacram-App")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val m = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body) ?: return null
                m.groupValues[1]
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Downloads the release APK for [tag], overwriting any previous download. */
    fun downloadApk(context: Context, tag: String, onProgress: (Int) -> Unit = {}): File? {
        return try {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "updates")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "sacram.apk")
            val conn = URL("https://github.com/SynacNipo/Sacram/releases/download/$tag/sacram.apk")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Sacram-App")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            try {
                if (conn.responseCode !in 200..299) return null
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) runCatching { onProgress((downloaded * 100 / total).toInt()) }
                        }
                    }
                }
                file
            } catch (_: Exception) {
                runCatching { file.delete() }
                null
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun downloadedApkFile(context: Context): File {
        return try {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(File(base, "updates"), "sacram.apk")
        } catch (_: Exception) {
            File(context.filesDir, "sacram.apk")
        }
    }

    private fun parseVersion(tag: String): Pair<Int, Int>? {
        val m = Regex("""v?(\d+)\.(\d+)""").find(tag) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return a to b
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        return a.first > b.first || (a.first == b.first && a.second > b.second)
    }
}

/**
 * Runs on WorkManager's schedule (~hourly, subject to Android's usual battery
 * deferral). Checks GitHub for a newer release and, if found, downloads it
 * silently in the background. Never launches the installer - that always
 * requires an explicit tap from [MainActivity], reached via
 * [AppState.updateAvailable].
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val latest = UpdateChecker.fetchLatestTag() ?: return Result.retry()
            if (!UpdateChecker.isNewer(latest, BuildConfig.VERSION_NAME)) {
                AppState.updateAvailable.value = null
                return Result.success()
            }
            // Already downloaded this exact version and still waiting on the
            // user to tap install - don't re-download every hour.
            if (AppState.updateAvailable.value == latest && UpdateChecker.downloadedApkFile(applicationContext).exists()) {
                return Result.success()
            }
            val file = UpdateChecker.downloadApk(applicationContext, latest)
            if (file != null) {
                AppState.updateAvailable.value = latest
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
