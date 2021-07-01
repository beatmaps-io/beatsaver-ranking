package io.beatmaps.beatsaver

import com.fasterxml.jackson.module.kotlin.readValue
import io.beatmaps.common.SSGameMode
import io.beatmaps.common.api.ECharacteristic
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.EMapState
import io.beatmaps.common.api.searchEnum
import io.beatmaps.common.beatsaver.*
import io.beatmaps.common.consumeAck
import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.db.updateReturning
import io.beatmaps.common.db.upsert
import io.beatmaps.common.dbo.*
import io.beatmaps.common.jackson
import io.beatmaps.common.randomClient
import io.beatmaps.common.zip.ZipHelper
import io.beatmaps.common.zip.parseDifficulty
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.coalesce
import pl.jutupe.ktor_rabbitmq.RabbitMQ
import pl.jutupe.ktor_rabbitmq.publish
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Integer.parseInt
import java.lang.Integer.toHexString
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.io.path.inputStream

val es: ExecutorService = Executors.newFixedThreadPool(8)
data class DownloadAttempts(val mapInfo: BeatsaverMap, val time: Instant = Instant.now())

fun startScraper(mq: RabbitMQ) {
    mq.consumeAck("bs.update", BeatsaverMap::class) { _, map ->
        transaction {
            val existingVersion = Versions.update({ Versions.hash eq map.hash }) {
                it[key64] = map.key
            } > 0

            if (existingVersion) {
                val oldInfo = BeatmapDao.wrapRows(
                    Versions
                        .join(Beatmap, JoinType.INNER, Versions.mapId, Beatmap.id)
                        .select {
                            Versions.key64 eq map.key
                        }.limit(1)
                ).firstOrNull()

                Versions
                    .join(Beatmap, JoinType.INNER, Versions.mapId, Beatmap.id)
                    .update({
                        Versions.key64 eq map.key
                    }) {
                        it[Beatmap.name] = map.name
                        it[Beatmap.description] = map.description
                        it[Beatmap.automapper] = map.metadata.automapper != null
                        it[Beatmap.upVotes] = map.stats.upVotes
                        it[Beatmap.downVotes] = map.stats.downVotes
                        it[Beatmap.beatsaverDownloads] = map.stats.downloads
                    }

                // Check if anything important changed
                if (oldInfo == null || map.name != oldInfo.name || map.description != oldInfo.description || (map.metadata.automapper != null) != oldInfo.automapper) {
                    val mapId = Versions.select {
                        Versions.key64 eq map.key
                    }.limit(1).toList().firstOrNull()?.get(Versions.mapId)?.value ?: 0

                    mq.publish("beatmaps", "maps.$mapId.updated", null, mapId)
                }
            } else {
                mq.publish("beatmaps", "beatsaver.download", null, DownloadAttempts(map))
            }
        }
    }

    // Create multiple download consumers
    repeat(4) {
        mq.consumeAck("bs.download", DownloadAttempts::class) { _, atmpt ->
            // Ignore messages older than 5 minutes
            val duration = Duration.between(atmpt.time, Instant.now()).toMinutes()
            if (duration < 10) {
                val map = atmpt.mapInfo

                println("Download ${map.hash}")
                val bytes = randomClient.get<ByteArray>("https://beatsaver.com${map.directDownload}") {
                    userAgent("BeatSaverDownloader/5.4.0.0 BeatSaverSharp/1.6.0.0 BeatSaber /1.14.0-oculus")
                    timeout {
                        socketTimeoutMillis = 30000
                        requestTimeoutMillis = 60000
                    }
                }
                val zipFile = File(localFolder(map.hash), "${map.hash}.zip")
                zipFile.writeBytes(bytes)

                mq.publish("beatmaps", "beatsaver.new", null, map)
            }
        }
    }

    mq.consumeAck("bs.new", BeatsaverMap::class) { _, map ->
        println("start ${map.hash}")

        val zipFile = File(localFolder(map.hash), "${map.hash}.zip")
        val coverFile = File(localCoverFolder(map.hash), "${map.hash}.jpg")

        transaction(Connection.TRANSACTION_READ_COMMITTED, 3) {
            if (Versions.select { Versions.hash eq map.hash }.count() > 0) {
                // Another message got there first
                return@transaction
            }

            val user = User.select {
                User.hash eq map.uploader._id
            }.limit(1).firstOrNull()?.let { UserDao.wrapRow(it) }

            val userId = if (user?.email == null) {
                User.upsert(conflictColumn = User.hash) {
                    it[name] = map.uploader.username
                    it[hash] = map.uploader._id
                }
            } else {
                user.id
            }

            val beatmapId = Beatmap.insertAndGetId {
                it[name] = map.name
                it[description] = map.description
                it[bpm] = map.metadata.bpm
                it[duration] = map.metadata.duration
                it[songName] = map.metadata.songName
                it[songAuthorName] = map.metadata.songAuthorName
                it[songSubName] = map.metadata.songSubName
                it[levelAuthorName] = map.metadata.levelAuthorName
                it[uploaded] = map.uploaded
                it[automapper] = map.metadata.automapper != null
                it[uploader] = userId
            }

            ZipHelper.openZip(zipFile) {
                Versions.upsert(Versions.hash) {
                    it[mapId] = beatmapId
                    it[hash] = map.hash
                    it[key64] = map.key
                    it[uploaded] = map.uploaded
                    it[state] = EMapState.Published
                    it[sageScore] = scoreMap()
                }

                val version = VersionsDao.wrapRow(Versions.select {
                    Versions.hash eq map.hash
                }.first())

                val stats = info._difficultyBeatmapSets.flatMap { set ->
                    set._difficultyBeatmaps.map { diff ->
                        parseDifficulty(map.hash, diff, set, info, version)
                    }
                }

                Beatmap.update({ Beatmap.id eq beatmapId }) {
                    it[chroma] = stats.any { s -> s.chroma }
                    it[noodle] = stats.any { s -> s.noodle }
                    it[me] = stats.any { s -> s.me }
                    it[cinema] = stats.any { s -> s.cinema }

                    it[minNps] = stats.minByOrNull { s -> s.nps }?.nps?.min(maxAllowedNps) ?: BigDecimal.ZERO
                    it[maxNps] = stats.maxByOrNull { s -> s.nps }?.nps?.min(maxAllowedNps) ?: BigDecimal.ZERO
                    it[fullSpread] = info._difficultyBeatmapSets
                        .firstOrNull { set -> set.enumValue() == ECharacteristic.Standard }?._difficultyBeatmaps
                        ?.map { diff -> diff.enumValue() }
                        ?.distinct()?.count() == 5
                }

                val file = fromInfo(info._coverImageFilename)
                file?.inputStream()?.use { stream ->
                    val image = ImageIO.read(stream)
                    val newImageStream = ByteArrayOutputStream()
                    Thumbnails
                        .of(image)
                        .size(256, 256)
                        .outputFormat("JPEG")
                        .outputQuality(0.8)
                        .toOutputStream(newImageStream)
                    coverFile.writeBytes(newImageStream.toByteArray())
                }

                mq.publish("beatmaps", "maps.${beatmapId.value}.updated", null, beatmapId.value)
            }
        }
        println("done ${map.hash}")
    }

    GlobalScope.launch(es.asCoroutineDispatcher()) {
        delay(Duration.ofSeconds(20L))

        var page = 0

        val maxPages = 2900
        val batchSize = 100
        val partitions = maxPages / batchSize

        var run = Random().nextInt(300)
        var lastPage = 5000

        while (true) {
            val cutoff = if (run % 10 == 0) {
                page = ((run / 10) % partitions) * 100
                lastPage = page + 100
                Instant.EPOCH
            } else {
                Instant.now().minus(if (run % 10 == 1) 6 else 2, ChronoUnit.HOURS)
            }

            while (page >= 0) {
                val morePages = try {
                    scrapePage(page, cutoff, mq)
                    true
                } catch (e: Exception) {
                    println(e)
                    false
                }

                if (!morePages || page >= lastPage) {
                    println("Checked $page beatsaver pages")
                    page = -1
                } else {
                    page++
                }

                if (page % 50 == 0) {
                    println("On page $page")
                }
            }

            page = 0
            run++
            delay(Duration.ofMinutes(10))
        }
    }

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
            println("Scoresaber quals")
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
                println("Scoresaber ${page - 1}")
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

suspend fun scrapePage(page: Int, cutoff: Instant, mq: RabbitMQ): Int {
    // User agent to mimic client because jellyfish are dumb
    val time = Instant.now().epochSecond
    val json = randomClient.get<String>("https://beatsaver.com/api/maps/latest/$page?automapper=1&$time") {
        userAgent("BeatSaverDownloader/5.4.0.0 BeatSaverSharp/1.6.0.0 BeatSaber /1.14.0-oculus")
        timeout {
            socketTimeoutMillis = 30000
            requestTimeoutMillis = 60000
        }
    }
    val obj = jackson.readValue<BeatsaverList>(json)

    // Find gaps between keys and assume those are deleted, maps can hide between pages but should get picked up eventually
    val intIds = obj.docs.map { parseInt(it.key, 16) }
    var lId = Int.MAX_VALUE
    val missing = intIds.sortedByDescending { it }.fold(listOf<String>()) { l, it ->
        l.plus((if (lId == Int.MAX_VALUE) IntRange.EMPTY else (it + 1)..lId).map { v -> toHexString(v) }).also { _ ->
            lId = it - 1
        }
    }

    // This is possible in a single statement (and so is this method but exposed makes life hard)
    // but this allows us to publish an event when a map is deleted
    transaction {
        val mapIds = VersionsDao.wrapRows(Versions.select {
            Versions.key64 inList (missing) and (Versions.state eq EMapState.Published)
        }).map { it.mapId }

        Beatmap.updateReturning({
            Beatmap.id inList mapIds and Beatmap.deletedAt.isNull()
        }, {
            it[deletedAt] = NowExpression(deletedAt.columnType)
        }, Beatmap.id)?.forEach {
            val mapId = it[Beatmap.id].value
            mq.publish("beatmaps", "maps.${mapId}.deleted", null, mapId)
        }
    }

    // Publish an event for each map in the response, rows will be processed asynchronously
    obj.docs.forEach {
        mq.publish("beatmaps", "beatsaver.update", null, it)
    }

    if (obj.docs.none { it.uploaded > cutoff }) {
        throw Exception("No objects on this page were before the cutoff")
    }

    // Slow down progress now that no work is done here
    delay(Duration.ofSeconds(1L))

    return intIds.minByOrNull { it } ?: 0//existingKeys.plus(out)
}