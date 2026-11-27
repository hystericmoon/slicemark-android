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
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class InvalidApiKeyException(message: String) : Exception(message)

data class ModelPresetOption(
    val id: String,
    val label: String,
    val shortLabel: String,
)

object SmartAutoTuneEngine {

    val openRouterPresetOptions = listOf(
        ModelPresetOption("auto", "Auto (Best Free -> Fallback)", "Auto (Best -> Fallback)"),
        ModelPresetOption("openrouter/free", "OpenRouter Free Auto · Free", "OpenRouter Free"),
        ModelPresetOption("google/gemini-2.5-flash", "Gemini 2.5 Flash · Fast", "Gemini 2.5 Flash"),
        ModelPresetOption("anthropic/claude-sonnet-4", "Claude Sonnet 4 · Best quality", "Claude Sonnet 4"),
        ModelPresetOption("openai/gpt-4o-mini", "GPT-4o mini · Balanced", "GPT-4o mini"),
    )

    val geminiPresetOptions = listOf(
        ModelPresetOption("auto", "Auto (Best -> Fast fallback)", "Auto (Best -> Fallback)"),
        ModelPresetOption("gemini-2.5-pro", "Gemini 2.5 Pro · Best quality", "Gemini 2.5 Pro"),
        ModelPresetOption("gemini-2.5-flash", "Gemini 2.5 Flash · Free & fast", "Gemini 2.5 Flash"),
        ModelPresetOption("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite · Fastest", "Gemini 2.5 Flash-Lite"),
    )

    val openRouterPresets = openRouterPresetOptions.map { it.id }
    val geminiPresets = geminiPresetOptions.map { it.id }

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
                primaryModel = selectedModel.ifBlank { "auto" },
                goal = goal,
                customDirection = customDirection,
                telemetry = telemetry,
                source = source,
                localFallback = localBaseline,
            )
            AutoTuneEngineType.GEMINI_BYOK -> runGeminiWithFallback(
                apiKey = apiKey,
                primaryModel = selectedModel.ifBlank { "auto" },
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

    fun getOpenRouterFallbackChain(selectedModel: String): List<String> {
        val trimmed = selectedModel.trim()
        if (trimmed.isEmpty() || trimmed.equals("auto", ignoreCase = true)) {
            return listOf(
                "openrouter/free",
                "google/gemma-3-27b-it:free",
                "qwen/qwen2.5-vl-72b-instruct:free",
            )
        }
        val clean = sanitizeModelId(trimmed) ?: return emptyList()
        return listOf(clean, "openrouter/free").distinct()
    }

    fun getGeminiFallbackChain(selectedModel: String): List<String> {
        val trimmed = selectedModel.trim()
        if (trimmed.isEmpty() || trimmed.equals("auto", ignoreCase = true)) {
            return listOf(
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
            )
        }
        val clean = sanitizeModelId(trimmed.removePrefix("models/")) ?: return emptyList()
        return listOf(clean, "gemini-2.5-flash", "gemini-2.5-flash-lite").distinct()
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
        val chain = getOpenRouterFallbackChain(primaryModel).ifEmpty {
            return localFallback.copy(
                fellBackToLocal = true,
                fallbackReason = "Invalid OpenRouter model ID — applied Local offline Auto-Tune.",
            )
        }
        val prompt = buildTelemetryPrompt(goal, customDirection, telemetry, source)
        var lastTransientError = "OpenRouter models busy"

        for ((index, modelId) in chain.withIndex()) {
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
                    val resolvedModel = root.optString("model").trim().ifBlank { modelId }
                    val displayModel = if (index > 0) "$resolvedModel (Fallback)" else resolvedModel
                    val parsed = parseCloudJsonResult(
                        rawText = content,
                        engine = AutoTuneEngineType.OPENROUTER_BYOK,
                        modelId = displayModel,
                        baseline = localFallback,
                        durationMs = source.durationMs,
                    )
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
        val chain = getGeminiFallbackChain(primaryModel).ifEmpty {
            return localFallback.copy(
                fellBackToLocal = true,
                fallbackReason = "Invalid Gemini model ID — applied Local offline Auto-Tune.",
            )
        }
        val prompt = buildTelemetryPrompt(goal, customDirection, telemetry, source)
        var lastTransientError = "Gemini models busy"

        for ((index, cleanModel) in chain.withIndex()) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"
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
                .addHeader("x-goog-api-key", apiKey)
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
                    val displayModel = if (index > 0) "$cleanModel (Fallback)" else cleanModel
                    val parsed = parseCloudJsonResult(
                        rawText = text,
                        engine = AutoTuneEngineType.GEMINI_BYOK,
                        modelId = displayModel,
                        baseline = localFallback,
                        durationMs = source.durationMs,
                    )
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
    ): String {
        val durSec = String.format(Locale.US, "%.1f", source.durationMs / 1000.0)
        return """
            Analyze this video telemetry and return ONLY a single JSON object with keys:
            lookTitle (string), summary (string, max 160 chars),
            brightness (float -0.25..0.25), contrast (float 0.8..1.4), saturation (float 0.7..1.5),
            sharpen (float 0..0.7), denoiseEnabled (boolean),
            volumeGain (float 0.7..1.7), loudnessNormalize (boolean), voiceClarityEq (boolean),
            recommendedAspect (one of ORIGINAL, PORTRAIT_9_16, SQUARE_1_1, SOCIAL_4_5, LANDSCAPE_16_9),
            recommendedSplitSec (int 15, 30, 45, or 60), recommendSilenceCut (boolean),
            suggestedHighlights (array of up to 3 objects: { "label": string, "startSec": float, "endSec": float } within 0..$durSec).
            Style goal: ${goal.label} (${goal.hint}).
            Custom user direction: ${customDirection.ifBlank { "None" }}.
            Video: ${source.effectiveWidth}x${source.effectiveHeight}, duration ${durSec}s.
            Telemetry (${t.sampledFrames} keyframes): meanLuma=${round2(t.meanLuma)}, p05=${round2(t.p05Luma)}, p95=${round2(t.p95Luma)},
            contrastSpread=${round2(t.contrastSpread)}, saturation=${round2(t.meanSaturation)},
            edgeSharpness=${round2(t.edgeSharpness)}, shadowNoise=${round2(t.shadowNoise)},
            audioRmsDb=${t.audioRmsDb.roundToInt()}, quietRatio=${round2(t.quietRatio)}.
        """.trimIndent()
    }

    internal fun parseCloudJsonResult(
        rawText: String,
        engine: AutoTuneEngineType,
        modelId: String,
        baseline: AutoTuneResult,
        durationMs: Long = Long.MAX_VALUE,
    ): AutoTuneResult? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = JSONObject(rawText.substring(start, end + 1))
            val colorObj = obj.optJSONObject("color")
            val audioObj = obj.optJSONObject("audio")
            val framingObj = obj.optJSONObject("framing")
            val pacingObj = obj.optJSONObject("pacing")

            val rawAspect = framingObj?.optString("recommendedAspect")
                ?.takeIf { it.isNotBlank() }
                ?: obj.optString("recommendedAspect", baseline.recommendedAspect.name)
            val aspect = parseAspectPreset(rawAspect, baseline.recommendedAspect)

            val rawTitle = obj.optString("lookTitle")
                .ifBlank { obj.optString("visualStyleName") }
                .ifBlank { baseline.lookTitle }
                .take(48)

            val brightnessVal = colorObj?.optDouble("brightness", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: obj.optDouble("brightness", baseline.brightness.toDouble())
            val contrastVal = colorObj?.optDouble("contrast", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: obj.optDouble("contrast", baseline.contrast.toDouble())
            val saturationVal = colorObj?.optDouble("saturation", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: obj.optDouble("saturation", baseline.saturation.toDouble())
            val sharpenVal = colorObj?.optDouble("sharpen", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: obj.optDouble("sharpen", baseline.sharpen.toDouble())
            val denoiseVal = when {
                colorObj?.has("denoise") == true -> colorObj.optBoolean("denoise", baseline.denoiseEnabled)
                else -> obj.optBoolean("denoiseEnabled", baseline.denoiseEnabled)
            }

            val volumeVal = audioObj?.optDouble("volume", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: obj.optDouble("volumeGain", baseline.volumeGain.toDouble())
            val normalizeVal = when {
                audioObj?.has("normalize") == true -> audioObj.optBoolean("normalize", baseline.loudnessNormalize)
                else -> obj.optBoolean("loudnessNormalize", baseline.loudnessNormalize)
            }
            val voiceEqVal = when {
                audioObj?.has("voiceEnhance") == true -> audioObj.optBoolean("voiceEnhance", baseline.voiceClarityEq)
                else -> obj.optBoolean("voiceClarityEq", baseline.voiceClarityEq)
            }

            val splitSec = pacingObj?.optInt("recommendedIntervalSeconds", -1)
                ?.takeIf { it > 0 }
                ?: obj.optInt("recommendedSplitSec", baseline.recommendedSplitSec)
            val silenceCut = when {
                pacingObj?.has("suggestRemoveSilence") == true ->
                    pacingObj.optBoolean("suggestRemoveSilence", baseline.recommendSilenceCut)
                else -> obj.optBoolean("recommendSilenceCut", baseline.recommendSilenceCut)
            }

            val highlightsArr = obj.optJSONArray("suggestedHighlights")
                ?: obj.optJSONArray("suggestedClips")
            val parsedHighlights = parseSuggestedHighlights(highlightsArr, durationMs)

            baseline.copy(
                engineUsed = engine,
                modelUsed = modelId,
                fellBackToLocal = false,
                fallbackReason = null,
                lookTitle = rawTitle,
                summary = obj.optString("summary", baseline.summary).ifBlank { baseline.summary }.take(220),
                brightness = round2(brightnessVal.toFloat().coerceIn(-0.35f, 0.35f)),
                contrast = round2(contrastVal.toFloat().coerceIn(0.6f, 1.5f)),
                saturation = round2(saturationVal.toFloat().coerceIn(0.4f, 1.6f)),
                sharpen = round2(sharpenVal.toFloat().coerceIn(0f, 0.85f)),
                denoiseEnabled = denoiseVal,
                volumeGain = round2(volumeVal.toFloat().coerceIn(0.5f, 1.9f)),
                loudnessNormalize = normalizeVal,
                voiceClarityEq = voiceEqVal,
                recommendedAspect = aspect,
                recommendedSplitSec = splitSec.coerceIn(5, 120),
                recommendSilenceCut = silenceCut,
                suggestedHighlights = if (parsedHighlights.isNotEmpty()) parsedHighlights else baseline.suggestedHighlights,
            )
        }.getOrNull()
    }

    private fun parseAspectPreset(raw: String, fallback: AspectPreset): AspectPreset {
        return when (raw.trim().lowercase(Locale.US)) {
            "original" -> AspectPreset.ORIGINAL
            "9:16", "portrait_9_16" -> AspectPreset.PORTRAIT_9_16
            "1:1", "square_1_1" -> AspectPreset.SQUARE_1_1
            "4:5", "social_4_5" -> AspectPreset.SOCIAL_4_5
            "16:9", "landscape_16_9" -> AspectPreset.LANDSCAPE_16_9
            else -> runCatching { AspectPreset.valueOf(raw.trim()) }.getOrDefault(fallback)
        }
    }

    private fun parseSuggestedHighlights(arr: JSONArray?, durationMs: Long): List<SuggestedHighlight> {
        if (arr == null || arr.length() == 0 || durationMs < 500L) return emptyList()
        val out = mutableListOf<SuggestedHighlight>()
        val maxItems = minOf(4, arr.length())
        for (i in 0 until maxItems) {
            val item = arr.optJSONObject(i) ?: continue
            val rawStartMs = when {
                item.has("startMs") -> item.optLong("startMs", -1L)
                item.has("startSec") -> (item.optDouble("startSec", Double.NaN) * 1000.0).toLong()
                item.has("startTime") -> (item.optDouble("startTime", Double.NaN) * 1000.0).toLong()
                else -> -1L
            }
            val rawEndMs = when {
                item.has("endMs") -> item.optLong("endMs", -1L)
                item.has("endSec") -> (item.optDouble("endSec", Double.NaN) * 1000.0).toLong()
                item.has("endTime") -> (item.optDouble("endTime", Double.NaN) * 1000.0).toLong()
                else -> -1L
            }
            if (rawStartMs < 0L || rawEndMs <= rawStartMs) continue
            val clampedStart = rawStartMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
            val clampedEnd = rawEndMs.coerceIn(clampedStart, durationMs)
            if (clampedEnd - clampedStart < 500L) continue
            val label = item.optString("label").trim().ifBlank { "Highlight ${i + 1}" }.take(40)
            out.add(SuggestedHighlight(label = label, startMs = clampedStart, endMs = clampedEnd))
        }
        return out.sortedBy { it.startMs }
    }

    fun sanitizeModelId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 96) return null
        if (trimmed.contains("..") || !Regex("^[a-zA-Z0-9._:/-]+$").matches(trimmed)) return null
        return trimmed
    }

    private fun buildSuggestedHighlights(durationMs: Long, waveformPeaks: List<Float>): List<SuggestedHighlight> {
        if (durationMs < 4000L || waveformPeaks.size < 8) {
            return emptyList()
        }
        val windowMs = minOf(6000L, durationMs / 3L).coerceAtLeast(2500L)

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
