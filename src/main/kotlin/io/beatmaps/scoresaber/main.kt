package io.beatmaps.scoresaber

import io.beatmaps.common.SSGameMode
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.searchEnum
import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.Difficulty
import io.beatmaps.common.dbo.Versions
import io.beatmaps.common.randomClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.LiteralOp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

val es: ExecutorService = Executors.newFixedThreadPool(8)
val logger = Logger.getLogger("bmio.scraper")

fun startScraper() {
    GlobalScope.launch(es.asCoroutineDispatcher()) {
        val qualifiedHashes = transaction {
            Versions
                .join(Beatmap, JoinType.INNER, Beatmap.id, Versions.mapId)
                .slice(Versions.hash)
                .select {
                    Beatmap.qualified eq true
                }.map { it[Versions.hash] }.toHashSet()
        }

        delay(Duration.ofMinutes(4))

        while (true) {
            try {
                logger.info("Scoresaber quals")
                scrapeQualified(qualifiedHashes)
            } catch (e: Exception) {
                logger.severe(e.message)
            }

            delay(Duration.ofHours(1))
        }
    }

    GlobalScope.launch(es.asCoroutineDispatcher()) {
        delay(Duration.ofMinutes(5))

        while (true) {
            try {
                updateRanked()
            } catch (e: Exception) {
                logger.severe(e.message)
            }

            delay(Duration.ofHours(1))
        }
    }
}

data class SSPagedMetadata(val total: Int, val page: Int, val itemsPerPage: Int)
data class ScoreSaberList(val metadata: SSPagedMetadata?, val leaderboards: List<ScoreSaberSong>)
data class ScoreSaberSong(
    val id: Int,
    val songHash: String,
    val songName: String,
    val songSubName: String,
    val songAuthorName: String,
    val levelAuthorName: String,
    val difficulty: SSLeaderboardInfoDiff,
    val maxScore: Long,
    val createdDate: Instant?,
    val rankedDate: Instant?,
    val qualifiedDate: Instant?,
    val lovedDate: Instant?,
    val ranked: Boolean,
    val qualified: Boolean,
    val loved: Boolean,
    val maxPP: Int,
    val stars: Float,
    val plays: Long,
    val dailyPlays: Int,
    val positiveModifiers: Boolean,
    val playerScore: Int?,
    val coverImage: String,
    val difficulties: List<SSLeaderboardInfoDiff>?
) {
    val characteristic = searchEnum<SSGameMode>(difficulty.gameMode).characteristic
    val diff = EDifficulty.fromInt(difficulty.difficulty) ?: throw IllegalArgumentException("No enum constant for diff ${difficulty.difficulty}")
}
data class SSLeaderboardInfoDiff(val leaderboardId: Int, val difficulty: Int, val gameMode: String, val difficultyRaw: String)

suspend fun scrapeQualified(qualifiedHashes: HashSet<String>) {
    val qualified = scrapeRanked(1, null, "qualified", false, { it.qualifiedDate }) { it.qualified }
    // Expand to include ALL hashes of qualified maps
    val groupedByHash = qualified.groupBy { it.songHash.lowercase() }
    val qualifiedMaps = transaction {
        val otherVersions = Versions.alias("v2")
        Versions
            .join(otherVersions, JoinType.INNER, otherVersions[Versions.mapId], Versions.mapId)
            .slice(otherVersions[Versions.hash])
            .select {
                Versions.hash inList groupedByHash.keys
            }
            .map { it[otherVersions[Versions.hash]] }
    }.toHashSet()

    val toRemove = qualifiedHashes.minus(qualifiedMaps)
    val toAdd = qualifiedMaps.minus(qualifiedHashes)

    logger.info("${toRemove.size} qualified maps to remove, ${toAdd.size} qualified maps to add")

    transaction {
        Beatmap
            .join(Versions, JoinType.INNER, Versions.mapId, Beatmap.id)
            .update({ Versions.hash inList toRemove }) {
                it[Beatmap.qualified] = false
            }

        Beatmap
            .join(Versions, JoinType.INNER, Versions.mapId, Beatmap.id)
            .update({ Versions.hash inList toAdd }) {
                it[Beatmap.qualified] = true
                it[Beatmap.qualifiedAt] = coalesce(Beatmap.qualifiedAt, NowExpression(Beatmap.qualifiedAt.columnType))
            }
        toAdd.forEach { hash ->
            groupedByHash[hash]?.forEach { diff ->
                Difficulty
                    .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                    .update({ Versions.hash eq diff.songHash.lowercase() and (Difficulty.characteristic eq diff.characteristic) and (Difficulty.difficulty eq diff.diff) }) {
                        it[Difficulty.qualifiedAt] = coalesce(
                            LiteralOp(Difficulty.qualifiedAt.columnType, diff.qualifiedDate),
                            Difficulty.qualifiedAt,
                            NowExpression(Difficulty.qualifiedAt.columnType)
                        )
                    }
            }
        }
    }

    qualifiedHashes.clear()
    qualifiedHashes.addAll(qualifiedMaps)
}

suspend fun scrapeRanked(page: Int, mostRecentRanked: Instant?, filter: String, unique: Boolean, dateSelector: (ScoreSaberSong) -> Instant?, boolSelector: (ScoreSaberSong) -> Boolean): List<ScoreSaberSong> {
    logger.info("Loading $filter page $page")
    val json = randomClient.get("https://scoresaber.com/api/leaderboards?$filter=true&category=1&unique=$unique&page=$page") {
        timeout {
            socketTimeoutMillis = 30000
            requestTimeoutMillis = 60000
        }
    }.body<ScoreSaberList>()
    val obj = json.leaderboards.filter { val d = dateSelector(it); boolSelector(it) && (mostRecentRanked == null || d == null || d > mostRecentRanked) }

    return if (obj.isNotEmpty()) {
        delay(Duration.ofMillis(20L))
        obj.plus(scrapeRanked(page + 1, mostRecentRanked, filter, unique, dateSelector, boolSelector))
    } else {
        emptyList()
    }
}

//suspend fun updateQual() = updateRanked(Difficulty.qualifiedAt, Beatmap.qualifiedAt, Beatmap.qualified, "qualified") { it.qualifiedDate }
suspend fun updateRanked(
    dColumn: Column<Instant?> = Difficulty.rankedAt,
    bColumn: Column<Instant?> = Beatmap.rankedAt,
    bBoolColumn: Column<Boolean> = Beatmap.ranked,
    filter: String = "ranked",
    dateSelector: (ScoreSaberSong) -> Instant? = { it.rankedDate },
    boolSelector: (ScoreSaberSong) -> Boolean = { it.ranked }
) {
    val mostRecentRanked = transaction {
        Difficulty
            .selectAll()
            .orderBy(dColumn, SortOrder.DESC_NULLS_LAST)
            .limit(1).singleOrNull()?.let { it[dColumn] }
    }

    val obj = scrapeRanked(1, mostRecentRanked, filter, false, dateSelector, boolSelector)
    val groupedByHash = obj.groupBy { it.songHash }

    logger.info("${obj.size} ranked diffs to update")

    transaction {
        obj.forEachIndexed { idx, diff ->
            if (idx % 100 == 0) logger.info("Updated $idx diffs")
            Difficulty
                .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                .update({ Versions.hash eq diff.songHash.lowercase() and (Difficulty.characteristic eq diff.characteristic) and (Difficulty.difficulty eq diff.diff) }) {
                    it[Difficulty.stars] = if (diff.stars > 0) diff.stars.toBigDecimal() else null
                    it[Difficulty.rankedAt] = coalesce(
                        LiteralOp(Difficulty.rankedAt.columnType, diff.rankedDate),
                        Difficulty.rankedAt,
                        NowExpression(Difficulty.rankedAt.columnType)
                    )
                    it[Difficulty.qualifiedAt] = coalesce(
                        LiteralOp(Difficulty.qualifiedAt.columnType, diff.qualifiedDate),
                        Difficulty.qualifiedAt,
                        NowExpression(Difficulty.qualifiedAt.columnType)
                    )
                }
        }
        groupedByHash.forEach { entry ->
            Beatmap
                .join(Versions, JoinType.INNER, Versions.mapId, Beatmap.id)
                .update({ Versions.hash eq entry.key.lowercase() }) {
                    it[bBoolColumn] = true
                    // Use date from api if possible
                    // Otherwise use existing value
                    // Lastly use current date
                    it[bColumn] = coalesce(
                        LiteralOp(Beatmap.rankedAt.columnType, entry.value.mapNotNull { e -> dateSelector(e) }.minOrNull()),
                        Beatmap.rankedAt,
                        NowExpression(Beatmap.rankedAt.columnType)
                    )
                }
        }
    }
}