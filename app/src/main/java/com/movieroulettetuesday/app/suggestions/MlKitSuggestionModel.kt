package com.movieroulettetuesday.app.suggestions

/**
 * Gemini Nano through ML Kit GenAI (Prompt API / AICore), on-device only.
 *
 * The ML Kit classes are loaded reflectively so a preview client, a missing AICore, or a
 * Robolectric classpath without the library can never crash the app: every failure is
 * [SuggestionAvailability.Unavailable]. Inference is only attempted while the caller is in the
 * foreground (the Roulette tab / `mrt://suggest` opens the app first).
 *
 * The `com.google.mlkit:genai-prompt` dependency is on the compile classpath so a supported
 * device can actually run; it is not invoked at process start.
 */
class MlKitSuggestionModel(
    private val client: Client = ReflectiveClient,
) : SuggestionModel {

    interface Client {
        suspend fun status(): SuggestionAvailability
        suspend fun generate(prompt: String): String?
    }

    override suspend fun availability(): SuggestionAvailability =
        runCatching { client.status() }.getOrDefault(SuggestionAvailability.Unavailable)

    override suspend fun generate(prompt: String): WatchSuggestion? {
        val text = runCatching { client.generate(prompt) }.getOrNull()
        return WatchSuggester.parseResponse(text)
    }

    /**
     * Reflective ML Kit Prompt API (`com.google.mlkit.genai.prompt.Generation.getClient()`).
     * FeatureStatus values: AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE.
     */
    object ReflectiveClient : Client {
        override suspend fun status(): SuggestionAvailability {
            val model = model() ?: return SuggestionAvailability.Unavailable
            val raw = model.javaClass.methods.firstOrNull { it.name == "checkStatus" && it.parameterCount == 0 }
                ?.invoke(model)?.toString()?.uppercase() ?: return SuggestionAvailability.Unavailable
            return when {
                raw.contains("AVAILABLE") && !raw.contains("UNAVAILABLE") && !raw.contains("DOWNLOAD") ->
                    SuggestionAvailability.Available
                raw.contains("DOWNLOADING") -> SuggestionAvailability.Downloading
                raw.contains("DOWNLOADABLE") -> SuggestionAvailability.Downloadable
                else -> SuggestionAvailability.Unavailable
            }
        }

        override suspend fun generate(prompt: String): String? {
            val model = model() ?: return null
            // Convenience: generateContent(String) returns a response with a `getText()` / `text`.
            val response = model.javaClass.methods.firstOrNull {
                it.name == "generateContent" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }?.invoke(model, prompt) ?: return null
            val text = response.javaClass.methods.firstOrNull { it.name == "getText" && it.parameterCount == 0 }
                ?.invoke(response)
                ?: response.javaClass.methods.firstOrNull { it.name == "text" && it.parameterCount == 0 }?.invoke(response)
            return text as? String
        }

        private fun model(): Any? = runCatching {
            val gen = Class.forName("com.google.mlkit.genai.prompt.Generation")
            val instance = gen.getField("INSTANCE").get(null) ?: gen.getMethod("getInstance").invoke(null)
            gen.getMethod("getClient").invoke(instance)
        }.getOrNull()
    }
}
