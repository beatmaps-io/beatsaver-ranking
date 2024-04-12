package io.beatmaps.beatleader

import io.beatmaps.common.BLGameMode
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.searchEnum
import io.beatmaps.common.api.searchEnumOrNull
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

// See: https://github.com/BeatLeader/beatleader-website/blob/5c3c35a07264c7587697bea021c4d3130cfac7b5/src/utils/beatleader/format.js#L675
const val qualifiedStatus = 2
const val rankedStatus = 3


fun startScraper() {
    GlobalScope.launch(es.asCoroutineDispatcher()) {
        val qualifiedHashes = transaction {
            Versions
                .join(Beatmap, JoinType.INNER, Beatmap.id, Versions.mapId)
                .slice(Versions.hash)
                .select {
                    Beatmap.blQualified eq true
                }.map { it[Versions.hash] }.toHashSet()
        }

        delay(Duration.ofMinutes(4))

        while (true) {
            try {
                logger.info("BeatLeader quals")
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

data class BeatLeaderPageMetadata(val total: Int, val page: Int, val itemsPerPage: Int)
data class BeatLeaderList(val metadata: BeatLeaderPageMetadata?, val data: List<BeatLeaderLeaderboard>)
data class BeatLeaderLeaderboard(
    val song: BeatLeaderSong,
    val difficulty: BeatLeaderDifficulty
) {
    val characteristic = searchEnumOrNull<BLGameMode>(difficulty.modeName)?.characteristic // null means it's an artificial characteristic created by a mod
    val diff = searchEnum<EDifficulty>(difficulty.difficultyName)
}
data class BeatLeaderSong(
    val hash: String,
    val uploadTime: Instant
)
data class BeatLeaderDifficulty(
    val stars: Float,
    val difficultyName: String,
    val modeName: String,
    val status: Int,

    val nominatedTime: Instant?,
    val qualifiedTime: Instant?,
    val rankedTime: Instant?
)

suspend fun scrapeQualified(qualifiedHashes: HashSet<String>) {
    val qualified = scrapeRanked(null, "qualified", { it.difficulty.qualifiedTime }, { it.difficulty.status == qualifiedStatus })
    // Expand to include ALL hashes of qualified maps
    val groupedByHash = qualified.groupBy { it.song.hash.lowercase() }
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
                it[Beatmap.blQualified] = false
            }

        Beatmap
            .join(Versions, JoinType.INNER, Versions.mapId, Beatmap.id)
            .update({ Versions.hash inList toAdd }) {
                it[Beatmap.blQualified] = true
                it[Beatmap.blQualifiedAt] = coalesce(Beatmap.blQualifiedAt, NowExpression(Beatmap.blQualifiedAt.columnType))
            }
        toAdd.forEach { hash ->
            groupedByHash[hash]?.forEach diff@{ leaderboard ->
                val characteristic = leaderboard.characteristic ?: return@diff

                Difficulty
                    .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                    .update({ Versions.hash eq leaderboard.song.hash.lowercase() and (Difficulty.characteristic eq characteristic) and (Difficulty.difficulty eq leaderboard.diff) }) {
                        it[Difficulty.blQualifiedAt] = coalesce(
                            LiteralOp(Difficulty.blQualifiedAt.columnType, leaderboard.difficulty.qualifiedTime),
                            Difficulty.blQualifiedAt,
                            NowExpression(Difficulty.blQualifiedAt)
                        )
                    }
            }
        }
    }

    qualifiedHashes.clear()
    qualifiedHashes.addAll(qualifiedMaps)
}

suspend fun scrapeRanked(
    mostRecentRanked: Instant?,
    filter: String,
    dateSelector: (BeatLeaderLeaderboard) -> Instant?,
    boolSelector: (BeatLeaderLeaderboard) -> Boolean,
    page: Int = 1,
    pageSize: Int = 20
): List<BeatLeaderLeaderboard> {
    logger.info("Loading $filter page $page")
    val json = randomClient.get("https://api.beatleader.xyz/leaderboards?type=$filter&sortBy=timestamp&order=asc&count=$pageSize&page=$page") {
        timeout {
            socketTimeoutMillis = 30000
            requestTimeoutMillis = 60000
        }
    }.body<BeatLeaderList>()

    val obj = json.data.filter {
        val d = dateSelector(it)
        boolSelector(it) && (mostRecentRanked == null || d == null || d > mostRecentRanked)
    }

    return if (obj.isNotEmpty()) {
        delay(Duration.ofMillis(20L))
        obj.plus(scrapeRanked(mostRecentRanked, filter, dateSelector, boolSelector, page + 1, pageSize))
    } else {
        emptyList()
    }
}

suspend fun updateRanked(
    dColumn: Column<Instant?> = Difficulty.blRankedAt,
    bColumn: Column<Instant?> = Beatmap.blRankedAt,
    bBoolColumn: Column<Boolean> = Beatmap.blRanked,
    filter: String = "ranked",
    dateSelector: (BeatLeaderLeaderboard) -> Instant? = { it.difficulty.rankedTime },
    boolSelector: (BeatLeaderLeaderboard) -> Boolean = { it.difficulty.status == rankedStatus }
) {
    val mostRecentRanked = transaction {
        Difficulty
            .selectAll()
            .orderBy(dColumn, SortOrder.DESC_NULLS_LAST)
            .limit(1).singleOrNull()?.let { it[dColumn] }
    }

    val obj = scrapeRanked(mostRecentRanked, filter, dateSelector, boolSelector)
    val groupedByHash = obj.groupBy { it.song.hash }

    logger.info("${obj.size} ranked diffs to update")

    transaction {
        obj.forEachIndexed diff@{ idx, leaderboard ->
            if (idx % 100 == 0) logger.info("Updated $idx diffs")

            val characteristic = leaderboard.characteristic ?: return@diff

            Difficulty
                .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                .update({ Versions.hash eq leaderboard.song.hash.lowercase() and (Difficulty.characteristic eq characteristic) and (Difficulty.difficulty eq leaderboard.diff) }) {
                    it[Difficulty.blStars] = if (leaderboard.difficulty.stars > 0) leaderboard.difficulty.stars.toBigDecimal() else null
                    it[Difficulty.blRankedAt] = coalesce(
                        LiteralOp(Difficulty.blRankedAt.columnType, leaderboard.difficulty.rankedTime),
                        Difficulty.blRankedAt,
                        NowExpression(Difficulty.blRankedAt)
                    )
                    it[Difficulty.blQualifiedAt] = coalesce(
                        LiteralOp(Difficulty.blQualifiedAt.columnType, leaderboard.difficulty.qualifiedTime),
                        Difficulty.blQualifiedAt,
                        NowExpression(Difficulty.blQualifiedAt)
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
                        LiteralOp(Beatmap.blRankedAt.columnType, entry.value.mapNotNull { e -> dateSelector(e) }.minOrNull()),
                        Beatmap.blRankedAt,
                        NowExpression(Beatmap.blRankedAt)
                    )
                }
        }
    }
}
