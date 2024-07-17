package io.beatmaps.scoresaber.dto

import kotlinx.serialization.Serializable

@Serializable
data class SSPagedMetadata(val total: Int, val page: Int, val itemsPerPage: Int)
