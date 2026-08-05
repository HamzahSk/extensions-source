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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
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

    private var lastId: String? = null
    private var lastScore: String? = null

    // Membungkus CookieJar bawaan Tachiyomi agar cookie lain tetap terambil,
    // sambil mencegah double cookie khusus untuk ci_session.
    private val customCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val rawCookie = preferences.getString(PREF_COOKIE, "")?.trim()
            val hasCustomSession = !rawCookie.isNullOrEmpty()

            val cookiesToSave = if (hasCustomSession) {
                // Jangan simpan ci_session dari web jika user pakai custom cookie
                cookies.filter { it.name != SESSION_COOKIE }
            } else {
                cookies
            }
            network.client.cookieJar.saveFromResponse(url, cookiesToSave)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val originalCookies = network.client.cookieJar.loadForRequest(url)
            val rawCookie = preferences.getString(PREF_COOKIE, "")?.trim()
            
            val customSession = if (rawCookie?.startsWith("$SESSION_COOKIE=") == true) {
                rawCookie.substringAfter("$SESSION_COOKIE=").substringBefore(";")
            } else {
                rawCookie?.substringBefore(";")
            }?.trim()

            if (customSession.isNullOrEmpty()) {
                return originalCookies
            }

            // Gabungkan cookie lain dari web dengan custom ci_session milik user
            val modifiedCookies = originalCookies.filter { it.name != SESSION_COOKIE }.toMutableList()
            modifiedCookies.add(
                Cookie.Builder()
                    .domain(baseUrlHost)
                    .path("/")
                    .name(SESSION_COOKIE)
                    .value(customSession)
                    .build()
            )
            return modifiedCookies
        }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .cookieJar(customCookieJar) // Gunakan Custom CookieJar
        .addInterceptor(::loginInterceptor)
        .addInterceptor(::thumbnailInterceptor)
        .rateLimit(2) { it.host == baseUrlHost }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        
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

        return chain.proceed(request)
    }

    private fun isLoggedIn(): Boolean {
        return client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == SESSION_COOKIE }
    }

    private fun login() {
        val username = usernamePref.orEmpty()
        val password = passwordPref.orEmpty()
        
        if (username.isBlank() || password.isBlank()) {
            throw IOException("Set your MirrorInKomik username and password in the source settings to read chapters.")
        }

        val getLoginClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val cleanRequest = chain.request().newBuilder()
                    .removeHeader("Cookie")
                    .build()
                chain.proceed(cleanRequest)
            }
            .build()

        val loginPageRequest = GET("$baseUrl/login", headers)
        val document = getLoginClient.newCall(loginPageRequest).execute().use { it.asJsoup() }
        
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
    
        // JANGAN LUPA GANTI URL INI DENGAN DOMAIN VERCEL KAMU!
    private val vercelApiUrl = "https://data-komik.vercel.app/api/search"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Karena Vercel me-return semua data sekaligus tanpa halaman (pagination),
        // kita cegah aplikasi untuk me-request halaman ke-2 dan seterusnya.
        if (page > 1) {
            throw IOException("Semua hasil sudah ditampilkan di halaman pertama.")
        }

        val url = vercelApiUrl.toHttpUrl().newBuilder()

        // 1. Masukkan Query (Kata Kunci)
        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        // 2. Masukkan Filter Genre (Logika DAN / AND)
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        if (genreFilter != null) {
            // Ambil nama genre yang statusnya dicentang (true)
            val selectedGenres = genreFilter.state
                .filter { it.state }
                .map { it.id }
                .joinToString(",")
            
            if (selectedGenres.isNotEmpty()) {
                url.addQueryParameter("filter", selectedGenres)
            }
        }

        // Hit API Vercel (Kita pakai header bawaan OkHttp biasa)
        return GET(url.build().toString(), headersBuilder().build())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()

        // Mengecek apakah respons berasal dari API Vercel buatan kita
        if (response.request.url.host.contains("vercel.app")) {
            val json = body.parseAs<VercelSearchResponse>()
            
            val mangas = json.data.map { item ->
                SManga.create().apply {
                    title = item.title
                    // setUrlWithoutDomain otomatis membuang "https://mirrorinkomik.my.id" 
                    // dan menyisakan path-nya saja (misal: /manhwa/solo-leveling)
                    setUrlWithoutDomain(item.url)
                    thumbnail_url = item.thumbnailUrl
                }
            }
            
            // hasNextPage = false karena semua data Vercel dikirim di 1 halaman penuh
            return MangasPage(mangas, false)
        }

        // Fallback: Jika suatu saat gagal dan request kembali ke web asli
        val document = Jsoup.parse(body)
        val mangas = searchResultsFrom(document)
        lastId = document.selectFirst("#load-more")?.attr("data-last-id")?.takeIf { it != "0" }
        lastScore = document.selectFirst("#load-more")?.attr("data-last-score")
        return MangasPage(mangas, lastId != null)
    }

    private fun searchResultsFrom(document: Document): List<SManga> {
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
        return System.currentTimeMillis() - amount * unitSeconds * 1000
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        var document = response.asJsoup()
        var token = document.selectFirst("#thisch")?.attr("data-token")

        if (token == null) {
            val hasCustomCookie = preferences.getString(PREF_COOKIE, "")?.isNotBlank() == true
            if (hasCustomCookie) {
                throw IOException("Custom ci_session tidak valid atau sudah expired. Silakan perbarui di pengaturan ekstensi, atau kosongkan isian tersebut untuk login pakai username/password.")
            }
            
            login() 

            val retryRequest = GET(response.request.url.toString(), headers)
            val retryResponse = client.newCall(retryRequest).execute()

            document = retryResponse.use { it.asJsoup() }
            token = document.selectFirst("#thisch")?.attr("data-token")
                ?: throw IOException("Could not find the reader token even after re-login. Check your credentials.")
        }

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

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun imageRequest(page: Page): Request {
        var imageUrl = page.imageUrl!!
        val proxyUrl = preferences.getString(PREF_PROXY_URL, "")?.trim()
        if (!proxyUrl.isNullOrEmpty()) {
            imageUrl = if (proxyUrl.contains("%s")) {
                proxyUrl.format(imageUrl)
            } else {
                "$proxyUrl$imageUrl"
            }
        }
        return GET(imageUrl, headers)
    }

    private class TypeFilter : Filter.Select<String>("Type", arrayOf("Manga", "Manhwa", "Manhua"), 0)

    private class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)
    
    private class GenreFilter(genres: List<GenreCheckBox>) : Filter.Group<GenreCheckBox>("Genre (Bisa pilih banyak)", genres)

    override fun getFilterList(): FilterList = FilterList(
        TypeFilter(),
        GenreFilter(genreValues.map { GenreCheckBox(it, it) })
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(screen.editTextPreference(PREF_USERNAME, "MirrorInKomik username"))
        screen.addPreference(screen.editTextPreference(PREF_PASSWORD, "MirrorInKomik password", isPassword = true))
        screen.addPreference(screen.editTextPreference(PREF_COOKIE, "Custom ci_session Cookie (Akan diprioritaskan)"))
        screen.addPreference(screen.editTextPreference(PREF_PROXY_URL, "Image Proxy URL"))
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
        private const val PREF_COOKIE = "cookie_session"
        private const val PREF_PROXY_URL = "pref_proxy_url"
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
