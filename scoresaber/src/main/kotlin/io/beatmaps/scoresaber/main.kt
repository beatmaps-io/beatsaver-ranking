package io.beatmaps.scoresaber

import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.Difficulty
import io.beatmaps.common.dbo.Versions
import io.beatmaps.common.dbo.joinVersions
import io.beatmaps.common.jsonClient
import io.beatmaps.scoresaber.dto.ScoreSaberList
import io.beatmaps.scoresaber.dto.ScoreSaberSong
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.LiteralOp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import pl.jutupe.ktor_rabbitmq.RabbitMQInstance
import pl.jutupe.ktor_rabbitmq.publish
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

val es: ExecutorService = Executors.newFixedThreadPool(8)
val logger = Logger.getLogger("bmio.scraper")

fun startScraper(mq: RabbitMQInstance) {
    GlobalScope.launch(es.asCoroutineDispatcher()) {
        val qualifiedHashes = transaction {
            Versions
                .join(Beatmap, JoinType.INNER, Beatmap.id, Versions.mapId)
                .select(Versions.hash)
                .where {
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
                updateRanked(mq = mq)
            } catch (e: Exception) {
                logger.severe(e.message)
            }

            delay(Duration.ofHours(1))
        }
    }
}

suspend fun scrapeQualified(qualifiedHashes: HashSet<String>) {
    val qualified = scrapeRanked(1, null, "qualified", false, { it.qualifiedDate }) { it.qualified }
    // Expand to include ALL hashes of qualified maps
    val groupedByHash = qualified.groupBy { it.songHash.lowercase() }
    val qualifiedMaps = transaction {
        val otherVersions = Versions.alias("v2")
        Versions
            .join(otherVersions, JoinType.INNER, otherVersions[Versions.mapId], Versions.mapId)
            .select(otherVersions[Versions.hash])
            .where {
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
                it[Beatmap.qualifiedAt] = coalesce(Beatmap.qualifiedAt, NowExpression(Beatmap.qualifiedAt))
            }
        toAdd.forEach { hash ->
            groupedByHash[hash]?.forEach { diff ->
                Difficulty
                    .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                    .update({ Versions.hash eq diff.songHash.lowercase() and (Difficulty.characteristic eq diff.characteristic) and (Difficulty.difficulty eq diff.diff) }) {
                        it[Difficulty.qualifiedAt] = coalesce(
                            LiteralOp(Difficulty.qualifiedAt.columnType, diff.qualifiedDate?.toJavaInstant()),
                            Difficulty.qualifiedAt,
                            NowExpression(Difficulty.qualifiedAt)
                        )
                    }
            }
        }
    }

    qualifiedHashes.clear()
    qualifiedHashes.addAll(qualifiedMaps)
}

suspend fun scrapeRanked(page: Int, mostRecentRanked: java.time.Instant?, filter: String, unique: Boolean, dateSelector: (ScoreSaberSong) -> Instant?, boolSelector: (ScoreSaberSong) -> Boolean): List<ScoreSaberSong> {
    logger.info("Loading $filter page $page")
    val json = jsonClient.get("https://scoresaber.com/api/leaderboards?$filter=true&category=1&unique=$unique&page=$page") {
        timeout {
            socketTimeoutMillis = 30000
            requestTimeoutMillis = 60000
        }
    }.body<ScoreSaberList>()
    val obj = json.leaderboards.filter {
        val d = dateSelector(it)?.toJavaInstant()
        boolSelector(it) && (mostRecentRanked == null || d == null || d > mostRecentRanked)
    }

    return if (obj.isNotEmpty()) {
        delay(Duration.ofMillis(20L))
        obj.plus(scrapeRanked(page + 1, mostRecentRanked, filter, unique, dateSelector, boolSelector))
    } else {
        emptyList()
    }
}

// suspend fun updateQual() = updateRanked(Difficulty.qualifiedAt, Beatmap.qualifiedAt, Beatmap.qualified, "qualified") { it.qualifiedDate }
suspend fun updateRanked(
    dColumn: Column<java.time.Instant?> = Difficulty.rankedAt,
    bColumn: Column<java.time.Instant?> = Beatmap.rankedAt,
    bBoolColumn: Column<Boolean> = Beatmap.ranked,
    filter: String = "ranked",
    dateSelector: (ScoreSaberSong) -> Instant? = { it.rankedDate },
    boolSelector: (ScoreSaberSong) -> Boolean = { it.ranked },
    mq: RabbitMQInstance
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
                        LiteralOp(Difficulty.rankedAt.columnType, diff.rankedDate?.toJavaInstant()),
                        Difficulty.rankedAt,
                        NowExpression(Difficulty.rankedAt)
                    )
                    it[Difficulty.qualifiedAt] = coalesce(
                        LiteralOp(Difficulty.qualifiedAt.columnType, diff.qualifiedDate?.toJavaInstant()),
                        Difficulty.qualifiedAt,
                        NowExpression(Difficulty.qualifiedAt)
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
                        LiteralOp(Beatmap.rankedAt.columnType, entry.value.mapNotNull { e -> dateSelector(e) }.minOrNull()?.toJavaInstant()),
                        Beatmap.rankedAt,
                        NowExpression(Beatmap.rankedAt)
                    )
                }
        }

        Beatmap
            .joinVersions()
            .select(Beatmap.id)
            .where { Versions.hash inList groupedByHash.keys }
            .map { it[Beatmap.id].value }
    }.forEach {
        mq.publish("beatmaps", "maps.$it.updated.ranked", null, it)
    }
}
