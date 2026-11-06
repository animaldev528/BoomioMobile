package com.nuvio.app.features.search

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection

enum class SearchEmptyStateReason {
    NoActiveAddons,
    NoSearchCatalogs,
    NoResults,
    RequestFailed,
}

data class SearchUiState(
    val isLoading: Boolean = false,
    val sections: List<HomeCatalogSection> = emptyList(),
    /** Client-side TMDB people matches, rendered as a strip above the catalog rows. */
    val people: List<PersonSearchResult> = emptyList(),
    val peopleLoading: Boolean = false,
    val emptyStateReason: SearchEmptyStateReason? = null,
    val errorMessage: String? = null,
)

/**
 * One person from TMDB's `/search/person`, mirroring the TV client's People strip.
 * Addon catalogs never surface actors, so the client asks TMDB directly.
 */
data class PersonSearchResult(
    val tmdbId: Int,
    val name: String,
    val profilePhotoUrl: String? = null,
    /** e.g. "Actor · Forrest Gump / Cast Away" */
    val knownForLabel: String? = null,
)

enum class DiscoverEmptyStateReason {
    NoActiveAddons,
    NoDiscoverCatalogs,
    NoResults,
    RequestFailed,
}

data class DiscoverCatalogOption(
    val key: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false,
    val supportsPagination: Boolean = false,
)

data class DiscoverUiState(
    val typeOptions: List<String> = emptyList(),
    val selectedType: String? = null,
    val catalogOptions: List<DiscoverCatalogOption> = emptyList(),
    val selectedCatalogKey: String? = null,
    val selectedGenre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val emptyStateReason: DiscoverEmptyStateReason? = null,
    val errorMessage: String? = null,
) {
    val selectedCatalog: DiscoverCatalogOption?
        get() = catalogOptions.firstOrNull { it.key == selectedCatalogKey }

    val genreOptions: List<String>
        get() = selectedCatalog?.genreOptions.orEmpty()

    val canLoadMore: Boolean
        get() = nextSkip != null
}
