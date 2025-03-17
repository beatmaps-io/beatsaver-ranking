package io.beatmaps.beatleader.dto

import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.beatsaber.leaderboard.BLGameMode
import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderLeaderboard(
    val song: BeatLeaderSong,
    val difficulty: BeatLeaderDifficulty
) {
    val characteristic = BLGameMode.valueOf(difficulty.modeName).characteristic // null means it's an artificial characteristic created by a mod
    val diff = EDifficulty.valueOf(difficulty.difficultyName)
}
