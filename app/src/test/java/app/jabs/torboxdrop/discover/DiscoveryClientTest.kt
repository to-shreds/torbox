package app.jabs.torboxdrop.discover

import app.jabs.torboxdrop.data.CachedDownload
import app.jabs.torboxdrop.data.Json
import app.jabs.torboxdrop.data.JsonValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiscoveryClientTest {
    private val hash = "b".repeat(40)
    private fun json(text: String) = Json.parse(text) as JsonValue.Object

    @Test fun supportedCatalogsOnlyAndRequiredExtraHandling() {
        val catalogs = DiscoveryWire.catalogs(json("""{"catalogs":[
            {"id":"top","type":"movie","name":"Popular","extra":[{"name":"genre","options":["Comedy"]},{"name":"skip"},{"name":"search"}]},
            {"id":"weird","type":"movie","extra":[{"name":"custom","isRequired":true}]},
            {"id":"top","type":"channel"}, {"id":"../../evil","type":"movie"}] }"""))
        assertEquals(1, catalogs.size)
        assertTrue(catalogs.single().searchable)
        assertEquals(listOf("Comedy"), catalogs.single().genres)
    }
    @Test fun paginationCountsRawRowsNotOnlyUsableRows() {
        val page = DiscoveryWire.titles(json("""{"metas":[{"id":"tt1234567","name":"A"},{"id":"bad","name":"B"}]}"""))
        assertEquals(2, page.sourceCount)
        assertEquals(1, page.titles.size)
    }
    @Test(expected = DiscoveryFailure::class) fun missingCatalogDataIsNotAnEmptyResult() { DiscoveryWire.titles(json("{}")) }
    @Test(expected = DiscoveryFailure::class) fun errorStreamIsNotAnUncachedResult() {
        DiscoveryWire.candidates(json("""{"streams":[{"name":"Service down","url":"https://example.com/error.mp4"}]}"""))
    }
    @Test fun streamHashesAreDeduplicatedAndThirdPartyUrlsIgnored() {
        val rows = DiscoveryWire.candidates(json("""{"streams":[
            {"infoHash":"$hash","title":"A.1080p\nprovider","url":"https://untrusted/secret"},
            {"infoHash":"${hash.uppercase()}","title":"same"}, {"infoHash":"invalid"}]}"""))
        assertEquals(1, rows.size)
        assertEquals("A.1080p", rows.single().name)
    }
    @Test fun cacheMustConfirmExactCandidateAndRejectContradictoryHash() {
        val candidate = TorrentCandidate(hash, "A.1080p.x265", "")
        assertTrue(DiscoveryRepository.confirmed(listOf(candidate), emptyMap(), 10).isEmpty())
        assertTrue(DiscoveryRepository.confirmed(listOf(candidate), mapOf(hash to CachedDownload("c".repeat(40))), 10).isEmpty())
        val result = DiscoveryRepository.confirmed(listOf(candidate), mapOf(hash to CachedDownload(hash, size = 12345)), 10).single()
        assertEquals(12345L, result.size)
        assertEquals("HEVC", result.codec)
        assertEquals(10L, result.checkedAt)
    }
    @Test fun sourceRequestsHaveNoAuthorizationOrCookiesAndUseExactEpisode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"streams":[]}"""))
            val client = DiscoveryClient(catalogBase = server.url("/"), streamsBase = server.url("/"))
            client.candidates(DiscoveryFilter(MediaKind.TV, season = 3, episode = 7), DiscoveryTitle("tt1234567", "A", "", emptyList()))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/stream/series/tt1234567:3:7.json", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun searchesEncodePathAndPaginationCorrectly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"metas":[]}"""))
            val client = DiscoveryClient(catalogBase = server.url("/"), streamsBase = server.url("/"))
            client.titles(DiscoveryFilter(query = "A/B & C", genre = "Sci-Fi"),
                DiscoveryCatalog("top", "Popular", MediaKind.MOVIE, emptyList(), true, true), 100)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/catalog/movie/top/search=A%2FB%20%26%20C&genre=Sci-Fi&skip=100.json", request.path)
        }
    }
    @Test fun rateLimitCarriesCooldownAndNoAutomaticRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "90"))
            val client = DiscoveryClient(catalogBase = server.url("/"), streamsBase = server.url("/"))
            try { client.catalogs(); fail("Should fail") } catch (failure: DiscoveryFailure) { assertEquals(90L, failure.retryAfterSeconds) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun redirectNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
            val client = DiscoveryClient(catalogBase = server.url("/"), streamsBase = server.url("/"))
            try { client.catalogs(); fail("Should fail") } catch (_: DiscoveryFailure) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun emptyUpstreamArrayMeansNoCandidatesWithoutCacheCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"streams":[]}"""))
            val repo = DiscoveryRepository(DiscoveryClient(catalogBase = server.url("/"), streamsBase = server.url("/"))) { error("Must not query cache") }
            assertTrue(repo.releases(DiscoveryFilter(), DiscoveryTitle("tt1234567", "A", "", emptyList())).isEmpty())
        }
    }
}
