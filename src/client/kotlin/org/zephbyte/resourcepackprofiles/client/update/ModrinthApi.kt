package org.zephbyte.resourcepackprofiles.client.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A file attached to a Modrinth version. [hashes] maps algorithm name ("sha1", "sha512") to hex. */
data class ApiFile(
    val hashes: Map<String, String> = emptyMap(),
    val url: String = "",
    val filename: String = "",
    val primary: Boolean = false,
    val size: Long = 0
)

data class ApiVersion(
    val id: String = "",
    @SerializedName("project_id") val projectId: String = "",
    @SerializedName("version_number") val versionNumber: String = "",
    @SerializedName("date_published") val datePublished: String = "",
    @SerializedName("game_versions") val gameVersions: List<String> = emptyList(),
    val name: String = "",
    val files: List<ApiFile> = emptyList()
)

private data class ApiProject(val id: String = "", val title: String = "")

private data class ApiSearch(val hits: List<ProjectHit> = emptyList())

/** One result of a project search. */
data class ProjectHit(
    @SerializedName("project_id") val projectId: String = "",
    val title: String = ""
)

/**
 * Minimal client for the keyless parts of the Modrinth v2 API. Every call blocks, so use it off
 * the render thread.
 */
object ModrinthApi {
    private const val BASE = "https://api.modrinth.com/v2"
    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // Modrinth asks API consumers to identify themselves with a descriptive User-Agent.
    private val userAgent: String by lazy {
        val version = FabricLoader.getInstance().getModContainer("resourcepackprofiles")
            .map { it.metadata.version.friendlyString }.orElse("dev")
        "ZephByte/ResourcePackProfiles/$version (https://github.com/ZephByte/ResourcePackProfiles)"
    }

    /** Looks up the version each file hash (SHA-1) belongs to. Unknown hashes are absent. */
    fun versionsByHash(hashes: Collection<String>): Map<String, ApiVersion> {
        if (hashes.isEmpty()) return emptyMap()
        return postForVersions("/version_files", mapOf("hashes" to hashes, "algorithm" to "sha1"))
    }

    /**
     * For each file hash, returns the newest version of the same project that supports
     * [gameVersion] as a resource pack. Hashes with no compatible version are absent.
     */
    fun latestByHash(hashes: Collection<String>, gameVersion: String): Map<String, ApiVersion> {
        if (hashes.isEmpty()) return emptyMap()
        return postForVersions(
            "/version_files/update",
            mapOf(
                "hashes" to hashes,
                "algorithm" to "sha1",
                "loaders" to listOf("minecraft"),
                "game_versions" to listOf(gameVersion)
            )
        )
    }

    /** Maps project id to its display title. */
    fun projectTitles(ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val encoded = URLEncoder.encode(gson.toJson(ids.distinct()), StandardCharsets.UTF_8)
        val request = baseRequest("/projects?ids=$encoded").GET().build()
        val type = object : TypeToken<List<ApiProject>>() {}.type
        val projects: List<ApiProject> = gson.fromJson(send(request), type) ?: emptyList()
        return projects.associate { it.id to it.title }
    }

    /** Searches resource pack projects by name, best match first. */
    fun searchProjects(query: String, limit: Int = 8): List<ProjectHit> {
        val facets = URLEncoder.encode("[[\"project_type:resourcepack\"]]", StandardCharsets.UTF_8)
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val request = baseRequest("/search?query=$q&facets=$facets&limit=$limit").GET().build()
        return gson.fromJson(send(request), ApiSearch::class.java)?.hits ?: emptyList()
    }

    /** Fetches versions by their Modrinth version id. Unknown ids are absent from the result. */
    fun versionsByIds(ids: Collection<String>): List<ApiVersion> {
        if (ids.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(gson.toJson(ids.distinct()), StandardCharsets.UTF_8)
        val request = baseRequest("/versions?ids=$encoded").GET().build()
        val type = object : TypeToken<List<ApiVersion>>() {}.type
        return gson.fromJson(send(request), type) ?: emptyList()
    }

    /** Every resource pack version of a project, in whatever order Modrinth returns them. */
    fun projectVersions(projectId: String): List<ApiVersion> {
        val loaders = URLEncoder.encode("[\"minecraft\"]", StandardCharsets.UTF_8)
        val id = URLEncoder.encode(projectId, StandardCharsets.UTF_8)
        val request = baseRequest("/project/$id/version?loaders=$loaders").GET().build()
        val type = object : TypeToken<List<ApiVersion>>() {}.type
        return gson.fromJson(send(request), type) ?: emptyList()
    }

    /** Opens a streaming GET for [url]. The caller must close the stream. */
    fun openStream(url: String): InputStream {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", userAgent)
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw IOException("Download failed with HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private fun postForVersions(path: String, body: Map<String, Any>): Map<String, ApiVersion> {
        val request = baseRequest(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        val type = object : TypeToken<Map<String, ApiVersion>>() {}.type
        return gson.fromJson(send(request), type) ?: emptyMap()
    }

    private fun baseRequest(pathAndQuery: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(BASE + pathAndQuery))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent)

    private fun send(request: HttpRequest): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("Modrinth API returned HTTP ${response.statusCode()}")
        }
        return response.body()
    }
}
