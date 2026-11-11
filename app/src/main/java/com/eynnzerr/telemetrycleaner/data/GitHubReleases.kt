package com.eynnzerr.telemetrycleaner.data

import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Describes the result of checking the project's public GitHub releases. */
internal sealed interface ReleaseCheckResult {
    /** A newer stable release is available at [releaseUrl]. */
    data class UpdateAvailable(val version: String, val releaseUrl: String) : ReleaseCheckResult

    /** The latest public release [version] is no newer than the installed version. */
    data class UpToDate(val version: String) : ReleaseCheckResult

    /** GitHub has no latest public release for this repository. */
    data object NoRelease : ReleaseCheckResult

    /** The request or version comparison could not be completed. */
    data object Failed : ReleaseCheckResult
}

/** Matches the owner and repository path segments used by the release API. */
private val repositoryPattern = Regex("[A-Za-z0-9-]+/(?!\\.{1,2}$)[A-Za-z0-9_.-]+")

/** Matches numeric versions with optional release-tag prefixes and SemVer suffixes. */
private val versionPattern = Regex(
    """[vV]?(\d+(?:\.\d+){0,2})(-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
)

/** A normalized version used to compare the installed build with a stable release. */
private data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val stable: Boolean) :
    Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(
        this,
        other,
        ReleaseVersion::major,
        ReleaseVersion::minor,
        ReleaseVersion::patch,
        ReleaseVersion::stable,
    )
}

/** Normalizes short versions such as 1.0 and release tags such as v1.0.0. */
private fun parseVersion(value: String): ReleaseVersion? {
    val match = versionPattern.matchEntire(value) ?: return null
    val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    return ReleaseVersion(
        major = numbers[0],
        minor = numbers.getOrElse(1) { 0 },
        patch = numbers.getOrElse(2) { 0 },
        stable = match.groups[2] == null,
    )
}

/** Compares the installed [currentVersion] with the latest stable release of [repository] (`owner/name`). */
internal suspend fun checkLatestRelease(repository: String, currentVersion: String): ReleaseCheckResult =
    withContext(Dispatchers.IO) {
        if (!repositoryPattern.matches(repository)) return@withContext ReleaseCheckResult.Failed
        val installed = parseVersion(currentVersion) ?: return@withContext ReleaseCheckResult.Failed

        val connection = try {
            URL("https://api.github.com/repos/$repository/releases/latest").openConnection() as HttpsURLConnection
        } catch (_: IOException) {
            return@withContext ReleaseCheckResult.Failed
        }
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "TelemetryCleaner")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> ReleaseCheckResult.NoRelease
                HttpURLConnection.HTTP_OK -> {
                    val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = release.getString("tag_name")
                    val latest = parseVersion(tag)?.takeIf { it.stable }
                        ?: return@withContext ReleaseCheckResult.Failed
                    if (latest > installed) {
                        val releaseUrl = "https://github.com/$repository/releases/tag/${Uri.encode(tag)}"
                        ReleaseCheckResult.UpdateAvailable(tag, releaseUrl)
                    } else {
                        ReleaseCheckResult.UpToDate(tag)
                    }
                }
                else -> ReleaseCheckResult.Failed
            }
        } catch (_: IOException) {
            ReleaseCheckResult.Failed
        } catch (_: JSONException) {
            ReleaseCheckResult.Failed
        } finally {
            connection.disconnect()
        }
    }
