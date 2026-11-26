package com.example.autotune

import com.example.model.AspectPreset
import com.example.model.AutoTuneEngineType
import com.example.model.AutoTuneGoal
import com.example.model.AutoTuneResult
import com.example.model.SourceMedia
import com.example.model.SuggestedHighlight
import com.example.model.TelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class InvalidApiKeyException(message: String) : Exception(message)

object SmartAutoTuneEngine {

    val openRouterPresets = listOf(
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemma-3-27b-it:free",
        "qwen/qwen-2.5-72b-instruct:free",
        "openai/gpt-4o-mini",
        "anthropic/claude-3.5-sonnet",
    )

    val geminiPresets = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun runAutoTune(
        engine: AutoTuneEngineType,
        goal: AutoTuneGoal,
        customDirection: String,
        telemetry: TelemetrySnapshot,
        source: SourceMedia,
        waveformPeaks: List<Float>,
        apiKey: String,
        selectedModel: String,
    ): AutoTuneResult = withContext(Dispatchers.IO) {
        val localBaseline = runLocalEngine(goal, customDirection, telemetry, source, waveformPeaks)

        if (engine == AutoTuneEngineType.LOCAL) {
            return@withContext localBaseline
        }

        if (apiKey.isBlank()) {
            throw InvalidApiKeyException(
                "Enter your ${if (engine == AutoTuneEngineType.OPENROUTER_BYOK) "OpenRouter" else "Gemini"} API key first, or switch to the Local (100% offline) engine."
            )
        }

        return@withContext when (engine) {
            AutoTuneEngineType.OPENROUTER_BYOK -> runOpenRouterWithFallback(
                apiKey = apiKey,
                primaryModel = selectedModel.ifBlank { openRouterPresets.first() },
                goal = goal,
                customDirection = customDirection,
                telemetry = telemetry,
                source = source,
                localFallback = localBaseline,
            )
            AutoTuneEngineType.GEMINI_BYOK -> runGeminiWithFallback(
                apiKey = apiKey,
                primaryModel = selectedModel.ifBlank { geminiPresets.first() },
                goal = goal,
                customDirection = customDirection,
                telemetry = telemetry,
                source = source,
                localFallback = localBaseline,
            )
            AutoTuneEngineType.LOCAL -> localBaseline
        }
    }

    /**
     * Engine 1: Local (100% offline, $0) deterministic telemetry calibration.
     */
    fun runLocalEngine(
        goal: AutoTuneGoal,
        customDirection: String,
        telemetry: TelemetrySnapshot,
        source: SourceMedia,
        waveformPeaks: List<Float>,
    ): AutoTuneResult = runLocalEngine(
        goal = goal,
        customDirection = customDirection,
        telemetry = telemetry,
        durationMs = source.durationMs,
        aspectRatio = source.aspectRatio,
        waveformPeaks = waveformPeaks,
    )

    fun runLocalEngine(
        goal: AutoTuneGoal,
        customDirection: String,
        telemetry: TelemetrySnapshot,
        durationMs: Long,
        aspectRatio: Float,
        waveformPeaks: List<Float>,
    ): AutoTuneResult {
        val dir = customDirection.lowercase()

        // Base exposure correction toward goal target luma
        val targetLuma = when (goal) {
            AutoTuneGoal.BALANCED_AUTO -> 0.50f
            AutoTuneGoal.CINEMATIC -> 0.44f
            AutoTuneGoal.SOCIAL_PUNCH -> 0.53f
            AutoTuneGoal.CLEAN_STUDIO -> 0.55f
            AutoTuneGoal.PODCAST_VOICE -> 0.51f
        }
        var brightness = ((targetLuma - telemetry.meanLuma) * 0.42f).coerceIn(-0.18f, 0.22f)

        // Contrast calibration from p05..p95 dynamic spread
        val targetSpread = when (goal) {
            AutoTuneGoal.BALANCED_AUTO -> 0.72f
            AutoTuneGoal.CINEMATIC -> 0.82f
            AutoTuneGoal.SOCIAL_PUNCH -> 0.80f
            AutoTuneGoal.CLEAN_STUDIO -> 0.70f
            AutoTuneGoal.PODCAST_VOICE -> 0.68f
        }
        var contrast = (1.0f + (targetSpread - telemetry.contrastSpread) * 0.48f).coerceIn(0.88f, 1.32f)

        // Saturation calibration from HSV mean saturation
        val targetSat = when (goal) {
            AutoTuneGoal.BALANCED_AUTO -> 0.42f
            AutoTuneGoal.CINEMATIC -> 0.38f
            AutoTuneGoal.SOCIAL_PUNCH -> 0.54f
            AutoTuneGoal.CLEAN_STUDIO -> 0.36f
            AutoTuneGoal.PODCAST_VOICE -> 0.40f
        }
        var saturation = (1.0f + (targetSat - telemetry.meanSaturation) * 0.75f).coerceIn(0.82f, 1.38f)

        // Sharpen & shadow denoise from edge sharpness + dark-region noise
        var sharpen = when {
            telemetry.edgeSharpness < 0.35f -> 0.34f
            telemetry.edgeSharpness < 0.55f -> 0.18f
            else -> 0.08f
        }
        if (goal == AutoTuneGoal.SOCIAL_PUNCH || goal == AutoTuneGoal.CLEAN_STUDIO) {
            sharpen = (sharpen + 0.10f).coerceAtMost(0.55f)
        }
        val denoise = telemetry.shadowNoise > 0.22f || goal == AutoTuneGoal.CLEAN_STUDIO

        // Custom direction adjustments
        if ("bright" in dir || "lift" in dir) brightness = (brightness + 0.08f).coerceAtMost(0.30f)
        if ("moody" in dir || "dark" in dir || "film" in dir) {
            brightness = (brightness - 0.06f).coerceAtLeast(-0.22f)
            contrast = (contrast + 0.08f).coerceAtMost(1.38f)
        }
        if ("vibrant" in dir || "pop" in dir || "punch" in dir) saturation = (saturation + 0.14f).coerceAtMost(1.48f)
        if ("muted" in dir || "minimal" in dir || "neutral" in dir) saturation = (saturation - 0.12f).coerceAtLeast(0.75f)
        if ("crisp" in dir || "sharp" in dir) sharpen = (sharpen + 0.15f).coerceAtMost(0.65f)

        // Audio calibration from real RMS dB and quiet ratio
        val targetRmsDb = if (goal == AutoTuneGoal.PODCAST_VOICE) -15f else -17f
        val dbDelta = (targetRmsDb - telemetry.audioRmsDb).coerceIn(-4f, 8f)
        val volumeGain = (1.0f + dbDelta * 0.06f).coerceIn(0.85f, 1.65f)
        val loudnessNorm = telemetry.audioRmsDb < -20f || goal == AutoTuneGoal.PODCAST_VOICE || goal == AutoTuneGoal.SOCIAL_PUNCH
        val voiceEq = goal == AutoTuneGoal.PODCAST_VOICE || "voice" in dir || "podcast" in dir || "talk" in dir
        val recommendSilenceCut = telemetry.quietRatio >= 0.16f || goal == AutoTuneGoal.PODCAST_VOICE

        val recommendedAspect = when {
            goal == AutoTuneGoal.SOCIAL_PUNCH -> AspectPreset.PORTRAIT_9_16
            goal == AutoTuneGoal.CINEMATIC -> AspectPreset.LANDSCAPE_16_9
            aspectRatio < 0.8f -> AspectPreset.PORTRAIT_9_16
            aspectRatio > 1.3f -> AspectPreset.LANDSCAPE_16_9
            else -> AspectPreset.SQUARE_1_1
        }

        val recommendedSplitSec = when {
            durationMs <= 20_000L -> 15
            goal == AutoTuneGoal.SOCIAL_PUNCH -> 15
            goal == AutoTuneGoal.PODCAST_VOICE -> 45
            else -> 30
        }

        val highlights = buildSuggestedHighlights(durationMs, waveformPeaks)

        val lumaDesc = when {
            telemetry.meanLuma < 0.36f -> "underexposed shadows"
            telemetry.meanLuma > 0.66f -> "bright highlights"
            else -> "balanced exposure"
        }
        val spreadDesc = if (telemetry.contrastSpread < 0.60f) "compressed dynamic range" else "solid tonal spread"
        val summary = buildString {
            append("${goal.label} look calibrated from ${telemetry.sampledFrames} keyframes ($lumaDesc, $spreadDesc) ")
            append("and ${telemetry.audioRmsDb.roundToInt()} dB audio RMS.")
            if (recommendSilenceCut) append(" Detected ${(telemetry.quietRatio * 100).roundToInt()}% quiet pauses ready for Jump-Cut.")
        }

        return AutoTuneResult(
            engineUsed = AutoTuneEngineType.LOCAL,
            modelUsed = "Slicemark Telemetry LUT v1",
            goal = goal,
            lookTitle = "${goal.label} Master",
            summary = summary,
            brightness = round2(brightness),
            contrast = round2(contrast),
            saturation = round2(saturation),
            sharpen = round2(sharpen),
            denoiseEnabled = denoise,
            volumeGain = round2(volumeGain),
            loudnessNormalize = loudnessNorm,
            voiceClarityEq = voiceEq,
            recommendedAspect = recommendedAspect,
            recommendedSplitSec = recommendedSplitSec,
            recommendSilenceCut = recommendSilenceCut,
            suggestedHighlights = highlights,
            telemetry = telemetry,
        )
    }

    private fun runOpenRouterWithFallback(
        apiKey: String,
        primaryModel: String,
        goal: AutoTuneGoal,
        customDirection: String,
        telemetry: TelemetrySnapshot,
        source: SourceMedia,
        localFallback: AutoTuneResult,
    ): AutoTuneResult {
        val chain = buildList {
            add(primaryModel)
            openRouterPresets.forEach { if (it != primaryModel) add(it) }
        }
        val prompt = buildTelemetryPrompt(goal, customDirection, telemetry, source)
        var lastTransientError = "OpenRouter models busy"

        for (modelId in chain) {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("temperature", 0.2)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are a colorist & audio engineer. Respond ONLY with valid minified JSON.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    },
                )
            }

            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://slicemark.hahostudio.app")
                .addHeader("X-Title", "Slicemark Android")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 401 || resp.code == 403) {
                        throw InvalidApiKeyException("OpenRouter rejected your API key (HTTP ${resp.code}). Verify the key in Tune settings.")
                    }
                    if (!resp.isSuccessful) {
                        lastTransientError = "OpenRouter $modelId returned HTTP ${resp.code}"
                        continue
                    }
                    val root = JSONObject(bodyStr)
                    val content = root.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    val parsed = parseCloudJsonResult(content, AutoTuneEngineType.OPENROUTER_BYOK, modelId, localFallback)
                    if (parsed != null) return parsed
                    lastTransientError = "Unparseable response from $modelId"
                }
            } catch (e: InvalidApiKeyException) {
                throw e
            } catch (e: Throwable) {
                lastTransientError = e.message ?: "Network timeout on $modelId"
            }
        }

        return localFallback.copy(
            fellBackToLocal = true,
            fallbackReason = "Cloud models busy ($lastTransientError) — applied Local offline Auto-Tune automatically.",
        )
    }

    private fun runGeminiWithFallback(
        apiKey: String,
        primaryModel: String,
        goal: AutoTuneGoal,
        customDirection: String,
        telemetry: TelemetrySnapshot,
        source: SourceMedia,
        localFallback: AutoTuneResult,
    ): AutoTuneResult {
        val chain = buildList {
            add(primaryModel)
            geminiPresets.forEach { if (it != primaryModel) add(it) }
        }
        val prompt = buildTelemetryPrompt(goal, customDirection, telemetry, source)
        var lastTransientError = "Gemini models busy"

        for (modelId in chain) {
            val cleanModel = modelId.removePrefix("models/")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put(
                                "parts",
                                JSONArray().apply {
                                    put(JSONObject().apply { put("text", prompt) })
                                },
                            )
                        })
                    },
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.2)
                        put("responseMimeType", "application/json")
                    },
                )
            }

            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 400 && bodyStr.contains("API_KEY_INVALID", ignoreCase = true)) {
                        throw InvalidApiKeyException("Gemini API key is invalid. Please update your key in Tune settings.")
                    }
                    if (resp.code == 401 || resp.code == 403) {
                        throw InvalidApiKeyException("Gemini rejected your API key (HTTP ${resp.code}). Verify key permissions.")
                    }
                    if (!resp.isSuccessful) {
                        lastTransientError = "Gemini $cleanModel returned HTTP ${resp.code}"
                        continue
                    }
                    val root = JSONObject(bodyStr)
                    val text = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                    val parsed = parseCloudJsonResult(text, AutoTuneEngineType.GEMINI_BYOK, cleanModel, localFallback)
                    if (parsed != null) return parsed
                    lastTransientError = "Unparseable response from $cleanModel"
                }
            } catch (e: InvalidApiKeyException) {
                throw e
            } catch (e: Throwable) {
                lastTransientError = e.message ?: "Network error on $cleanModel"
            }
        }

        return localFallback.copy(
            fellBackToLocal = true,
            fallbackReason = "Gemini cloud busy ($lastTransientError) — applied Local offline Auto-Tune automatically.",
        )
    }

    private fun buildTelemetryPrompt(
        goal: AutoTuneGoal,
        customDirection: String,
        t: TelemetrySnapshot,
        source: SourceMedia,
    ): String = """
        Analyze this video telemetry and return ONLY a single JSON object with keys:
        lookTitle (string), summary (string, max 160 chars),
        brightness (float -0.25..0.25), contrast (float 0.8..1.4), saturation (float 0.7..1.5),
        sharpen (float 0..0.7), denoiseEnabled (boolean),
        volumeGain (float 0.7..1.7), loudnessNormalize (boolean), voiceClarityEq (boolean),
        recommendedAspect (one of ORIGINAL, PORTRAIT_9_16, SQUARE_1_1, SOCIAL_4_5, LANDSCAPE_16_9),
        recommendedSplitSec (int 15, 30, 45, or 60), recommendSilenceCut (boolean).
        Style goal: ${goal.label} (${goal.hint}).
        Custom user direction: ${customDirection.ifBlank { "None" }}.
        Video: ${source.effectiveWidth}x${source.effectiveHeight}, duration ${source.durationMs / 1000}s.
        Telemetry (${t.sampledFrames} keyframes): meanLuma=${round2(t.meanLuma)}, p05=${round2(t.p05Luma)}, p95=${round2(t.p95Luma)},
        contrastSpread=${round2(t.contrastSpread)}, saturation=${round2(t.meanSaturation)},
        edgeSharpness=${round2(t.edgeSharpness)}, shadowNoise=${round2(t.shadowNoise)},
        audioRmsDb=${t.audioRmsDb.roundToInt()}, quietRatio=${round2(t.quietRatio)}.
    """.trimIndent()

    private fun parseCloudJsonResult(
        rawText: String,
        engine: AutoTuneEngineType,
        modelId: String,
        baseline: AutoTuneResult,
    ): AutoTuneResult? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = JSONObject(rawText.substring(start, end + 1))
            val aspect = runCatching {
                AspectPreset.valueOf(obj.optString("recommendedAspect", baseline.recommendedAspect.name))
            }.getOrDefault(baseline.recommendedAspect)
            baseline.copy(
                engineUsed = engine,
                modelUsed = modelId,
                fellBackToLocal = false,
                fallbackReason = null,
                lookTitle = obj.optString("lookTitle", baseline.lookTitle).take(48),
                summary = obj.optString("summary", baseline.summary).take(220),
                brightness = round2(obj.optDouble("brightness", baseline.brightness.toDouble()).toFloat().coerceIn(-0.35f, 0.35f)),
                contrast = round2(obj.optDouble("contrast", baseline.contrast.toDouble()).toFloat().coerceIn(0.6f, 1.5f)),
                saturation = round2(obj.optDouble("saturation", baseline.saturation.toDouble()).toFloat().coerceIn(0.4f, 1.6f)),
                sharpen = round2(obj.optDouble("sharpen", baseline.sharpen.toDouble()).toFloat().coerceIn(0f, 0.85f)),
                denoiseEnabled = obj.optBoolean("denoiseEnabled", baseline.denoiseEnabled),
                volumeGain = round2(obj.optDouble("volumeGain", baseline.volumeGain.toDouble()).toFloat().coerceIn(0.5f, 1.9f)),
                loudnessNormalize = obj.optBoolean("loudnessNormalize", baseline.loudnessNormalize),
                voiceClarityEq = obj.optBoolean("voiceClarityEq", baseline.voiceClarityEq),
                recommendedAspect = aspect,
                recommendedSplitSec = obj.optInt("recommendedSplitSec", baseline.recommendedSplitSec).coerceIn(5, 120),
                recommendSilenceCut = obj.optBoolean("recommendSilenceCut", baseline.recommendSilenceCut),
            )
        }.getOrNull()
    }

    private fun buildSuggestedHighlights(durationMs: Long, waveformPeaks: List<Float>): List<SuggestedHighlight> {
        if (durationMs < 4000L) {
            return listOf(SuggestedHighlight("Peak Moment", 0L, durationMs))
        }
        val windowMs = minOf(6000L, durationMs / 3L).coerceAtLeast(2500L)
        if (waveformPeaks.size < 8) {
            return listOf(
                SuggestedHighlight("Opening Hook", 0L, windowMs),
                SuggestedHighlight("Mid Highlight", (durationMs / 2L - windowMs / 2L).coerceAtLeast(0L), (durationMs / 2L + windowMs / 2L).coerceAtMost(durationMs)),
            )
        }

        val barMs = durationMs.toDouble() / waveformPeaks.size
        val windowBars = (windowMs / barMs).roundToInt().coerceIn(2, waveformPeaks.size / 2)
        val scored = mutableListOf<Pair<Int, Float>>()
        for (start in 0..(waveformPeaks.size - windowBars)) {
            var sum = 0f
            for (k in start until start + windowBars) sum += waveformPeaks[k]
            scored.add(start to (sum / windowBars))
        }
        scored.sortByDescending { it.second }

        val picked = mutableListOf<SuggestedHighlight>()
        val labels = listOf("Hook Peak", "Core Highlight", "Closing Beat")
        for ((barStart, _) in scored) {
            val sMs = (barStart * barMs).toLong()
            val eMs = (sMs + windowMs).coerceAtMost(durationMs)
            val overlaps = picked.any { !(eMs <= it.startMs || sMs >= it.endMs) }
            if (!overlaps) {
                picked.add(SuggestedHighlight(labels[picked.size], sMs, eMs))
                if (picked.size >= 3) break
            }
        }
        return picked.sortedBy { it.startMs }
    }

    private fun round2(v: Float): Float = (v * 100f).roundToInt() / 100f
}
