package dev.restudio.spring.graph

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.model.SpringModels
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * The content of the Spring bean graph tool window: a toolbar with the filters and a JCEF browser that draws the graph.
 * The graph data is computed in a non-blocking read action. A PSI change cancels the computation and starts it again.
 */
class BeanGraphPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private class ContextItem(val qualifiedName: String, val name: String)

  /** The result of one background computation. */
  private class Snapshot(val contexts: List<ContextItem>, val selected: ContextItem?, val data: BeanGraphData?, val reveal: List<String>)

  private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
  private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
  private var ready = false
  private val pendingScripts = mutableListOf<String>()

  private val contextBox = ComboBox<ContextItem>()
  private val packageBox = ComboBox<String>()
  private val profileBox = ComboBox<String>()
  private val libraryBox = JBCheckBox(SpringBundle.message("graph.filter.library"))
  private val searchField = SearchTextField(false)
  private var updatingControls = false

  private var contextName: String? = null
  private var data: BeanGraphData? = null
  private var shownContext: String? = null
  private var revealTarget: SmartPsiElementPointer<PsiElement>? = null

  private val updateQueue = MergingUpdateQueue("restudio.spring.graph", 500, true, this, this)

  init {
    Disposer.register(this, { browser?.let(Disposer::dispose) })
    query?.let { Disposer.register(this, it) }
    add(createToolbar(), BorderLayout.NORTH)
    if (browser == null || query == null) {
      add(JBLabel(SpringBundle.message("graph.no.jcef"), SwingConstants.CENTER), BorderLayout.CENTER)
    }
    else {
      query.addHandler { message ->
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) onMessage(message) }, ModalityState.any())
        null
      }
      browser.loadHTML(page(query))
      add(browser.component, BorderLayout.CENTER)
      project.messageBus.connect(this).subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { scheduleRefresh() })
      ApplicationManager.getApplication().messageBus.connect(this)
        .subscribe(LafManagerListener.TOPIC, LafManagerListener { execute("window.beanGraph.setTheme(${theme()})") })
      refresh()
    }
  }

  /** Shows the graph of the context of [element] and selects the beans that [element] declares. */
  fun reveal(element: PsiElement) {
    revealTarget = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
    refresh()
  }

  private fun createToolbar(): JComponent {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)))
    contextBox.renderer = textListCellRenderer { it?.name }
    contextBox.addItemListener { event ->
      if (event.stateChange == ItemEvent.SELECTED && !updatingControls) {
        contextName = (event.item as ContextItem).qualifiedName
        refresh()
      }
    }
    packageBox.renderer = textListCellRenderer { it?.ifEmpty { SpringBundle.message("graph.filter.all.packages") } }
    packageBox.addItemListener { event -> if (event.stateChange == ItemEvent.SELECTED && !updatingControls) render(keepView = false) }
    profileBox.renderer = textListCellRenderer { it?.ifEmpty { SpringBundle.message("graph.filter.all.profiles") } }
    profileBox.addItemListener { event -> if (event.stateChange == ItemEvent.SELECTED && !updatingControls) render(keepView = false) }
    libraryBox.addItemListener { if (!updatingControls) render(keepView = false) }
    searchField.textEditor.emptyText.text = SpringBundle.message("graph.search.hint")
    searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = execute("window.beanGraph.search(${js(searchField.text)})")
    })
    searchField.textEditor.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_ENTER) execute("window.beanGraph.searchNext()")
      }
    })
    contextBox.isVisible = false
    panel.add(contextBox)
    panel.add(JBLabel(SpringBundle.message("graph.filter.package")))
    panel.add(packageBox)
    panel.add(JBLabel(SpringBundle.message("graph.filter.profile")))
    panel.add(profileBox)
    panel.add(libraryBox)
    panel.add(searchField)

    val group = DefaultActionGroup(
      DumbAwareAction.create(SpringBundle.message("graph.action.refresh"), AllIcons.Actions.Refresh) { refresh() },
      DumbAwareAction.create(SpringBundle.message("graph.action.fit"), AllIcons.General.FitContent) { execute("window.beanGraph.fit()") },
      DumbAwareAction.create(SpringBundle.message("graph.action.zoom.in"), AllIcons.General.ZoomIn) { execute("window.beanGraph.zoom(1.25)") },
      DumbAwareAction.create(SpringBundle.message("graph.action.zoom.out"), AllIcons.General.ZoomOut) { execute("window.beanGraph.zoom(0.8)") },
    )
    val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("RestudioSpringBeanGraph", group, true)
    toolbar.targetComponent = this
    panel.add(toolbar.component)
    return panel
  }

  private fun scheduleRefresh() {
    updateQueue.queue(Update.create("refresh") { refresh() })
  }

  /** Computes the graph data in a background read action. */
  private fun refresh() {
    if (browser == null) return
    val wanted = contextName
    val target = revealTarget
    ReadAction.nonBlocking<Snapshot> { compute(wanted, target) }
      .inSmartMode(project)
      .expireWith(this)
      .coalesceBy(this)
      .finishOnUiThread(ModalityState.any(), ::apply)
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun compute(wanted: String?, target: SmartPsiElementPointer<PsiElement>?): Snapshot {
    val roots = SpringModels.roots(project)
    val contexts = roots.map { ContextItem(it.qualifiedName.orEmpty(), it.name.orEmpty()) }
    val element = target?.element
    val root: PsiClass? = when {
      element != null -> {
        val psiClass = element as? PsiClass ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        psiClass?.let { SpringModels.contextsOf(it).firstOrNull()?.root }
      }
      else -> null
    } ?: roots.firstOrNull { it.qualifiedName == wanted } ?: roots.firstOrNull()
    val data = root?.let { BeanGraphBuilder(SpringModels.context(it)).build() }
    val reveal = if (element == null || data == null) emptyList()
    else data.graph.nodes.filter { it.kind != BeanNodeKind.MISSING && data.targets[it.id]?.element == element }.map { it.id }
    return Snapshot(contexts, contexts.firstOrNull { it.qualifiedName == root?.qualifiedName }, data, reveal)
  }

  private fun apply(snapshot: Snapshot) {
    val sameContext = snapshot.selected?.qualifiedName == shownContext
    data = snapshot.data
    contextName = snapshot.selected?.qualifiedName
    shownContext = contextName
    val revealed = revealTarget != null
    revealTarget = null
    updatingControls = true
    try {
      contextBox.removeAllItems()
      snapshot.contexts.forEach(contextBox::addItem)
      contextBox.selectedItem = snapshot.selected
      contextBox.isVisible = snapshot.contexts.size > 1
      val graph = snapshot.data?.graph
      fill(packageBox, listOf("") + graph?.packages().orEmpty())
      fill(profileBox, listOf("") + (graph?.profiles().orEmpty() + ProfileExpression.DEFAULT).distinct())
      if (revealed && snapshot.reveal.isNotEmpty()) {
        val visible = graph?.filter(filter())?.nodes.orEmpty().map { it.id }.toSet()
        if (snapshot.reveal.none { it in visible }) {
          packageBox.selectedItem = ""
          profileBox.selectedItem = ""
          if (graph?.nodes.orEmpty().any { it.id in snapshot.reveal && it.library }) libraryBox.isSelected = true
        }
      }
    }
    finally {
      updatingControls = false
    }
    render(keepView = sameContext && !revealed)
    if (snapshot.reveal.isNotEmpty()) execute("window.beanGraph.reveal(${jsArray(snapshot.reveal)})")
  }

  private fun fill(box: ComboBox<String>, items: List<String>) {
    val selected = box.selectedItem as String?
    box.removeAllItems()
    items.forEach(box::addItem)
    box.selectedItem = if (selected != null && selected in items) selected else ""
  }

  private fun filter(): BeanGraphFilter {
    val profile = profileBox.selectedItem as String?
    return BeanGraphFilter(
      packageName = (packageBox.selectedItem as String?)?.ifEmpty { null },
      activeProfiles = profile?.ifEmpty { null }?.let { if (it == ProfileExpression.DEFAULT) emptySet() else setOf(it) },
      showLibrary = libraryBox.isSelected,
    )
  }

  private fun render(keepView: Boolean) {
    val graph = data?.graph ?: BeanGraph("", emptyList(), emptyList())
    execute("window.beanGraph.setData(${BeanGraphJson.write(graph.filter(filter()))}, $keepView)")
  }

  private fun onMessage(message: String) {
    when {
      message == "ready" -> {
        ready = true
        val scripts = pendingScripts.toList()
        pendingScripts.clear()
        scripts.forEach(::execute)
      }
      message.startsWith("navigate:") -> navigate(message.removePrefix("navigate:"))
    }
  }

  private fun navigate(id: String) {
    val pointer = data?.targets?.get(id) ?: return
    val element = runReadActionBlocking { pointer.element } as? NavigatablePsiElement ?: return
    if (element.canNavigate()) element.navigate(false)
  }

  private fun execute(script: String) {
    val browser = browser ?: return
    if (!ready) {
      // The page runs the scripts when it is ready.
      pendingScripts += script
      return
    }
    browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
  }

  private fun page(query: JBCefJSQuery): String {
    val loader = BeanGraphPanel::class.java
    fun resource(name: String) = loader.getResourceAsStream("/graph/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    val labels = LABELS.joinToString(",", "{", "}") { key ->
      "${js(key)}:${js(SpringBundle.message("graph.label.$key", "{0}", "{1}", "{2}"))}"
    }
    val bridge = "window.beanGraphLabels = $labels;\n" +
                 "window.beanGraphSend = function (message) { ${query.inject("message")} };"
    return resource("graph.html")
      .replace("/*STYLE*/", resource("graph.css"))
      .replace("/*BRIDGE*/", bridge)
      .replace("/*SCRIPT*/", resource("graph.js") + "\nwindow.beanGraph.setTheme(${theme()});")
  }

  private fun theme(): String {
    val background = UIUtil.getTreeBackground()
    val foreground = UIUtil.getLabelForeground()
    val font = UIUtil.getLabelFont()
    val colors = mapOf(
      "bg" to hex(background),
      "fg" to hex(foreground),
      "muted" to hex(NamedColorUtil.getInactiveTextColor()),
      "node-bg" to hex(ColorUtil.mix(background, foreground, 0.05)),
      "node-border" to hex(ColorUtil.mix(background, foreground, 0.28)),
      "library-bg" to hex(ColorUtil.mix(background, foreground, 0.12)),
      "edge" to hex(ColorUtil.mix(background, foreground, 0.5)),
      "accent" to hex(JBUI.CurrentTheme.Focus.focusColor()),
      "dependency" to hex(JBColor(Color(0x3574F0), Color(0x548AF7))),
      "dependent" to hex(JBColor(Color(0x208A3C), Color(0x5FB865))),
      "ambiguous" to hex(JBColor(Color(0xE08855), Color(0xE08855))),
      "unresolved" to hex(JBColor(Color(0xE55765), Color(0xF0717C))),
      "font" to "\"${font.family}\", sans-serif",
      "font-size" to "${font.size}px",
    )
    return colors.entries.joinToString(",", "{", "}") { "${js(it.key)}:${js(it.value)}" }
  }

  override fun dispose() {}

  companion object {
    private val LABELS = listOf(
      "kind.COMPONENT", "kind.CONFIGURATION", "kind.BEAN_METHOD", "kind.REPOSITORY", "kind.CONFIGURATION_PROPERTIES", "kind.MISSING",
      "status.RESOLVED", "status.AMBIGUOUS", "status.UNRESOLVED", "status.FACTORY",
      "status.count", "status.selection", "status.matches",
      "tooltip.profiles", "tooltip.primary", "tooltip.conditional", "tooltip.library", "tooltip.points",
      "legend.resolved", "legend.ambiguous", "legend.unresolved", "legend.factory", "legend.dependency", "legend.dependent",
      "empty",
    )

    private fun hex(color: Color): String = "#" + ColorUtil.toHex(color)

    private fun js(value: String): String = with(BeanGraphJson) { StringBuilder().string(value).toString() }

    private fun jsArray(values: List<String>): String = values.joinToString(",", "[", "]", transform = ::js)
  }
}
