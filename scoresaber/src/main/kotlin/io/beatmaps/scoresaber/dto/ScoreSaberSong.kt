package io.beatmaps.scoresaber.dto

import io.beatmaps.common.SSGameMode
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.searchEnum
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ScoreSaberSong(
    val id: Int,
    val songHash: String,
    val songName: String,
    val songSubName: String,
    val songAuthorName: String,
    val levelAuthorName: String,
    val difficulty: SSLeaderboardInfoDiff,
    val maxScore: Long,
    val createdDate: Instant? = null,
    val rankedDate: Instant? = null,
    val qualifiedDate: Instant? = null,
    val lovedDate: Instant? = null,
    val ranked: Boolean,
    val qualified: Boolean,
    val loved: Boolean,
    val maxPP: Int,
    val stars: Float,
    val plays: Long,
    val dailyPlays: Int,
    val positiveModifiers: Boolean,
    val playerScore: Int? = null,
    val coverImage: String,
    val difficulties: List<SSLeaderboardInfoDiff>? = null
) {
    val characteristic = searchEnum<SSGameMode>(difficulty.gameMode).characteristic
    val diff = EDifficulty.fromInt(difficulty.difficulty) ?: throw IllegalArgumentException("No enum constant for diff ${difficulty.difficulty}")
}
