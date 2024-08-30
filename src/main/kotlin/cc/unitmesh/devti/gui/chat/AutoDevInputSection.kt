package cc.unitmesh.devti.gui.chat

import cc.unitmesh.devti.AutoDevBundle
import cc.unitmesh.devti.AutoDevIcons
import cc.unitmesh.devti.agent.configurable.customAgentSetting
import cc.unitmesh.devti.agent.model.CustomAgentConfig
import cc.unitmesh.devti.agent.model.CustomAgentState
import cc.unitmesh.devti.llms.tokenizer.Tokenizer
import cc.unitmesh.devti.llms.tokenizer.TokenizerFactory
import cc.unitmesh.devti.settings.AutoDevSettingsState
import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.Balloon.Position
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.impl.InternalDecorator
import com.intellij.temporary.gui.block.AutoDevCoolBorder
import com.intellij.ui.HintHint
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.EventDispatcher
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min

/**
 *
 */
class AutoDevInputSection(private val project: Project, val disposable: Disposable?) : BorderLayoutPanel() {
    private val input: AutoDevInput
    private val documentListener: DocumentListener
    private val sendButtonPresentation: Presentation
    private val stopButtonPresentation: Presentation
    private val sendButton: ActionButton
    private val stopButton: ActionButton
    private val buttonPanel = JPanel(CardLayout())

    private val defaultRag: CustomAgentConfig = CustomAgentConfig("<Select Custom Agent>", "Normal")
    private var customRag: ComboBox<CustomAgentConfig> = ComboBox(MutableCollectionComboBoxModel(listOf()))

    private val logger = logger<AutoDevInputSection>()

    val editorListeners = EventDispatcher.create(AutoDevInputListener::class.java)
    private var tokenizer: Tokenizer? = try {
        lazy { TokenizerFactory.createTokenizer() }.value
    } catch (e: Exception) {
        logger.error("TokenizerImpl.INSTANCE is not available", e)
        null
    }

    var text: String
        get() {
            return input.text
        }
        set(text) {
            input.recreateDocument()
            input.text = text
        }

    init {
        val sendButtonPresentation = Presentation(AutoDevBundle.message("chat.panel.send"))
        sendButtonPresentation.setIcon(AutoDevIcons.Send)
        this.sendButtonPresentation = sendButtonPresentation

        val stopButtonPresentation = Presentation("Stop")
        stopButtonPresentation.setIcon(AutoDevIcons.Stop)
        this.stopButtonPresentation = stopButtonPresentation

        sendButton = ActionButton(
            DumbAwareAction.create {
                object : DumbAwareAction("") {
                    override fun actionPerformed(e: AnActionEvent) {
                        editorListeners.multicaster.onSubmit(this@AutoDevInputSection, AutoDevInputTrigger.Button)
                    }
                }.actionPerformed(it)
            },
            this.sendButtonPresentation,
            "",
            Dimension(20, 20)
        )

        stopButton = ActionButton(
            DumbAwareAction.create {
                object : DumbAwareAction("") {
                    override fun actionPerformed(e: AnActionEvent) {
                        editorListeners.multicaster.onStop(this@AutoDevInputSection)
                    }
                }.actionPerformed(it)
            },
            this.stopButtonPresentation,
            "",
            Dimension(20, 20)
        )

        input = AutoDevInput(project, listOf(), disposable, this)

        documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val i = input.preferredSize?.height
                if (i != input.height) {
                    revalidate()
                }
            }
        }

        input.addDocumentListener(documentListener)
        input.recreateDocument()

        input.border = JBEmptyBorder(4)

        addToCenter(input)
        val layoutPanel = BorderLayoutPanel()
        val horizontalGlue = Box.createHorizontalGlue()
        horizontalGlue.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                IdeFocusManager.getInstance(project).requestFocus(input, true)
                input.caretModel.moveToOffset(input.text.length - 1)
            }
        })
        layoutPanel.setOpaque(false)

        if (project.customAgentSetting.enableCustomRag) {
            customRag = ComboBox(MutableCollectionComboBoxModel(loadRagApps()))
            customRag.setRenderer(SimpleListCellRenderer.create { label: JBLabel, value: CustomAgentConfig?, _: Int ->
                if (value != null) {
                    label.text = value.name
                }
            })
            customRag.selectedItem = defaultRag

            layoutPanel.addToLeft(customRag)
        }


        buttonPanel.add(sendButton, "Send")
        buttonPanel.add(stopButton, "Stop")

        layoutPanel.addToCenter(horizontalGlue)
        layoutPanel.addToRight(buttonPanel)
        addToBottom(layoutPanel)

        ComponentValidator(disposable!!).withValidator(Supplier<ValidationInfo?> {
            val validationInfo: ValidationInfo? = this.getInputValidationInfo()
            sendButton.setEnabled(validationInfo == null)
            return@Supplier validationInfo
        }).installOn((this as JComponent)).revalidate()

        addListener(object : AutoDevInputListener {
            override fun editorAdded(editor: EditorEx) {
                this@AutoDevInputSection.initEditor()
            }
        })
    }

    fun showStopButton() {
        (buttonPanel.layout as? CardLayout)?.show(buttonPanel, "Stop")
        stopButton.isEnabled = true
    }

    fun showTooltip(text: @NlsContexts.Tooltip String) {
        showTooltip(input, Position.above, text)
    }

    fun showTooltip(component: JComponent, position: Position, text: @NlsContexts.Tooltip String) {
        val point = Point(component.x, component.y)
        val tipComponent = IdeTooltipManager.initPane(
            text, HintHint(component, point).setAwtTooltip(true).setPreferredPosition(position), null
        )
        val tooltip = IdeTooltip(component, point, tipComponent)
        IdeTooltipManager.getInstance().show(tooltip, true)
    }

    fun showSendButton() {
        (buttonPanel.layout as? CardLayout)?.show(buttonPanel, "Send")
        buttonPanel.isEnabled = true
    }

    private fun loadRagApps(): List<CustomAgentConfig> {
        val rags = CustomAgentConfig.loadFromProject(project)

        if (rags.isEmpty()) return listOf(defaultRag)

        return listOf(defaultRag) + rags
    }

    fun initEditor() {
        val editorEx = this.input.editor as? EditorEx ?: return

        setBorder(AutoDevCoolBorder(editorEx, this))
        UIUtil.setOpaqueRecursively(this, false)
        this.revalidate()
    }

    override fun getPreferredSize(): Dimension {
        val result = super.getPreferredSize()
        result.height = max(min(result.height, maxHeight), minimumSize.height)
        return result
    }

    fun setContent(trimMargin: String) {
        val focusManager = IdeFocusManager.getInstance(project)
        focusManager.requestFocus(input, true)
        this.input.recreateDocument()
        this.input.text = trimMargin
    }

    override fun getBackground(): Color? {
        // it seems that the input field is not ready when this method is called
        if (this.input == null) return super.getBackground()

        val editor = input.editor ?: return super.getBackground()
        return editor.colorsScheme.defaultBackground
    }

    override fun setBackground(bg: Color?) {}

    fun addListener(listener: AutoDevInputListener) {
        editorListeners.addListener(listener)
    }

    private fun getInputValidationInfo(): ValidationInfo? {
        val text = input.getDocument().text
        val textLength = (this.tokenizer)?.count(text) ?: text.length

        val exceed: Int = textLength - AutoDevSettingsState.maxTokenLength
        if (exceed <= 0) return null

        val errorMessage = AutoDevBundle.message("chat.too.long.user.message", exceed)
        return ValidationInfo(errorMessage, this as JComponent).asWarning()
    }

    fun hasSelectedAgent(): Boolean {
        if (!project.customAgentSetting.enableCustomRag) return false
        if (customRag.selectedItem == null) return false
        return customRag.selectedItem != defaultRag
    }

    fun getSelectedAgent(): CustomAgentConfig {
        return customRag.selectedItem as CustomAgentConfig
    }

    fun resetAgent() {
        (customRag.selectedItem as? CustomAgentConfig)?.let {
            it.state = CustomAgentState.START
        }

        customRag.selectedItem = defaultRag
        text = ""
    }

    fun moveCursorToStart() {
        input.caretModel.moveToOffset(0)
    }

    private val maxHeight: Int
        get() {
            val decorator = UIUtil.getParentOfType(InternalDecorator::class.java, this)
            val contentManager = decorator?.contentManager ?: return JBUI.scale(200)
            return contentManager.component.height / 2
        }

    val focusableComponent: JComponent get() = input
}
