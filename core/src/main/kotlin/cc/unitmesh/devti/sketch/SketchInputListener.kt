package cc.unitmesh.devti.sketch

import cc.unitmesh.devti.AutoDevBundle
import cc.unitmesh.devti.devin.InsCommand
import cc.unitmesh.devti.devin.InsCommandListener
import cc.unitmesh.devti.devin.InsCommandStatus
import cc.unitmesh.devti.devin.dataprovider.BuiltinCommand
import cc.unitmesh.devti.gui.chat.ChatCodingService
import cc.unitmesh.devti.gui.chat.ui.AutoDevInputListener
import cc.unitmesh.devti.gui.chat.ui.AutoDevInputSection
import cc.unitmesh.devti.gui.chat.ui.AutoDevInputTrigger
import cc.unitmesh.devti.prompting.SimpleDevinPrompter
import cc.unitmesh.devti.provider.devins.LanguagePromptProcessor
import cc.unitmesh.devti.template.GENIUS_CODE
import cc.unitmesh.devti.template.TemplateRender
import cc.unitmesh.devti.util.AutoDevCoroutineScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch

class SketchInputListener(
    private val project: Project,
    private val chatCodingService: ChatCodingService,
    private val toolWindow: SketchToolWindow
) : AutoDevInputListener, SimpleDevinPrompter(), Disposable {
    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    override val template = templateRender.getTemplate("sketch.vm")
    override val templateRender: TemplateRender get() = TemplateRender(GENIUS_CODE)
    var systemPrompt = ""

    init {
        systemPrompt = templateRender.renderTemplate(template, SketchRunContext.create(project, null, ""))
        toolWindow.addRequestPrompt(systemPrompt)
    }

    override fun onStop(component: AutoDevInputSection) {
        chatCodingService.stop()
        toolWindow.hiddenProgressBar()
    }

    override fun onSubmit(component: AutoDevInputSection, trigger: AutoDevInputTrigger) {
        val userInput = component.text
        component.text = ""

        if (userInput.isEmpty() || userInput.isBlank()) {
            component.showTooltip(AutoDevBundle.message("chat.input.tips"))
            return
        }

        val postProcessors = LanguagePromptProcessor.instance("DevIn").firstOrNull()
        val compiledInput = postProcessors?.compile(project, userInput) ?: userInput

        toolWindow.addRequestPrompt(compiledInput)

        ApplicationManager.getApplication().executeOnPooledThread {
            val flow = chatCodingService.request(systemPrompt, compiledInput)
            val suggestion = StringBuilder()

            AutoDevCoroutineScope.scope(project).launch {
                flow.cancellable().collect { char ->
                    suggestion.append(char)

                    invokeLater {
                        toolWindow.onUpdate(suggestion.toString())
                    }
                }

                toolWindow.onFinish(suggestion.toString())
            }
        }
    }

    override fun dispose() {
        connection.disconnect()
    }
}