package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonStreamMatcherTest {

    private val reference = SeasonQueueReference(
        addonId = "addon",
        addonName = "Addon",
        bingeGroup = "group-a",
        text = SeasonStreamMatcher.describe(stream("Show S01E01 2160p DV HEVC WEB-DL", "group-a")),
    )

    @Test
    fun sameBingeGroupWithOtherQualityIsSkipped() {
        val sdr = stream("Show S01E02 1080p WEB-DL", "group-a")
        val match = stream("Show S01E02 2160p DV HEVC WEB-DL", "group-b")
        assertEquals(listOf(match), SeasonStreamMatcher.rank(listOf(sdr, match), reference))
    }

    @Test
    fun sameBingeGroupAndQualityLeads() {
        val grouped = stream("Show S01E02 2160p DV HEVC", "group-a")
        val similar = stream("Show S01E02 2160p DV HEVC WEB-DL", "group-b")
        assertEquals(listOf(grouped, similar), SeasonStreamMatcher.rank(listOf(similar, grouped), reference))
    }

    private fun stream(name: String, group: String) = StreamItem(
        name = name,
        url = "https://example.com/${name.hashCode()}.mkv",
        addonName = "Addon",
        addonId = "addon",
        behaviorHints = StreamBehaviorHints(bingeGroup = group),
    )
}
