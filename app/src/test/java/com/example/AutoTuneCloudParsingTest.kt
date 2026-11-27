package com.example

import com.example.autotune.SmartAutoTuneEngine
import com.example.model.AspectPreset
import com.example.model.AutoTuneEngineType
import com.example.model.AutoTuneGoal
import com.example.model.ClipSegment
import com.example.model.SubtitleAndChapterParser
import com.example.model.TelemetrySnapshot
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoTuneCloudParsingTest {

    @Test
    fun parseCloudJsonResultExtractsSuggestedHighlightsAndWebShapes() {
        val baseline = SmartAutoTuneEngine.runLocalEngine(
            goal = AutoTuneGoal.BALANCED_AUTO,
            customDirection = "",
            telemetry = TelemetrySnapshot(),
            durationMs = 45_000L,
            aspectRatio = 16f / 9f,
            waveformPeaks = emptyList(),
        )
        val cloudJson = """
            {
              "lookTitle": "Crisp Cloud Master",
              "summary": "Balanced highlights and clear voice.",
              "brightness": 0.08,
              "contrast": 1.15,
              "saturation": 1.12,
              "sharpen": 0.35,
              "denoiseEnabled": true,
              "volumeGain": 1.2,
              "loudnessNormalize": true,
              "voiceClarityEq": true,
              "recommendedAspect": "9:16",
              "recommendedSplitSec": 15,
              "recommendSilenceCut": false,
              "suggestedHighlights": [
                { "label": "Hook", "startSec": 2.0, "endSec": 9.5 },
                { "label": "Payoff", "startSec": 22.0, "endSec": 35.0 }
              ]
            }
        """.trimIndent()

        val parsed = SmartAutoTuneEngine.parseCloudJsonResult(
            rawText = cloudJson,
            engine = AutoTuneEngineType.GEMINI_BYOK,
            modelId = "gemini-2.5-flash",
            baseline = baseline,
            durationMs = 45_000L,
        )
        assertNotNull(parsed)
        assertEquals("Crisp Cloud Master", parsed!!.lookTitle)
        assertEquals(AspectPreset.PORTRAIT_9_16, parsed.recommendedAspect)
        assertEquals(2, parsed.suggestedHighlights.size)
        assertEquals("Hook", parsed.suggestedHighlights[0].label)
        assertEquals(2000L, parsed.suggestedHighlights[0].startMs)
        assertEquals(9500L, parsed.suggestedHighlights[0].endMs)
        assertEquals(22000L, parsed.suggestedHighlights[1].startMs)
        assertEquals(35000L, parsed.suggestedHighlights[1].endMs)
    }

    @Test
    fun chaptersJsonExportMatchesWebSchema() {
        val clips = listOf(
            ClipSegment(label = "Hook", startMs = 0L, endMs = 12_500L, isSelected = true, chapterTitle = "Opening Hook"),
            ClipSegment(label = "Body", startMs = 12_500L, endMs = 30_000L, isSelected = false),
        )
        val jsonText = SubtitleAndChapterParser.exportChaptersJson(clips)
        val arr = JSONArray(jsonText)
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals(1, first.getInt("index"))
        assertEquals("Opening Hook", first.getString("label"))
        assertEquals(0.0, first.getDouble("startTime"), 0.001)
        assertEquals(12.5, first.getDouble("endTime"), 0.001)
        assertEquals(12.5, first.getDouble("duration"), 0.001)
        assertEquals(true, first.getBoolean("selected"))

        val second = arr.getJSONObject(1)
        assertEquals(2, second.getInt("index"))
        assertEquals("Body", second.getString("label"))
        assertEquals(false, second.getBoolean("selected"))
    }
}
