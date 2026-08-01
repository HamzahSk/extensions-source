package eu.kanade.tachiyomi.extension.id.mirrorinkomik

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

@Source
abstract class MirrorInKomik :
    HttpSource(),
    ConfigurableSource {

    override val name = "MirrorInKomik"

    override val baseUrl = "https://mirrorinkomik.my.id"

    override val lang = "id"

    override val supportsLatest = true

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val usernamePref by lazy { preferences.getString(PREF_USERNAME, "") }
    private val passwordPref by lazy { preferences.getString(PREF_PASSWORD, "") }

    // "Komik" is used by the site for the type of the load-more button on the latest page.
    private var lastId: String? = null
    private var lastScore: String? = null

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::loginInterceptor)
        .addInterceptor(::thumbnailInterceptor)
        .rateLimit(2) { it.host == baseUrlHost }
        .build()

    // The reader page requires a logged-in session; the listchap endpoint requires
    // the full browser header set (User-Agent + Accept-Language + XHR + Sec-Fetch trio).
    private val readerHeaders: Headers = headersBuilder()
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Dest", "empty")
        .build()

    private val xhrHeaders: Headers = headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only chapter reader pages require authentication.
        if (!request.url.encodedPath.startsWith("/chapter/") || request.url.encodedPath.contains("listchap")) {
            return chain.proceed(request)
        }
        if (!isLoggedIn()) {
            login()
        }
        return chain.proceed(request)
    }
    
    private fun thumbnailInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // Cek apakah request mengarah ke domain CDN gambar/thumbnail
        if (request.url.host == "cdngue.my.id") {
            val newRequest = request.newBuilder()
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Referer", "$baseUrl/")
                .build()
            return chain.proceed(newRequest)
        }
        
        // Lanjutkan request normal untuk URL lainnya (seperti API atau HTML)
        return chain.proceed(request)
    }

    private fun isLoggedIn(): Boolean = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == SESSION_COOKIE }

    private fun login() {
        val username = usernamePref.orEmpty()
        val password = passwordPref.orEmpty()
        if (username.isBlank() || password.isBlank()) {
            throw IOException("Set your MirrorInKomik username and password in the source settings to read chapters.")
        }

        // Fetch the login page to obtain the CSRF token.
        val loginPageRequest = GET("$baseUrl/login", headers)
        val loginPageResponse = client.newCall(loginPageRequest).execute()
        val document = loginPageResponse.use { it.asJsoup() }
        val csrf = document.selectFirst("input[name=csrf_test_name]")?.attr("value")
            ?: throw IOException("Could not load the login page.")

        val formBody = FormBody.Builder()
            .addEncoded("csrf_test_name", csrf)
            .addEncoded("login", username)
            .addEncoded("password", password)
            .build()
        val loginRequest = POST("$baseUrl/login", headersBuilder().build(), formBody)
        client.newCall(loginRequest).execute().use { response ->
            if (response.code !in 200..399) {
                throw IOException("Login failed (HTTP ${response.code}). Check your credentials.")
            }
        }
        if (!isLoggedIn()) {
            throw IOException("Login failed. Check your credentials.")
        }
    }

    // Popular: homepage "Popular Updates" section (12 cards), single page.
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("""div[name="popular_updates"] a.block.w-full""")
            .mapNotNull { mangaFromElement(it) }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    private fun mangaFromElement(element: Element): SManga? = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("img")?.attr("title")?.takeIf(String::isNotBlank) ?: return null
        thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")?.takeIf(String::isNotBlank)
            ?: element.selectFirst("img")?.attr("abs:src")
    }

    // Latest: /list-update then /loadmore-type?type=Komik&last_id=<id>
    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        lastId = null
        GET("$baseUrl/list-update", headers)
    } else {
        GET("$baseUrl/loadmore-type?type=Komik&last_id=$lastId", xhrHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body.string().trimStart()
        if (body.startsWith("{")) {
            val json = body.parseAs<LoadMoreResponse>()
            lastId = json.lastId?.takeIf { it != "0" }
            val document = Jsoup.parse(json.html)
            val mangas = document.select("a.komik-card").mapNotNull { mangaFromKomikCard(it) }
            return MangasPage(mangas, lastId != null && mangas.isNotEmpty())
        }
        val document = Jsoup.parse(body)
        val mangas = document.select("#komik-list a.komik-card").mapNotNull { mangaFromKomikCard(it) }
        lastId = document.selectFirst("#load-more")?.attr("data-last-id")?.takeIf { it != "0" }
        return MangasPage(mangas, lastId != null)
    }

    private fun mangaFromKomikCard(element: Element): SManga? = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(".komik-info h3")?.text()?.takeIf(String::isNotBlank) ?: return null
        thumbnail_url = element.selectFirst(".komik-cover img")?.attr("abs:src")
    }

    // Search: /cari?s=<query> then /loadmore-search?keyword=<query>&last_id=<id>&last_score=<score>
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            val typeFilter = filters.firstOrNull { it is TypeFilter } as? TypeFilter
            val genreFilter = filters.firstOrNull { it is GenreFilter } as? GenreFilter
            return when {
                genreFilter?.state != 0 -> {
                    val genre = genreFilter!!.values[genreFilter.state]
                    if (page == 1) {
                        lastId = null
                        GET("$baseUrl/Genre/$genre", headers)
                    } else {
                        GET("$baseUrl/loadmore-type?type=$genre&last_id=$lastId", xhrHeaders)
                    }
                }
                typeFilter?.state != 0 -> {
                    val type = typeFilter!!.values[typeFilter.state]
                    if (page == 1) {
                        lastId = null
                        GET("$baseUrl/$type", headers)
                    } else {
                        GET("$baseUrl/loadmore-type?type=$type&last_id=$lastId", xhrHeaders)
                    }
                }
                else -> popularMangaRequest(page)
            }
        }
        return if (page == 1) {
            lastId = null
            lastScore = null
            GET("$baseUrl/cari?s=$query", headers)
        } else {
            GET("$baseUrl/loadmore-search?keyword=$query&last_id=$lastId&last_score=$lastScore", xhrHeaders)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string().trimStart()
        if (body.startsWith("{")) {
            val json = body.parseAs<LoadMoreResponse>()
            lastId = json.lastId?.takeIf { it != "0" }
            lastScore = json.lastScore
            val document = Jsoup.parse(json.html)
            val mangas = searchResultsFrom(document)
            return MangasPage(mangas, lastId != null && mangas.isNotEmpty())
        }
        val document = Jsoup.parse(body)
        val mangas = searchResultsFrom(document)
        lastId = document.selectFirst("#load-more")?.attr("data-last-id")?.takeIf { it != "0" }
        lastScore = document.selectFirst("#load-more")?.attr("data-last-score")
        return MangasPage(mangas, lastId != null)
    }

    private fun searchResultsFrom(document: Document): List<SManga> {
        // Search rows (first page and loadmore fragment) share the same div structure.
        val rows = document.select("""div[class*="border-b-base-200"]""")
        if (rows.isNotEmpty()) {
            return rows.mapNotNull { mangaFromSearchRow(it) }
        }
        val genreCards = document.select("#genre-list .bsx, .bsx")
        if (genreCards.isNotEmpty()) {
            return genreCards.mapNotNull { mangaFromGenreCard(it) }
        }
        val cards = document.select("#komik-list a.komik-card, a.komik-card")
        if (cards.isNotEmpty()) {
            return cards.mapNotNull { mangaFromKomikCard(it) }
        }
        return emptyList()
    }

    private fun mangaFromSearchRow(element: Element): SManga? {
        // Skip the ad iframe row (no manga link).
        if (element.selectFirst("iframe") != null) return null
        val link = element.selectFirst("a[href]") ?: return null
        val title = element.selectFirst("h3 a")?.text()?.takeIf(String::isNotBlank) ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.attr("href"))
            this.title = title
            thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        }
    }

    private fun mangaFromGenreCard(element: Element): SManga? {
        if (element.selectFirst("iframe") != null) return null
        val link = element.selectFirst("a[title]") ?: return null
        val title = link.attr("title").takeIf(String::isNotBlank) ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.attr("href"))
            this.title = title
            thumbnail_url = link.selectFirst("img")?.attr("abs:src")
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val titleLink = document.selectFirst("h3 a.link-hover") ?: return@apply
            title = titleLink.text().takeIf(String::isNotBlank) ?: return@apply
            setUrlWithoutDomain(titleLink.attr("href"))
            thumbnail_url = document.selectFirst("img[data-src]")?.attr("abs:data-src")
                ?: document.selectFirst("img[src]")?.attr("abs:src")
            author = document.selectFirst("a[href*=\"cari?s=\"]")?.text()
            genre = document.select(".flex.items-center.flex-wrap span span")
                .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
                .filter { it != "," }
                .distinct()
                .joinToString(", ")
            description = document.selectFirst("div.limit-html-p")?.text()?.trim()
            status = when (document.selectFirst(".mt-1.text-xs span")?.text()?.trim()?.uppercase()) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a[href*=\"/chapter/\"]")
            .mapNotNull { link ->
                val name = link.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                SChapter.create().apply {
                    setUrlWithoutDomain(link.attr("href"))
                    this.name = name
                    date_upload = parseRelativeTime(link)
                }
            }
            .reversed()
    }

    private fun parseRelativeTime(link: Element): Long {
        // Chapter dates are relative Indonesian strings; fall back to 0 when unparseable.
        val row = link.parent()?.parent() ?: return 0L
        val text = row.selectFirst("time")?.text() ?: return 0L
        val pattern = Regex("""(\d+)\s+(menit|jam|hari|minggu|bulan|tahun) lalu""")
        val match = pattern.find(text) ?: return 0L
        val amount = match.groupValues[1].toLong()
        val unitSeconds = when (match.groupValues[2]) {
            "menit" -> 60L
            "jam" -> 60L * 60
            "hari" -> 60L * 60 * 24
            "minggu" -> 60L * 60 * 24 * 7
            "bulan" -> 60L * 60 * 24 * 30
            "tahun" -> 60L * 60 * 24 * 365
            else -> return 0L
        }
        return System.currentTimeMillis() / 1000 - amount * unitSeconds
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val token = document.selectFirst("#thisch")?.attr("data-token")
            ?: throw IOException("Could not find the reader token. Make sure you are logged in.")
        val listChapRequest = GET("$baseUrl/chapter/listchap,$token", readerHeaders)
        return client.newCall(listChapRequest).execute().use { listResponse ->
            if (!listResponse.isSuccessful) {
                throw IOException("Failed to load the chapter pages (HTTP ${listResponse.code}).")
            }
            val urls = listResponse.body.string().parseAs<List<String>>()
            if (urls.isEmpty() || urls.first().contains("Bookmark-Dulu")) {
                throw IOException("Chapter images are not available yet.")
            }
            urls.mapIndexed { index, url -> Page(index, url) }
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        TypeFilter(),
        GenreFilter(genreValues),
    )

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    private class TypeFilter : Filter.Select<String>("Type", arrayOf("Manga", "Manhwa", "Manhua"), 0)

    private class GenreFilter(values: Array<String>) : Filter.Select<String>("Genre", arrayOf("All") + values, 0)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(PREF_USERNAME, "MirrorInKomik username"))
        screen.addPreference(screen.editTextPreference(PREF_PASSWORD, "MirrorInKomik password", isPassword = true))
    }

    private fun PreferenceScreen.editTextPreference(key: String, summary: String, isPassword: Boolean = false): EditTextPreference = EditTextPreference(context).apply {
        this.key = key
        this.summary = summary
        dialogTitle = summary
        setDefaultValue("")
        if (isPassword) {
            setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        }
    }

    companion object {
        private const val PREF_USERNAME = "username"
        private const val PREF_PASSWORD = "password"
        private const val SESSION_COOKIE = "ci_session"

        private val genreValues = arrayOf(
            "Action",
            "Adventure",
            "Comedy",
            "Demons",
            "Drama",
            "Ecchi",
            "Fantasy",
            "Harem",
            "Historical",
            "Isekai",
            "Magic",
            "Martial Art",
            "Military",
            "Reincarnation",
            "Romance",
            "School",
            "Seinen",
            "Shoujo",
            "Shounen",
            "Slice of Life",
            "Supernatural",
            "Webtoons",
            "Yaoi",
        )
    }
}

@kotlinx.serialization.Serializable
data class LoadMoreResponse(
    val html: String = "",
    val lastId: String? = null,
    val lastScore: String? = null,
)
