package eu.kanade.tachiyomi.extension.id.cgbum

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}

open class UriPartFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
    private val default: String = "",
) : Filter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
    vals.indexOfFirst { it.second == default }.takeIf { it != -1 } ?: 0,
), UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val selected = vals[state].second
        if (selected.isNotEmpty()) {
            builder.addQueryParameter(param, selected)
        }
    }
}

open class UriMultiSelectOption(name: String, val value: String) : Filter.CheckBox(name)

open class UriMultiSelectFilter(
    name: String,
    private val param: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Group<UriMultiSelectOption>(
    name,
    options.map { UriMultiSelectOption(it.first, it.second) },
), UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        state.filter { it.state }.forEach { 
            // Format array parameter agar menghasilkan &genres[]=...
            builder.addQueryParameter("$param[]", it.value)
        }
    }
}

class TypeFilter : UriPartFilter(
    "Type",
    "type",
    arrayOf(
        Pair("All", ""),
        Pair("Manga", "manga"),
        Pair("Manhwa", "manhwa"),
        Pair("Manhua", "manhua"),
        Pair("Pornhwa", "pornhwa"),
    ),
)

class StatusFilter : UriPartFilter(
    "Status",
    "status",
    arrayOf(
        Pair("All", ""),
        Pair("Ongoing", "ongoing"),
        Pair("Tamat", "tamat"),
    ),
)

class GenreFilter(genres: Array<Pair<String, String>>) : UriMultiSelectFilter(
    "Genres",
    "genres",
    genres,
)
