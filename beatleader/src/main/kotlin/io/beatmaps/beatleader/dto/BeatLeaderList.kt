package io.beatmaps.beatleader.dto

import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderList(
    val metadata: BeatLeaderPageMetadata? = null,
    val data: List<BeatLeaderLeaderboard> = listOf()
)
