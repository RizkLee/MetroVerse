package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistPanelRenderer(
    val title: String?,
    val titleText: Runs?,
    val shortBylineText: Runs?,
    val contents: List<Content>,
    val isInfinite: Boolean?,
    val numItemsToShow: Int?,
    val playlistId: String?,
    val continuations: List<Continuation>?,
) {
    @Serializable
    data class Content(
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer? = null,
        val playlistPanelVideoWrapperRenderer: PlaylistPanelVideoWrapperRenderer? = null,
        val automixPreviewVideoRenderer: AutomixPreviewVideoRenderer? = null,
    ) {
        fun resolvedVideoRenderer(): PlaylistPanelVideoRenderer? =
            playlistPanelVideoRenderer
                ?: playlistPanelVideoWrapperRenderer?.primaryRenderer?.playlistPanelVideoRenderer
                ?: playlistPanelVideoWrapperRenderer?.counterpart
                    ?.firstOrNull()
                    ?.counterpartRenderer
                    ?.playlistPanelVideoRenderer
    }
}

@Serializable
data class PlaylistPanelVideoWrapperRenderer(
    val primaryRenderer: PrimaryRenderer? = null,
    val counterpart: List<Counterpart>? = null,
) {
    @Serializable
    data class PrimaryRenderer(
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer? = null,
    )

    @Serializable
    data class Counterpart(
        val counterpartRenderer: PrimaryRenderer? = null,
    )
}
