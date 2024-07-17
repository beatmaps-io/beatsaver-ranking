package io.beatmaps.scoresaber.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScoreSaberList(val metadata: SSPagedMetadata? = null, val leaderboards: List<ScoreSaberSong> = listOf())
