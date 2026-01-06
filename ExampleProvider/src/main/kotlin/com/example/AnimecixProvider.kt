package com.example // DİKKAT: Burası klasör yapınla aynı olmalı (örn: com.enes.animecix)

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
@CloudstreamPlugin
class AnimecixProvider : MainAPI() {
    override var mainUrl = "https://animecix.tv"
    private val apiUrl = "https://animecix.tv/secure"
    override var name = "Animecix"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)

    // ==========================================
    // 1. DATA CLASSES
    // ==========================================

    data class ResponseContainer(
        @JsonProperty("title") val title: TitleDetail?
    )

    data class TitleDetail(
        @JsonProperty("name") val name: String,
        @JsonProperty("description") val description: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("backdrop") val backdrop: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("seasons") val seasons: List<SeasonInfo>?,
        @JsonProperty("season") val currentSeason: SeasonDetail?,
        @JsonProperty("videos") val videos: List<VideoInfo>?
    )

    data class SeasonInfo(
        @JsonProperty("number") val number: Int,
        @JsonProperty("name") val name: String
    )

    data class SeasonDetail(
        @JsonProperty("episodePagination") val episodePagination: EpisodePagination?
    )

    data class EpisodePagination(
        @JsonProperty("data") val data: List<EpisodeInfo>?
    )

    data class EpisodeInfo(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("season_number") val seasonNumber: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("poster") val poster: String?
    )

    data class VideoInfo(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("episode_num") val episodeNum: Int?
    )

    data class SearchResultContainer(
        @JsonProperty("results") val results: List<SearchResultItem>?
    )

    data class SearchResultItem(
        @JsonProperty("name") val name: String,
        @JsonProperty("id") val id: Int,
        @JsonProperty("poster") val poster: String?
    )

    // ==========================================
    // 2. SEARCH
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/search/$query"
        val res = app.get(url).parsedSafe<SearchResultContainer>()

        return res?.results?.map {
            newAnimeSearchResponse(it.name, it.id.toString(), TvType.Anime) {
                this.posterUrl = it.poster
            }
        } ?: emptyList()
    }

    // ==========================================
    // 3. LOAD (DÜZELTİLMİŞ)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val mainPageUrl = "$apiUrl/titles/$url?titleId=$url"
        val mainResponse = app.get(mainPageUrl).parsedSafe<ResponseContainer>()
            ?: throw ErrorLoadingException("Veri çekilemedi")

        val titleData = mainResponse.title ?: throw ErrorLoadingException("Başlık bilgisi yok")

        val episodes = ArrayList<Episode>()

        val seasonsToFetch = titleData.seasons?.map { it.number } ?: listOf(1)

        seasonsToFetch.forEach { seasonNum ->
            val seasonUrl = "$apiUrl/titles/$url?seasonNumber=$seasonNum"
            val seasonRes = app.get(seasonUrl).parsedSafe<ResponseContainer>()
            val currentSeasonData = seasonRes?.title

            val seasonVideos = currentSeasonData?.videos ?: emptyList()
            val episodeList = currentSeasonData?.currentSeason?.episodePagination?.data

            episodeList?.forEach { ep ->
                val matchingVideos = seasonVideos.filter { it.episodeNum == ep.episodeNumber }
                val videoDataJson = mapper.writeValueAsString(matchingVideos)

                val episodeObj = newEpisode(data = videoDataJson) {
                    this.name = ep.name ?: "Bölüm ${ep.episodeNumber}"
                    this.season = seasonNum
                    this.episode = ep.episodeNumber
                    this.posterUrl = ep.poster
                    this.description = ep.description
                }
                episodes.add(episodeObj)
            }
        }

        return newAnimeLoadResponse(titleData.name, url, TvType.Anime) {
            this.posterUrl = titleData.poster
            this.backgroundPosterUrl = titleData.backdrop
            this.plot = titleData.description
            this.year = titleData.year

            // HATA VEREN rating SATIRINI TAMAMEN SİLDİK.
            // Artık kırmızı hata vermeyecek.

            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ==========================================
    // 4. LOAD LINKS
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val videos = mapper.readValue<List<VideoInfo>>(data)

        videos.forEach { video ->
            loadExtractor(video.url, subtitleCallback, callback)
        }

        return true
    }
}