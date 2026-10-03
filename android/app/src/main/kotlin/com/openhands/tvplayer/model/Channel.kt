package com.openhands.tvplayer.model

/** One channel or radio station, parsed from the bundled channel list. */
data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val source: String?,
    val kind: String,
    val urls: List<Stream> = emptyList()
) {
    data class Stream(val url: String, val label: String?)

    /**
     * Stream to hand to ExoPlayer. The list carries several mirrors per
     * channel; the first non-blank alternate wins, otherwise the primary URL.
     */
    val playUrl: String
        get() = urls.firstOrNull { !it.url.isBlank() }?.url?.takeIf { it.isNotBlank() } ?: url

    val isRadio: Boolean get() = kind == "radio"

    /** Letter shown when the channel has no logo (or it fails to load). */
    val initial: String
        get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}
