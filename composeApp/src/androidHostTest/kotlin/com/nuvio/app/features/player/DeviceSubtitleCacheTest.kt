package com.nuvio.app.features.player

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceSubtitleCacheTest {
    private val cacheDir: File = Files.createTempDirectory("device-subtitles-test").toFile()
    private val dir = File(cacheDir, "device-subtitles")

    @AfterTest
    fun cleanUp() {
        cacheDir.deleteRecursively()
    }

    private fun DeviceSubtitleCache.import(name: String, text: String = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n"): File =
        newFile(name, name.substringAfterLast('.')).also { it.writeText(text, Charsets.UTF_8) }

    @Test
    fun repeatedImportsInOneRunKeepEveryFileAPlayerMayShow() {
        val cache = DeviceSubtitleCache(dir)
        val files = (1..20).map { cache.import("الحلقة.ar.srt") }
        assertEquals(20, files.map { it.name }.toSet().size, "a new import overwrote an earlier one")
        assertTrue(files.all { it.isFile }, "a file of this run was removed")
        assertEquals(0, cache.removeOldCopies())
    }

    @Test
    fun aRestartRemovesOnlyTheCopiesOfTheEarlierRun() {
        val firstRun = DeviceSubtitleCache(dir)
        val old = listOf(firstRun.import("a.srt"), firstRun.import("b.ass"))
        val stray = File(dir, "left-over.vtt").apply { writeText("WEBVTT\n") }

        val secondRun = DeviceSubtitleCache(dir)   // a new process: nothing references old copies
        assertTrue(old.all { it.isFile } && stray.isFile, "files removed before the next import")
        val fresh = secondRun.import("c.srt")
        assertTrue(old.none { it.exists() } && !stray.exists(), "earlier run's copies are still there")
        assertTrue(fresh.isFile)
        val another = secondRun.import("d.ssa")
        assertTrue(fresh.isFile && another.isFile, "this run's file was removed")
        assertEquals(setOf(fresh.name, another.name), dir.list()!!.toSet())
    }

    @Test
    fun theCopyKeepsArabicTextAndItsFormat() {
        val cache = DeviceSubtitleCache(dir)
        val file = cache.newFile("فيلم.ar.ass", "ass")
        assertTrue(file.name.endsWith(".ass"))
        assertTrue(file.name.all { it.code < 128 }, "cache name is not ASCII: ${file.name}")
        file.writeText("[Script Info]\nمرحبا", Charsets.UTF_8)
        assertEquals("[Script Info]\nمرحبا", file.readText(Charsets.UTF_8))
    }
}
