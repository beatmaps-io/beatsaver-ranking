package io.beatmaps.beatleader.dto

import io.beatmaps.beatleader.InstantUnixSecondsSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderSong(
    val hash: String,
    @Serializable(with = InstantUnixSecondsSerializer::class)
    val uploadTime: Instant
)
