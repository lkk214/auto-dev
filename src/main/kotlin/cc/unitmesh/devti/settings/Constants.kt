package cc.unitmesh.devti.settings

val OPENAI_MODEL = arrayOf("gpt-3.5-turbo", "gpt-3.5-turbo-16k", "gpt-4", "custom")
val AI_ENGINES = arrayOf("OpenAI", "Custom", "Azure")

enum class AIEngines {
    OpenAI, Custom, Azure
}

val GIT_TYPE = arrayOf("Github" , "Gitlab")
val DEFAULT_GIT_TYPE = GIT_TYPE[0]

enum class ResponseType {
    SSE, JSON;
}


val DEFAULT_AI_ENGINE = AI_ENGINES[0]

val DEFAULT_AI_MODEL = OPENAI_MODEL[0]

@Suppress("unused")
enum class HUMAN_LANGUAGES(val abbr: String, val display: String) {
    ENGLISH("en", "English"),
    CHINESE("zh", "中文");

    companion object {
        private val map: Map<String, HUMAN_LANGUAGES> = HUMAN_LANGUAGES.values().map { it.display to it }.toMap()

        fun getAbbrByDispay(display: String): String {
            return map.getOrDefault(display, ENGLISH).abbr
        }
    }
}
val DEFAULT_HUMAN_LANGUAGE = HUMAN_LANGUAGES.ENGLISH.display
val MAX_TOKEN_LENGTH = 4000
val SELECT_CUSTOM_MODEL = "custom"
