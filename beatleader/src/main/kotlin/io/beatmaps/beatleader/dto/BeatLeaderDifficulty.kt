package io.beatmaps.beatleader.dto

import io.beatmaps.beatleader.InstantUnixSecondsSerializer
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderDifficulty(
    val stars: Float,
    val difficultyName: String,
    val modeName: String,
    val status: Int,

    @Serializable(with = InstantUnixSecondsSerializer::class)
    val nominatedTime: Instant? = null,
    @Serializable(with = InstantUnixSecondsSerializer::class)
    val qualifiedTime: Instant? = null,
    @Serializable(with = InstantUnixSecondsSerializer::class)
    val rankedTime: Instant? = null
)
