package io.beatmaps.beatleader.dto

import kotlinx.serialization.Serializable

@Serializable
data class BeatLeaderPageMetadata(val total: Int, val page: Int, val itemsPerPage: Int)
