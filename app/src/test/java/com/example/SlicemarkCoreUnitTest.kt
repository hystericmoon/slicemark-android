package com.example

import android.net.Uri
import com.example.autotune.SmartAutoTuneEngine
import com.example.media.MediaAnalyzer
import com.example.model.AutoTuneGoal
import com.example.model.ClipSegment
import com.example.model.SourceMedia
import com.example.model.SubtitleAndChapterParser
import com.example.model.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlicemarkCoreUnitTest {

    @Test
    fun srtParsingAndPerClipRetiming() {
        val rawSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            Welcome to Slicemark

            2
            00:00:08,500 --> 00:00:12,000
            Second segment caption
        """.trimIndent()

        val cues = SubtitleAndChapterParser.parseSubtitles(rawSrt)
        assertEquals(2, cues.size)
        assertEquals("Welcome to Slicemark", cues[0].text)
        assertEquals(1000L, cues[0].startMs)

        // Slice clip from 8000ms..13000ms at 1.0x speed
        val sliced = SubtitleAndChapterParser.sliceAndRetimeSrtForClip(cues, 8000L, 13_000L, 1.0f)
        assertTrue(sliced.contains("00:00:00,500 --> 00:00:04,000"))
        assertTrue(sliced.contains("Second segment caption"))
    }

    @Test
    fun vttAndChaptersParsing() {
        val rawVtt = """
            WEBVTT

            00:02.500 --> 00:05.000
            Studio intro
        """.trimIndent()
        val cues = SubtitleAndChapterParser.parseSubtitles(rawVtt)
        assertEquals(1, cues.size)
        assertEquals(2500L, cues.first().startMs)

        val chaptersRaw = """
            00:00 Intro
            00:15 Color Grade
            01:05 Export Tips
        """.trimIndent()
        val marks = SubtitleAndChapterParser.parseChapters(chaptersRaw, 120_000L)
        assertEquals(3, marks.size)
        assertEquals("Color Grade", marks[1].title)
        assertEquals(15_000L, marks[1].startMs)
        assertEquals(65_000L, marks[2].startMs)

        val exported = SubtitleAndChapterParser.exportChaptersText(
            listOf(
                ClipSegment(label = "Intro", startMs = 0L, endMs = 15_000L, chapterTitle = "Intro"),
                ClipSegment(label = "Body", startMs = 15_000L, endMs = 45_000L, chapterTitle = "Body"),
            )
        )
        assertTrue(exported.contains("00:00 Intro"))
        assertTrue(exported.contains("00:15 Body"))
    }

    @Test
    fun silenceRangeDetectionFromWaveform() {
        val peaks = listOf(0.8f, 0.7f, 0.05f, 0.04f, 0.06f, 0.05f, 0.85f, 0.9f)
        val silences = MediaAnalyzer.detectSilenceRanges(
            waveformPeaks = peaks,
            durationMs = 8_000L,
            threshold = 0.16f,
            minSilenceMs = 1_500L,
        )
        assertEquals(1, silences.size)
        assertEquals(2_000L, silences.first().first)
        assertEquals(6_000L, silences.first().last)
    }

    @Test
    fun localAutoTuneCalibratesFromTelemetry() {
        val telemetry = TelemetrySnapshot(
            sampledFrames = 6,
            meanLuma = 0.25f,
            p05Luma = 0.08f,
            p95Luma = 0.52f,
            contrastSpread = 0.44f,
            meanSaturation = 0.30f,
            edgeSharpness = 0.28f,
            shadowNoise = 0.30f,
            audioRmsDb = -26f,
            quietRatio = 0.28f,
        )
        val res = SmartAutoTuneEngine.runLocalEngine(
            goal = AutoTuneGoal.PODCAST_VOICE,
            customDirection = "crisp bright voice",
            telemetry = telemetry,
            durationMs = 30_000L,
            aspectRatio = 1080f / 1920f,
            waveformPeaks = List(60) { if (it in 10..20) 0.9f else 0.3f },
        )
        assertTrue(res.brightness > 0f)
        assertTrue(res.contrast > 1f)
        assertTrue(res.sharpen > 0.2f)
        assertTrue(res.denoiseEnabled)
        assertTrue(res.voiceClarityEq)
        assertTrue(res.loudnessNormalize)
        assertTrue(res.recommendSilenceCut)
        assertTrue(res.suggestedHighlights.isNotEmpty())
    }
}
