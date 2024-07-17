package io.beatmaps.beatleader.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderDifficulty(
    val stars: Float,
    val difficultyName: String,
    val modeName: String,
    val status: Int,

    val nominatedTime: Instant? = null,
    val qualifiedTime: Instant? = null,
    val rankedTime: Instant? = null
)
