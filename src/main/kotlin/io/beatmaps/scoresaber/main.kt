package io.beatmaps.scoresaber

import com.fasterxml.jackson.module.kotlin.readValue
import io.beatmaps.common.SSGameMode
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.searchEnum
import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.Difficulty
import io.beatmaps.common.dbo.Versions
import io.beatmaps.common.jackson
import io.beatmaps.common.randomClient
import io.ktor.client.features.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
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
            logger.info("Scoresaber quals")
            scrapeQualified(qualifiedHashes)

            delay(Duration.ofHours(1))
        }
    }

    GlobalScope.launch(es.asCoroutineDispatcher()) {
        val rankedHashes = transaction {
            Difficulty
                .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                .slice(Versions.hash)
                .select {
                    Difficulty.stars.isNotNull()
                }.toList().map { it[Versions.hash] }.toHashSet()
        }

        delay(Duration.ofMinutes(5))

        while (true) {
            var page = 1
            while (scrapeRanked(page++, rankedHashes)) {
                logger.info("Scoresaber ${page - 1}")
            }

            delay(Duration.ofHours(1))
        }
    }
}

data class ScoreSaberUniqueList(val songs: List<ScoreSaberSongUnique>)
data class ScoreSaberSongUnique(val uid: Int, val id: String, val name: String, val songSubName: String, val songAuthorName: String, val levelAuthorName: String, val bpm: Int, val scores_day: Int, val ranked: Boolean, val stars: Float, val image: String)

data class ScoreSaberList(val songs: List<ScoreSaberSong>)
data class ScoreSaberSong(val uid: Int, val id: String, val name: String, val songSubName: String, val songAuthorName: String, val levelAuthorName: String, val bpm: Int, val diff: String, val scores: String, val scores_day: Int, val ranked: Boolean, val stars: Float, val image: String) {
    val characteristic = searchEnum<SSGameMode>(diff.split('_')[2]).characteristic
    val difficulty = searchEnum<EDifficulty>(diff.split('_')[1])
}

suspend fun scrapeQualified(qualifiedHashes: HashSet<String>) {
    val json = randomClient.get<String>("https://scoresaber.com/api.php?function=get-leaderboards&cat=5&qualified=1&unique=1&page=1&limit=200") {
        timeout {
            socketTimeoutMillis = 30000
            requestTimeoutMillis = 60000
        }
    }
    val qualifiedMaps = jackson.readValue<ScoreSaberUniqueList>(json).songs.map { it.id.lowercase() }.toHashSet()
    val toRemove = qualifiedHashes.minus(qualifiedMaps)
    val toAdd = qualifiedMaps.minus(qualifiedHashes)

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
    }

    qualifiedHashes.clear()
    qualifiedHashes.addAll(qualifiedMaps)
}

suspend fun scrapeRanked(page: Int, rankedHashes: HashSet<String>, limit: Int = 100): Boolean {
    val json = randomClient.get<String>("https://scoresaber.com/api.php?function=get-leaderboards&cat=1&ranked=1&page=$page&limit=$limit") {
        timeout {
            socketTimeoutMillis = 30000
            requestTimeoutMillis = 60000
        }
    }
    val obj = jackson.readValue<ScoreSaberList>(json).songs.filter { it.ranked && it.stars > 0 && !rankedHashes.contains(it.id.lowercase()) }

    transaction {
        obj.forEach { diff ->
            rankedHashes.add(diff.id.lowercase())
            Difficulty
                .join(Versions, JoinType.INNER, Versions.id, Difficulty.versionId)
                .update({ Versions.hash eq diff.id.lowercase() and (Difficulty.characteristic eq diff.characteristic) and (Difficulty.difficulty eq diff.difficulty) }) {
                    it[Difficulty.stars] = diff.stars.toBigDecimal()
                }
            Beatmap
                .join(Versions, JoinType.INNER, Versions.mapId, Beatmap.id)
                .update({ Versions.hash eq diff.id.lowercase() }) {
                    it[Beatmap.ranked] = true
                    it[Beatmap.rankedAt] = coalesce(Beatmap.rankedAt, NowExpression(Beatmap.rankedAt.columnType))
                }
        }
    }

    delay(Duration.ofMillis(100L))

    return obj.isNotEmpty()
}