package cc.unitmesh.devti.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.Converter
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.OptionTag
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.APP)
@State(name = "cc.unitmesh.devti.settings.DevtiSettingsState", storages = [Storage("DevtiSettings.xml")])
class AutoDevSettingsState : PersistentStateComponent<AutoDevSettingsState> {
    var gitType = DEFAULT_GIT_TYPE
    var githubToken = ""
    var gitlabToken = ""
    var gitlabUrl = ""
    var openAiKey = ""
    var openAiModel = DEFAULT_AI_MODEL
    var delaySeconds = ""

    var aiEngine = DEFAULT_AI_ENGINE
    var customOpenAiHost = ""
    var customEngineServer = ""
    var customEngineToken = ""
    var customPrompts = ""
    var customModel = ""

    var customEngineResponseType = ResponseType.SSE.name
    /**
     * should be a json path
     */
    var customEngineResponseFormat = ""
    /**
     * should be a json
     * {
     *     'customHeaders': { 'headerName': 'headerValue', 'headerName2': 'headerValue2' ... },
     *     'customFields' : { 'bodyFieldName': 'bodyFieldValue', 'bodyFieldName2': 'bodyFieldValue2' ... }
     *     'messageKey': {'role': 'roleKeyName', 'content': 'contentKeyName'}
     * }
     *
     * @see docs/custom-llm-server.md
     */
    var customEngineRequestFormat = ""

    @OptionTag(value = "lastCheckTime", converter = ZonedDateTimeConverter::class)
    var lastCheck: ZonedDateTime? = null


    var language = DEFAULT_HUMAN_LANGUAGE
    var maxTokenLength = MAX_TOKEN_LENGTH.toString()

    fun fetchMaxTokenLength(): Int = maxTokenLength.toIntOrNull() ?: MAX_TOKEN_LENGTH

    fun fetchLocalLanguage(display: String = language) : String {
        return HUMAN_LANGUAGES.getAbbrByDispay(display)
    }

    @Synchronized
    override fun getState(): AutoDevSettingsState = this

    @Synchronized
    override fun loadState(state: AutoDevSettingsState) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        val maxTokenLength: Int get() = getInstance().fetchMaxTokenLength()
        val language: String get() = getInstance().fetchLocalLanguage()

        fun getInstance(): AutoDevSettingsState {
            return ApplicationManager.getApplication().getService(AutoDevSettingsState::class.java).state
        }
    }
}

class ZonedDateTimeConverter : Converter<ZonedDateTime>() {
    override fun toString(value: ZonedDateTime): String? = value.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)

    override fun fromString(value: String): ZonedDateTime? {
        return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME)
    }
}
