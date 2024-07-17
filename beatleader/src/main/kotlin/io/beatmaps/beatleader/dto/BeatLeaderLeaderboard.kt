package io.beatmaps.beatleader.dto

import io.beatmaps.common.BLGameMode
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.searchEnum
import io.beatmaps.common.api.searchEnumOrNull
import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderLeaderboard(
    val song: BeatLeaderSong,
    val difficulty: BeatLeaderDifficulty
) {
    val characteristic = searchEnumOrNull<BLGameMode>(difficulty.modeName)?.characteristic // null means it's an artificial characteristic created by a mod
    val diff = searchEnum<EDifficulty>(difficulty.difficultyName)
}