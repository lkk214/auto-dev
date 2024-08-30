package cc.unitmesh.devti.gui.toolbar

import cc.unitmesh.devti.gui.AutoDevToolWindowFactory
import cc.unitmesh.devti.gui.chat.ChatCodingPanel
import cc.unitmesh.devti.settings.LanguageChangedCallback.componentStateChanged
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComponent

class NewChatAction : DumbAwareAction(), CustomComponentAction {
    private val logger = logger<NewChatAction>()

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val button: JButton = object : JButton() {
            init {
                putClientProperty("ActionToolbar.smallVariant", true)
                putClientProperty("customButtonInsets", JBInsets(1, 1, 1, 1).asUIResource())

                setOpaque(false)
                addActionListener {
                    val dataContext: DataContext = ActionToolbar.getDataContextFor(this)
                    val project = dataContext.getData(CommonDataKeys.PROJECT)
                    if (project == null) {
                        logger.error("project is null")
                        return@addActionListener
                    }

                    val toolWindowManager = AutoDevToolWindowFactory.getToolWindow(project)
                    val contentManager = toolWindowManager?.contentManager
                    val codingPanel =
                        contentManager?.component?.components?.filterIsInstance<ChatCodingPanel>()?.firstOrNull()

                    if (codingPanel == null) {
                        AutoDevToolWindowFactory().createToolWindowContent(project, toolWindowManager!!)
                        return@addActionListener
                    }

                    // change content displayName AutoDevBundle.message("autodev.chat")
                    contentManager.contents.forEach {
                        AutoDevToolWindowFactory.setInitialDisplayName(it)
                    }

                    codingPanel.resetChatSession()
                }
            }
        }.apply {
            componentStateChanged("chat.panel.new", this) { b, d -> b.text = d }
        }

        return Wrapper(button).also {
            it.setBorder(JBUI.Borders.empty(0, 10))
        }
    }
}
