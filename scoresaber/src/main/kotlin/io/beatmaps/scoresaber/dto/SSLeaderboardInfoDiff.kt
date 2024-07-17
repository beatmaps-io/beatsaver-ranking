package io.beatmaps.scoresaber.dto

import kotlinx.serialization.Serializable

@Serializable
data class SSLeaderboardInfoDiff(val leaderboardId: Int, val difficulty: Int, val gameMode: String, val difficultyRaw: String)
