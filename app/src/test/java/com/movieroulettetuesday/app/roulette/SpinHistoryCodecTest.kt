package com.movieroulettetuesday.app.roulette

import com.movieroulettetuesday.app.data.CorruptFileQuarantine
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3: the iOS SpinHistoryLog v2 file format, legacy reads, and moving a corrupt file aside. */
class SpinHistoryCodecTest {
    private val t = 1_791_000_000_000L // 2026-10-03

    @Test fun writesIosV2Format() {
        val log = SpinHistoryStore.Log(
            results = listOf(SpinResult("r1", "m1", "c1", t)),
            archivedTotal = 2, archivedMovieCounts = mapOf("old" to 2), archivedCategoryCounts = mapOf("a" to 2),
        )
        val o = Json.parseToJsonElement(SpinHistoryCodec.encode(log)).jsonObject
        assertEquals(2, o["version"]!!.jsonPrimitive.content.toInt())
        val r = o["results"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("categoryId", "id", "movieId", "timestamp"), r.keys)
        assertEquals((t - SpinHistoryCodec.REFERENCE_DATE_MS) / 1000.0, r["timestamp"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        val a = o["archive"]!!.jsonObject
        assertEquals("2", a["totalSpins"]!!.jsonPrimitive.content)
        assertEquals(log, SpinHistoryCodec.decode(SpinHistoryCodec.encode(log)))
    }

    @Test fun readsIosFileAndLegacyShapesLossily() {
        val ios = """{"version":2,"results":[{"id":"A","movieId":"m","categoryId":"c","timestamp":812000000.5},{"id":"B","movieId":3}],
            "archive":{"totalSpins":4,"movieCounts":{"x":4},"categoryCounts":{"k":4}}}"""
        val log = SpinHistoryCodec.decode(ios)!!
        assertEquals(listOf("A"), log.results.map { it.id })
        assertEquals(SpinHistoryCodec.REFERENCE_DATE_MS + 812_000_000_500L, log.results[0].timestampEpochMs)
        assertEquals(4, log.archivedTotal)
        assertEquals(mapOf("x" to 4), log.archivedMovieCounts)

        val bare = """[{"id":"A","movieId":"m","categoryId":"c","timestamp":0}]"""
        assertEquals(SpinHistoryCodec.REFERENCE_DATE_MS, SpinHistoryCodec.decode(bare)!!.results.single().timestampEpochMs)

        val oldAndroid = """{"version":2,"results":[{"id":"A","movieId":"m","categoryId":"c","timestampEpochMs":5}],"archivedTotal":1,"archivedMovieCounts":{"m":1},"archivedCategoryCounts":{"c":1}}"""
        val o = SpinHistoryCodec.decode(oldAndroid)!!
        assertEquals(5L, o.results.single().timestampEpochMs)
        assertEquals(1, o.archivedTotal)
        assertEquals(mapOf("c" to 1), o.archivedCategoryCounts)

        assertNull(SpinHistoryCodec.decode("{not json"))
        assertNull(SpinHistoryCodec.decode("42"))
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = Files.createTempDirectory("hist").toFile()
        val f = File(dir, "spin-history.json").apply { writeText("{garbage") }
        val store = SpinHistoryStore(f)
        assertEquals(0, store.totalSpins)
        assertFalse(f.exists())
        val backups = CorruptFileQuarantine.backups(f)
        assertEquals(1, backups.size)
        assertTrue(backups[0].name.matches(Regex("""spin-history\.corrupt-\d{8}-\d{6}\.json""")))
        assertEquals("{garbage", backups[0].readText())
        store.record(SpinResult(movieId = "m", categoryId = "c"))
        assertEquals(1, SpinHistoryStore(f).totalSpins)
    }

    @Test fun quarantineKeepsNewestThree() {
        val dir = Files.createTempDirectory("q").toFile()
        val f = File(dir, "x.json")
        repeat(5) { i ->
            f.writeText("bad $i")
            CorruptFileQuarantine.quarantine(f, now = 1_000L * i)
        }
        val names = CorruptFileQuarantine.backups(f).map { it.name }
        assertEquals(listOf("x.corrupt-19700101-000002.json", "x.corrupt-19700101-000003.json", "x.corrupt-19700101-000004.json"), names)
    }
}
