package eu.kanade.tachiyomi.extension.id.cgbum

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Cgbum : HttpSource(), ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences = getPreferences()

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-komik?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = document.select(".comic-grid .comic-card").map { element ->
            SManga.create().apply {
                val titleEl = element.select(".comic-card-title a")
                title = titleEl.text().trim()
                url = titleEl.attr("href").substringAfter(baseUrl)
                thumbnail_url = element.select(".comic-card-cover img").attr("abs:src")
            }
        }
        val hasNextPage = document.select("ul.pagination li.page-item:not(.disabled) a[rel=next]").first() != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/last-update?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            val url = "$baseUrl/search-suggest.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            GET(url, headers)
        } else {
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

        if (requestUrl.contains("search-suggest.php")) {
            val searchResults = response.parseAs<List<CgbumDto>>()
            val mangaList = searchResults.map { dto ->
                SManga.create().apply {
                    title = dto.title.orEmpty()
                    url = dto.url?.substringAfter(baseUrl) ?: "/komik/${dto.slug}"
                    thumbnail_url = dto.cover
                }
            }
            return MangasPage(mangaList, false)
        }

        return popularMangaParse(response)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        val container = document.select(".comic-detail")

        return SManga.create().apply {
            title = container.select(".comic-info h1").text().trim()
            thumbnail_url = container.select(".comic-cover img").attr("abs:src")
            status = container.select(".badge-status").text().trim().toStatus()

            val type = container.select(".badge-type-text").text().trim()
            val genres = container.select(".comic-genres .genre-pill").map { it.text().trim() }
            genre = (listOf(type) + genres).filter { it.isNotBlank() }.joinToString()

            var authorName: String? = null
            container.select(".comic-meta-simple .meta-row").forEach { row ->
                val label = row.select(".meta-label").text().lowercase().trim()
                val value = row.select(".meta-value").text().trim()
                if (label.contains("author")) authorName = value
            }
            author = authorName

            description = container.select(".synopsis-content").text().trim()
        }
    }

    private fun String.toStatus(): Int = when (this.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "tamat" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return document.select(".chapter-grid .ch-grid-item").map { element ->
            SChapter.create().apply {
                url = element.attr("href").substringAfter(baseUrl)
                name = element.attr("title").ifEmpty { "Chapter ${element.attr("data-chapter")}" }
                date_upload = 0L
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body.string(), baseUrl)
        return document.select(".reader-images .page-container").mapIndexed { index, element ->
            val imageUrl = element.attr("abs:data-url")
            Page(index = index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .add("DNT", "1")
            .add("referer", "$baseUrl/")
            .add("sec-fetch-dest", "empty")
            .add("Sec-GPC", "1")
            .build()

        var imageUrl = page.imageUrl!!
        
        val useWpProxy = preferences.getBoolean(PREF_USE_WP_PROXY, false)

        if (useWpProxy) {
            // Ambil settingan kualitas, default ke 50 jika kosong
            val quality = preferences.getString(PREF_WP_QUALITY, "50") ?: "50"
            // Hapus http:// atau https:// dari URL asli untuk dimasukkan ke URL wp proxy
            val urlWithoutScheme = imageUrl.replaceFirst(Regex("^https?://"), "")
            imageUrl = "https://i0.wp.com/$urlWithoutScheme?q=$quality"
        } else {
            // Logika custom proxy lama (berjalan kalau WP Proxy dimatikan)
            val proxyUrl = preferences.getString(PREF_PROXY_URL, "")?.trim()
            if (!proxyUrl.isNullOrEmpty()) {
                imageUrl = if (proxyUrl.contains("%s")) {
                    proxyUrl.format(imageUrl)
                } else {
                    "$proxyUrl$imageUrl"
                }
            }
        }

        return GET(imageUrl, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val useWpProxyPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_WP_PROXY
            title = "Gunakan WordPress Proxy (i0.wp.com)"
            summary = "Kompres gambar menggunakan proxy WP untuk menghemat kuota (menonaktifkan Custom Proxy di bawah)."
            setDefaultValue(false)
        }
        screen.addPreference(useWpProxyPref)

        val wpQualityPref = ListPreference(screen.context).apply {
            key = PREF_WP_QUALITY
            title = "Kualitas Gambar WP Proxy"
            entries = arrayOf("Rendah (30%)", "Sedang (50%)", "Tinggi (70%)", "Sangat Tinggi (90%)")
            entryValues = arrayOf("30", "50", "70", "90")
            setDefaultValue("50")
            summary = "Pilih persentase kualitas gambar (Aktif jika WP Proxy dinyalakan)."
        }
        screen.addPreference(wpQualityPref)

        val customProxyPref = EditTextPreference(screen.context).apply {
            key = PREF_PROXY_URL
            title = "Image Compression Proxy URL"
            summary = "Kosongkan jika tidak ingin digunakan. Masukkan URL proxy penanganan kompresi gambar kamu...\n(Aktif jika WP Proxy dimatikan)"
            setDefaultValue("")
        }
        screen.addPreference(customProxyPref)

        // Setup state awal (nyala/mati) berdasarkan preferensi yang tersimpan
        val isWpProxyEnabled = preferences.getBoolean(PREF_USE_WP_PROXY, false)
        wpQualityPref.setEnabled(isWpProxyEnabled)
        customProxyPref.setEnabled(!isWpProxyEnabled)

        // Listener untuk mengubah state secara dinamis ketika switch ditekan
        useWpProxyPref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            wpQualityPref.setEnabled(enabled)
            customProxyPref.setEnabled(!enabled)
            true
        }
    }


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

    companion object {
        private const val PREF_PROXY_URL = "pref_proxy_url"
        private const val PREF_USE_WP_PROXY = "pref_use_wp_proxy"
        private const val PREF_WP_QUALITY = "pref_wp_quality"
    }
}