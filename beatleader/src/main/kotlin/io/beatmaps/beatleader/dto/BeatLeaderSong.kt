package io.beatmaps.beatleader.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderSong(
    val hash: String,
    val uploadTime: Instant
)
