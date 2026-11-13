package dev.restudio.spring.graph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.restudio.spring.inject.InjectionIndex
import dev.restudio.spring.inject.InjectionKind
import dev.restudio.spring.inject.InjectionPoint
import dev.restudio.spring.inject.InjectionPoints
import dev.restudio.spring.inject.InjectionProblem
import dev.restudio.spring.inject.InjectionResolver
import dev.restudio.spring.model.BeanKind
import dev.restudio.spring.model.SpringAnnotations
import dev.restudio.spring.model.SpringBean
import dev.restudio.spring.model.SpringContext
import dev.restudio.spring.model.SpringNames

/** A graph with the PSI elements of its nodes and edges, for navigation. */
class BeanGraphData(
  val graph: BeanGraph,
  /** The declaration of each node, and the first injection point of each edge. */
  val targets: Map<String, SmartPsiElementPointer<PsiElement>>,
)

/**
 * Builds the dependency graph of a [SpringContext] from the bean model and the injection resolver.
 * The edge status uses the results of the autowiring inspection. Call it in a read action.
 */
class BeanGraphBuilder(private val context: SpringContext) {
  private val project = context.root.project
  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val pointers = SmartPointerManager.getInstance(project)

  private val nodes = LinkedHashMap<String, BeanNode>()
  private val idOfBean = HashMap<SpringBean, String>()
  private val targets = HashMap<String, SmartPsiElementPointer<PsiElement>>()
  private val edges = LinkedHashMap<Triple<String, String, EdgeKind>, EdgeBuilder>()

  private class EdgeBuilder(val from: String, val to: String, val kind: EdgeKind) {
    var status = EdgeStatus.RESOLVED
    val points = mutableListOf<String>()
    var target: PsiElement? = null
  }

  fun build(): BeanGraphData {
    for (bean in context.beans) {
      ProgressManager.checkCanceled()
      addNode(bean)
    }
    for (bean in context.beans) {
      ProgressManager.checkCanceled()
      val method = bean.element as? PsiMethod ?: continue
      if (method.hasModifierProperty(PsiModifier.STATIC)) continue
      val owner = method.containingClass ?: continue
      for (configuration in context.beansOf(owner)) {
        edge(idOfBean.getValue(bean), idOfBean[configuration] ?: continue, EdgeKind.FACTORY, EdgeStatus.RESOLVED, null, null)
      }
    }
    for (psiClass in classes()) {
      for (point in InjectionIndex.pointsOf(psiClass)) {
        ProgressManager.checkCanceled()
        addPoint(point)
      }
    }
    val edgeList = edges.values.mapIndexed { index, edge ->
      val id = "e$index"
      edge.target?.let { targets[id] = pointers.createSmartPsiElementPointer(it) }
      BeanEdge(id, edge.from, edge.to, edge.kind, edge.status, edge.points.distinct())
    }
    val graph = BeanGraph(context.root.name ?: context.root.qualifiedName.orEmpty(), nodes.values.toList(), edgeList)
    return BeanGraphData(graph, targets)
  }

  private fun classes(): List<PsiClass> =
    (context.beans.mapNotNull { it.element as? PsiClass ?: (it.element as? PsiMethod)?.containingClass } + context.configurations)
      .distinct()

  private fun addNode(bean: SpringBean) {
    var id = bean.name
    var index = 1
    while (id in nodes) id = "${bean.name}#${index++}"
    val element = bean.element
    val psiClass = bean.beanClass
    val method = element as? PsiMethod
    val profiles = bean.profiles.toMutableList()
    if (method != null && psiClass != null) {
      SpringAnnotations.findAllMeta(psiClass, SpringNames.PROFILE).flatMapTo(profiles) { SpringAnnotations.strings(it, "value").orEmpty() }
    }
    val kind = when (bean.kind) {
      BeanKind.BEAN_METHOD -> BeanNodeKind.BEAN_METHOD
      BeanKind.REPOSITORY -> BeanNodeKind.REPOSITORY
      BeanKind.CONFIGURATION_PROPERTIES -> BeanNodeKind.CONFIGURATION_PROPERTIES
      BeanKind.COMPONENT ->
        when {
          psiClass != null && SpringAnnotations.hasMeta(psiClass, SpringNames.CONFIGURATION) -> BeanNodeKind.CONFIGURATION
          psiClass != null && SpringAnnotations.hasMeta(psiClass, REPOSITORY_STEREOTYPE) -> BeanNodeKind.REPOSITORY
          else -> BeanNodeKind.COMPONENT
        }
    }
    val file = element.containingFile
    val virtualFile = file?.virtualFile
    nodes[id] = BeanNode(
      id = id,
      name = bean.name,
      type = bean.type.presentableText,
      qualifiedType = bean.type.canonicalText,
      packageName = (file as? PsiJavaFile)?.packageName ?: psiClass?.qualifiedName?.substringBeforeLast('.', "").orEmpty(),
      kind = kind,
      profiles = profiles.distinct(),
      primary = bean.primary,
      conditional = bean.conditional,
      library = bean.autoConfiguration || virtualFile == null || !fileIndex.isInSourceContent(virtualFile),
      file = file?.name,
    )
    idOfBean[bean] = id
    targets[id] = pointers.createSmartPsiElementPointer(element)
  }

  private fun addPoint(point: InjectionPoint) {
    val ownerElement: PsiElement? = when (point.kind) {
      InjectionKind.BEAN_METHOD -> PsiTreeUtil.getParentOfType(point.owner, PsiMethod::class.java, false)
      else -> PsiTreeUtil.getParentOfType(point.owner, PsiClass::class.java, false)
    }
    val owners = ownerElement?.let(context::beansOf).orEmpty().mapNotNull(idOfBean::get)
    if (owners.isEmpty()) return
    val result = InjectionResolver.resolve(point, context) ?: return
    // Show a problem only when the autowiring inspection reports it: in a project source file, and when all the contexts agree.
    val problem = result.problem?.takeIf {
      val file = point.anchor.containingFile?.virtualFile
      point.anchor.isPhysical && file != null && fileIndex.isInSourceContent(file) &&
      InjectionResolver.commonProblem(InjectionResolver.resolve(point)) != null
    }
    val target = InjectionPoints.target(point)
    for (from in owners) {
      when (problem) {
        is InjectionProblem.NoBeans -> {
          val missing = missingNode("missing:${problem.type.canonicalText}", problem.type.presentableText,
                                    problem.type.canonicalText, target)
          edge(from, missing, EdgeKind.INJECTION, EdgeStatus.UNRESOLVED, point.name, target)
        }
        is InjectionProblem.UnknownQualifier -> {
          val label = "@${problem.annotation.nameReferenceElement?.referenceName ?: "Qualifier"}(\"${problem.value}\")"
          val missing = missingNode("missing:@${problem.annotation.qualifiedName}:${problem.value}", label,
                                    point.type.canonicalText, target)
          edge(from, missing, EdgeKind.INJECTION, EdgeStatus.UNRESOLVED, point.name, target)
        }
        is InjectionProblem.MultipleBeans -> for (bean in problem.beans) {
          edge(from, idOfBean[bean] ?: continue, EdgeKind.INJECTION, EdgeStatus.AMBIGUOUS, point.name, target)
        }
        null -> for (bean in result.resolved) {
          edge(from, idOfBean[bean] ?: continue, EdgeKind.INJECTION, EdgeStatus.RESOLVED, point.name, target)
        }
      }
    }
  }

  private fun missingNode(id: String, name: String, type: String, target: PsiElement): String {
    if (id !in nodes) {
      nodes[id] = BeanNode(id, name, type.substringAfterLast('.'), type, "", BeanNodeKind.MISSING, emptyList(),
                           primary = false, conditional = false, library = false, file = null)
      targets[id] = pointers.createSmartPsiElementPointer(target)
    }
    return id
  }

  private fun edge(from: String, to: String, kind: EdgeKind, status: EdgeStatus, point: String?, target: PsiElement?) {
    if (from == to) return
    val edge = edges.getOrPut(Triple(from, to, kind)) { EdgeBuilder(from, to, kind) }
    if (status.ordinal > edge.status.ordinal) edge.status = status
    if (point != null) edge.points += point
    if (edge.target == null) edge.target = target
  }
}

private const val REPOSITORY_STEREOTYPE = "org.springframework.stereotype.Repository"
