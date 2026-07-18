package eu.kanade.tachiyomi.extension.id.cgbum

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
class Cgbum : ParsedHttpSource() {

    override val name = "CGBUM"

    override val baseUrl = "https://cgbum.com"

    override val lang = "id"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    // ====== POPULAR MANGA (Menggunakan Halaman Filter Kosong Sebagai Default Popular) ======
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-komik?page=$page", headers)
    }

    override fun popularMangaSelector() = ".comic-grid .comic-card"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleEl = element.select(".comic-card-title a")
        title = titleEl.text().trim()
        url = titleEl.attr("href").substringAfter(baseUrl)
        thumbnail_url = element.select(".comic-card-cover img").attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "ul.pagination li.page-item:not(.disabled) a[rel=next]"

    // ====== LATEST UPDATES ======
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/last-update?page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ====== SEARCH & FILTER MANGA ======
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            // Menggunakan endpoint pencarian JSON jika user mengetik kata kunci
            val url = "$baseUrl/search-suggest.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            GET(url, headers)
        } else {
            // Menggunakan endpoint filter jika pencarian teks kosong
            val url = "$baseUrl/daftar-komik".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
            
            filters.filterIsInstance<UriFilter>().forEach {
                it.addToUri(url)
            }
            GET(url.build(), headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()
        
        // Cek apakah request berasal dari search-suggest.php (JSON)
        if (requestUrl.contains("search-suggest.php")) {
            val searchResults = response.parseAs<List<CgbumSearchDto>>()
            val mangaList = searchResults.map { dto ->
                SManga.create().apply {
                    title = dto.title.orEmpty()
                    url = dto.url?.substringAfter(baseUrl) ?: "/komik/${dto.slug}"
                    thumbnail_url = dto.cover
                }
            }
            return MangasPage(mangaList, false)
        }
        
        // Jika bukan dari API search, parse dengan selector HTML biasa (fitur filter)
        return super.searchMangaParse(response)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ====== MANGA DETAILS ======
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val container = document.select(".comic-detail")
        title = container.select(".comic-info h1").text().trim()
        thumbnail_url = container.select(".comic-cover img").attr("abs:src")
        status = container.select(".badge-status").text().trim().toStatus()
        
        val type = container.select(".badge-type-text").text().trim()
        val genres = container.select(".comic-genres .genre-pill").map { it.text().trim() }
        genre = (listOf(type) + genres).filter { it.isNotBlank() }.joinToString()

        // Ambil metadata tabel author & tahun
        var authorName: String? = null
        container.select(".comic-meta-simple .meta-row").forEach { row ->
            val label = row.select(".meta-label").text().lowercase().trim()
            val value = row.select(".meta-value").text().trim()
            if (label.contains("author")) authorName = value
        }
        author = authorName

        description = container.select(".synopsis-content").text().trim()
    }

    private fun String.toStatus(): Int = when (this.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "tamat" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ====== CHAPTER LIST ======
    override fun chapterListSelector() = ".chapter-grid .ch-grid-item"

    override fun chapterListFromElement(element: Element): SChapter = SChapter.create().apply {
        url = element.attr("href").substringAfter(baseUrl)
        name = element.attr("title").ifEmpty { "Chapter ${element.attr("data-chapter")}" }
        // Format tanggal tidak disediakan di DOM element .ch-grid-item secara eksplisit
        date_upload = 0L 
    }

    // ====== PAGE LIST ======
    override fun pageListParse(document: Document): List<Page> {
        return document.select(".reader-images .page-container").mapIndexed { index, element ->
            val imageUrl = element.attr("abs:data-url")
            Page(index = index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ====== FILTERS DEFINITION ======
    override fun getFilterList(): FilterList = FilterList(
        TypeFilter(),
        StatusFilter(),
        GenreFilter(getGenres()),
    )

    private fun getGenres(): Array<Pair<String, String>> = arrayOf(
        Pair("Action", "Action"), Pair("Adaptation", "Adaptation"), Pair("Adult", "Adult"),
        Pair("AdultMature", "AdultMature"), Pair("Adventure", "Adventure"), Pair("Age Gap", "Age Gap"),
        Pair("Aliens", "Aliens"), Pair("Animals", "Animals"), Pair("Anthology", "Anthology"),
        Pair("BDSM", "BDSM"), Pair("Beasts", "Beasts"), Pair("Bloody", "Bloody"),
        Pair("Bodyswap", "Bodyswap"), Pair("Cheating/Infidelity", "Cheating/Infidelity"),
        Pair("Childhood Friends", "Childhood Friends"), Pair("College life", "College life"),
        Pair("Comedy", "Comedy"), Pair("Contest winning", "Contest winning"), Pair("Cooking", "Cooking"),
        Pair("Crime", "Crime"), Pair("Crossdressing", "Crossdressing"), Pair("Delinquents", "Delinquents"),
        Pair("Demons", "Demons"), Pair("Doujinshi", "Doujinshi"), Pair("Drama", "Drama"),
        Pair("Dungeons", "Dungeons"), Pair("Emperor's Daughter", "Emperor's Daughter"),
        Pair("Fantasy", "Fantasy"), Pair("Fetish", "Fetish"), Pair("Full Color", "Full Color"),
        Pair("Futanari", "Futanari"), Pair("Game", "Game"), Pair("Gender Bender", "Gender Bender"),
        Pair("Genderswap", "Genderswap"), Pair("Ghosts", "Ghosts"), Pair("Girls", "Girls"),
        Pair("Gore", "Gore"), Pair("Harem", "Harem"), Pair("Hentai", "Hentai"),
        Pair("Historical", "Historical"), Pair("Incest", "Incest"), Pair("Isekai", "Isekai"),
        Pair("Josei", "Josei"), Pair("Kids", "Kids"), Pair("Lolicon", "Lolicon"),
        Pair("Magic", "Magic"), Pair("Magical Girls", "Magical Girls"), Pair("Manhua", "Manhua"),
        Pair("Martial Arts", "Martial Arts"), Pair("Mature", "Mature"), Pair("Medical", "Medical"),
        Pair("Military", "Military"), Pair("Monster Girls", "Monster Girls"), Pair("Monsters", "Monsters"),
        Pair("Music", "Music"), Pair("Mystery", "Mystery"), Pair("NTR", "NTR"),
        Pair("Non-human", "Non-human"), Pair("Office Workers", "Office Workers"), Pair("Omegaverse", "Omegaverse"),
        Pair("Oneshot", "Oneshot"), Pair("Philosophical", "Philosophical"), Pair("Police", "Police"),
        Pair("Psychological", "Psychological"), Pair("Regression", "Regression"), Pair("Reincarnation", "Reincarnation"),
        Pair("Revenge", "Revenge"), Pair("Reverse Harem", "Reverse Harem"), Pair("Reverse Isekai", "Reverse Isekai"),
        Pair("Romance", "Romance"), Pair("Royal family", "Royal family"), Pair("Royalty", "Royalty"),
        Pair("School Life", "School Life"), Pair("Sci-Fi", "Sci-Fi"), Pair("Seinen", "Seinen"),
        Pair("Sejarah", "Sejarah"), Pair("Shoujo ai", "Shoujo ai"), Pair("Shoujo(G)", "Shoujo(G)"),
        Pair("Shounen ai", "Shounen ai"), Pair("Shounen(B)", "Shounen(B)"), Pair("Showbiz", "Showbiz"),
        Pair("Slice of Life", "Slice of Life"), Pair("Smut", "Smut"), Pair("Space", "Space"),
        Pair("Sports", "Sports"), Pair("Super Power", "Super Power"), Pair("Superhero", "Superhero"),
        Pair("Supernatural", "Supernatural"), Pair("Survival", "Survival"), Pair("System", "System"),
        Pair("Thriller", "Thriller"), Pair("Time Travel", "Time Travel"), Pair("Tower Climbing", "Tower Climbing"),
        Pair("Traditional Games", "Traditional Games"), Pair("Tragedy", "Tragedy"), Pair("Transmigration", "Transmigration"),
        Pair("Vampires", "Vampires"), Pair("Video Games", "Video Games"), Pair("Villainess", "Villainess"),
        Pair("Violence", "Violence"), Pair("Virtual Reality", "Virtual Reality"), Pair("Western", "Western"),
        Pair("Wuxia", "Wuxia"), Pair("Yakuzas", "Yakuzas"), Pair("Yaoi(BL)", "Yaoi(BL)"),
        Pair("Yuri(GL)", "Yuri(GL)"), Pair("Zombies", "Zombies"), Pair("action", "action"),
        Pair("adventure", "adventure"), Pair("boys", "boys"), Pair("drama", "drama"),
        Pair("ecchi", "ecchi"), Pair("fighting", "fighting"), Pair("girl", "girl"),
        Pair("gore", "gore"), Pair("horror", "horror"), Pair("manga", "manga"),
        Pair("manhwa", "manhwa"),
    )
}
