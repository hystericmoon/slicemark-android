package com.example

import com.example.autotune.SmartAutoTuneEngine
import com.example.media.MediaAnalyzer
import com.example.media.MediaStoreHelper
import com.example.model.AudioMotionConfig
import com.example.model.AutoTuneGoal
import com.example.model.ClipSegment
import com.example.model.EditorUiState
import com.example.model.ExportConfig
import com.example.model.ExportFormat
import com.example.model.ExportQualityPreset
import com.example.model.ExportResolution
import com.example.model.OverlayConfig
import com.example.model.RemainderPolicy
import com.example.model.SplitMethod
import com.example.model.SubtitleAndChapterParser
import com.example.model.TelemetrySnapshot
import com.example.model.TrimConfig
import com.example.model.VisualConfig
import com.example.viewmodel.EditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SlicemarkCoreUnitTest {

    // 1. Subtitles (.SRT & .VTT) + Per-Clip Retiming & Speed Adjustment
    @Test
    fun srtParsingActiveCueAndSpeedAdjustedRetiming() {
        val rawSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            <i>Welcome</i> to <b>Slicemark</b>

            2
            00:00:08,500 --> 00:00:12,500
            Second segment caption
        """.trimIndent()

        val cues = SubtitleAndChapterParser.parseSubtitles(rawSrt)
        assertEquals(2, cues.size)
        assertEquals("Welcome to Slicemark", cues[0].text)
        assertEquals(1000L, cues[0].startMs)
        assertEquals("Welcome to Slicemark", SubtitleAndChapterParser.activeCueAt(cues, 2500L)?.text)
        assertNull(SubtitleAndChapterParser.activeCueAt(cues, 6000L))

        // Slice clip 8000ms..13000ms at 1.0x speed
        val sliced1x = SubtitleAndChapterParser.sliceAndRetimeSrtForClip(cues, 8_000L, 13_000L, 1.0f)
        assertTrue(sliced1x.contains("00:00:00,500 --> 00:00:04,500"))
        assertTrue(sliced1x.contains("Second segment caption"))

        // Slice same clip at 2.0x speed -> timestamps halve cleanly
        val sliced2x = SubtitleAndChapterParser.sliceAndRetimeSrtForClip(cues, 8_000L, 13_000L, 2.0f)
        assertTrue(sliced2x.contains("00:00:00,250 --> 00:00:02,250"))
    }

    @Test
    fun vttAndYouTubeChaptersParsingAndExport() {
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
            01:05 Duplicate Timestamp Ignored
            05:00 Out Of Bounds Ignored
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

    // 2. Waveform Silence Detection
    @Test
    fun silenceRangeDetectionAndEmptyAudioSafety() {
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

        assertTrue(MediaAnalyzer.detectSilenceRanges(emptyList(), 8_000L, 0.16f, 700L).isEmpty())
    }

    // 3. Batch Split Engine (Interval, N Parts, Max MB, Overlap, Snap-to-Silence, Remainder Policies)
    @Test
    fun batchSplitModesAndRemainderPolicies() {
        // 32s video split by 15s -> 15s, 15s, 2s remainder (<4s)
        val baseTrim = TrimConfig(splitMethod = SplitMethod.INTERVAL, intervalSec = 15)

        val keepClips = EditorViewModel.computeBatchClipsPure(
            durationMs = 32_000L,
            fileSizeBytes = 20_000_000L,
            bitrateBps = 5_000_000L,
            displayName = "shoot.mp4",
            trim = baseTrim.copy(remainderPolicy = RemainderPolicy.KEEP),
            silences = emptyList(),
        )
        assertEquals(3, keepClips.size)
        assertEquals(30_000L, keepClips[2].startMs)
        assertEquals(32_000L, keepClips[2].endMs)

        val dropClips = EditorViewModel.computeBatchClipsPure(
            durationMs = 32_000L,
            fileSizeBytes = 20_000_000L,
            bitrateBps = 5_000_000L,
            displayName = "shoot.mp4",
            trim = baseTrim.copy(remainderPolicy = RemainderPolicy.DROP_SHORT),
            silences = emptyList(),
        )
        assertEquals(2, dropClips.size)
        assertEquals(30_000L, dropClips.last().endMs)

        val mergeClips = EditorViewModel.computeBatchClipsPure(
            durationMs = 32_000L,
            fileSizeBytes = 20_000_000L,
            bitrateBps = 5_000_000L,
            displayName = "shoot.mp4",
            trim = baseTrim.copy(remainderPolicy = RemainderPolicy.MERGE_LAST),
            silences = emptyList(),
        )
        assertEquals(2, mergeClips.size)
        assertEquals(15_000L, mergeClips.last().startMs)
        assertEquals(32_000L, mergeClips.last().endMs)

        // Equal N-Parts
        val nParts = EditorViewModel.computeBatchClipsPure(
            durationMs = 60_000L,
            fileSizeBytes = 30_000_000L,
            bitrateBps = 4_000_000L,
            displayName = "vlog.mp4",
            trim = TrimConfig(splitMethod = SplitMethod.N_PARTS, targetPartCount = 4),
            silences = emptyList(),
        )
        assertEquals(4, nParts.size)
        assertEquals(15_000L, nParts[0].durationMs)

        // Target Max MB (10 MB Discord target on 40 MB / 40s video -> ~9s chunks)
        val maxMb = EditorViewModel.computeBatchClipsPure(
            durationMs = 40_000L,
            fileSizeBytes = 40L * 1024L * 1024L,
            bitrateBps = 8_000_000L,
            displayName = "discord.mp4",
            trim = TrimConfig(splitMethod = SplitMethod.MAX_MB, maxFileSizeMb = 10),
            silences = emptyList(),
        )
        assertTrue(maxMb.size >= 4)
        assertTrue(maxMb.first().durationMs <= 10_000L)

        // Overlap pre-roll + Snap to silence + Merge short 1s tail
        val snapped = EditorViewModel.computeBatchClipsPure(
            durationMs = 30_000L,
            fileSizeBytes = 10_000_000L,
            bitrateBps = 4_000_000L,
            displayName = "talk.mp4",
            trim = TrimConfig(
                splitMethod = SplitMethod.INTERVAL,
                intervalSec = 15,
                snapToSilence = true,
                overlapPreRollSec = 1.5f,
                remainderPolicy = RemainderPolicy.MERGE_LAST,
            ),
            silences = listOf(13_500L..14_500L), // midpoint 14_000ms within ±3s of 15_000ms
        )
        assertEquals(2, snapped.size)
        assertEquals(14_000L, snapped[0].endMs)
        assertEquals(12_500L, snapped[1].startMs) // 14_000ms - 1500ms pre-roll
        assertEquals(30_000L, snapped[1].endMs)
    }

    // 4. Smart Auto-Tune Local Engine (All 5 Goals + No Fabricated Highlights on Silent Media)
    @Test
    fun localAutoTuneCalibratesAcrossGoalsAndRespectsEmptyAudio() {
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

        for (goal in AutoTuneGoal.entries) {
            val res = SmartAutoTuneEngine.runLocalEngine(
                goal = goal,
                customDirection = "crisp bright",
                telemetry = telemetry,
                durationMs = 30_000L,
                aspectRatio = 1080f / 1920f,
                waveformPeaks = List(60) { if (it in 10..20) 0.9f else 0.3f },
            )
            assertTrue("Brightness should lift dark clip for $goal", res.brightness > 0f)
            assertTrue("Contrast in valid bounds for $goal", res.contrast in 0.7f..1.45f)
            assertTrue("Saturation in valid bounds for $goal", res.saturation in 0.6f..1.6f)
            assertTrue("Denoise enabled for noisy shadows on $goal", res.denoiseEnabled)
            assertTrue("Highlights extracted from real waveform peaks on $goal", res.suggestedHighlights.isNotEmpty())
        }

        // Verify zero fabricated highlights when no audio waveform peaks exist
        val noAudioRes = SmartAutoTuneEngine.runLocalEngine(
            goal = AutoTuneGoal.BALANCED_AUTO,
            customDirection = "",
            telemetry = telemetry,
            durationMs = 30_000L,
            aspectRatio = 16f / 9f,
            waveformPeaks = emptyList(),
        )
        assertTrue("Must not invent fake highlights when audio is absent", noAudioRes.suggestedHighlights.isEmpty())
    }

    // 5. Security & Input Sanitization QA
    @Test
    fun securitySanitizersBlockInjectionAndPathTraversal() {
        assertEquals("gemini-2.5-flash", SmartAutoTuneEngine.sanitizeModelId("gemini-2.5-flash"))
        assertEquals(
            "google/gemini-2.0-flash-exp:free",
            SmartAutoTuneEngine.sanitizeModelId("google/gemini-2.0-flash-exp:free")
        )
        assertNull(SmartAutoTuneEngine.sanitizeModelId("../../etc/passwd"))
        assertNull(SmartAutoTuneEngine.sanitizeModelId("gemini-2.5-flash?key=stolen"))
        assertNull(SmartAutoTuneEngine.sanitizeModelId("model with spaces"))
        assertNull(SmartAutoTuneEngine.sanitizeModelId("   "))

        val safe1 = MediaStoreHelper.sanitizeDisplayName("../../evil/path\\clip.mp4")
        assertFalse(safe1.contains(".."))
        assertFalse(safe1.contains("/"))
        assertFalse(safe1.contains("\\"))
        assertTrue(safe1.endsWith(".mp4"))

        val safe2 = MediaStoreHelper.sanitizeDisplayName("   ")
        assertNotNull(safe2)
        assertTrue(safe2.isNotBlank())
    }

    // 6. Lossless Stream-Copy Eligibility Guards
    @Test
    fun losslessEligibilityDisablesWhenAnyEffectIsActive() {
        val cleanState = EditorUiState(
            exportConfig = ExportConfig(
                format = ExportFormat.MP4,
                resolution = ExportResolution.ORIGINAL,
                qualityPreset = ExportQualityPreset.LOSSLESS,
            )
        )
        assertTrue(cleanState.isLosslessEligible)
        assertFalse(cleanState.copy(visualConfig = VisualConfig(sharpen = 0.4f)).isLosslessEligible)
        assertFalse(cleanState.copy(visualConfig = VisualConfig(denoiseEnabled = true)).isLosslessEligible)
        assertFalse(cleanState.copy(overlayConfig = OverlayConfig(customWatermark = "@studio")).isLosslessEligible)
        assertFalse(cleanState.copy(audioMotionConfig = AudioMotionConfig(voiceClarityEq = true)).isLosslessEligible)
        assertFalse(cleanState.copy(exportConfig = cleanState.exportConfig.copy(format = ExportFormat.WEBM)).isLosslessEligible)
        assertFalse(cleanState.copy(exportConfig = cleanState.exportConfig.copy(resolution = ExportResolution.P720)).isLosslessEligible)
    }

    // 7. Production Manifest, Privacy & Google Play Policy Release Verification
    @Test
    fun releaseManifestStringsAndBackupRulesComplyWithProductionPolicy() {
        val moduleRoot = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val manifest = File(moduleRoot, "AndroidManifest.xml").readText()
        val strings = File(moduleRoot, "res/values/strings.xml").readText()
        val backupRules = File(moduleRoot, "res/xml/backup_rules.xml").readText()
        val dataExtractionRules = File(moduleRoot, "res/xml/data_extraction_rules.xml").readText()

        // App name must be production "Slicemark", never template "My Application"
        assertTrue(strings.contains("<string name=\"app_name\">Slicemark</string>"))
        assertFalse(strings.contains("My Application"))
        assertFalse(manifest.contains("Theme.MyApplication"))

        // Zero banned broad storage permissions (Google Play Photo Picker compliance)
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("READ_MEDIA_VIDEO"))
        assertFalse(manifest.contains("READ_MEDIA_IMAGES"))

        // Android 15 Foreground Service mediaProcessing compliance
        assertTrue(manifest.contains("FOREGROUND_SERVICE_MEDIA_PROCESSING"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaProcessing\""))

        // Encrypted BYOK key vault excluded from cloud backups and device transfer
        assertTrue(backupRules.contains("slicemark_byok_vault.xml"))
        assertTrue(dataExtractionRules.contains("slicemark_byok_vault.xml"))
    }
}
