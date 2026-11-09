package dev.restudio.dotnet.nuget

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** One search result of the NuGet v3 search API. [versions] are in the order of the API: oldest first. */
data class NuGetPackage(val id: String, val version: String, val description: String, val versions: List<String>, val totalDownloads: Long)

/**
 * Searches nuget.org with the NuGet v3 search API (`SearchQueryService` of the service index).
 *
 * The calls block on the network. Call them from a background thread only.
 */
object NuGetClient {
  private const val SERVICE_INDEX = "https://api.nuget.org/v3/index.json"
  private const val FALLBACK_SEARCH = "https://azuresearch-usnc.nuget.org/query"
  private const val PAGE_SIZE = 30
  private val LOG = logger<NuGetClient>()

  @Volatile
  private var searchUrl: String? = null

  fun search(query: String, prerelease: Boolean): List<NuGetPackage> {
    val url = searchEndpoint() + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
              "&take=$PAGE_SIZE&prerelease=$prerelease&semVerLevel=2.0.0"
    return parseSearch(HttpRequests.request(url).accept("application/json").readString())
  }

  fun parseSearch(json: String): List<NuGetPackage> {
    val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return emptyList()
    return data.mapNotNull { element ->
      val item = element as? JsonObject ?: return@mapNotNull null
      val id = item.get("id")?.asString ?: return@mapNotNull null
      NuGetPackage(
        id = id,
        version = item.get("version")?.asString.orEmpty(),
        description = item.get("description")?.asString.orEmpty(),
        versions = item.getAsJsonArray("versions")?.mapNotNull { (it as? JsonObject)?.get("version")?.asString }.orEmpty(),
        totalDownloads = item.get("totalDownloads")?.asLong ?: 0L,
      )
    }
  }

  private fun searchEndpoint(): String {
    searchUrl?.let { return it }
    val url = try {
      val resources = JsonParser.parseString(HttpRequests.request(SERVICE_INDEX).readString()).asJsonObject.getAsJsonArray("resources")
      resources.mapNotNull { it as? JsonObject }
        .firstOrNull { it.get("@type")?.asString?.startsWith("SearchQueryService") == true }
        ?.get("@id")?.asString ?: FALLBACK_SEARCH
    }
    catch (e: Exception) {
      LOG.info("Cannot read the NuGet service index, so the fixed search URL is used", e)
      FALLBACK_SEARCH
    }
    searchUrl = url
    return url
  }
}
