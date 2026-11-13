package dev.restudio.spring.boot.run

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.application.AbstractApplicationConfigurationProducer
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBTextField
import dev.restudio.spring.SpringBundle
import dev.restudio.spring.SpringIcons
import dev.restudio.spring.model.SpringAnnotations
import dev.restudio.spring.model.SpringNames
import org.jdom.Element
import java.awt.BorderLayout

class SpringBootConfigurationType : ConfigurationTypeBase(
  ID, SpringBundle.message("run.type.name"), SpringBundle.message("run.type.description"), SpringIcons.Bean,
) {
  init {
    addFactory(SpringBootConfigurationFactory(this))
  }

  companion object {
    const val ID = "RestudioSpringBoot"

    fun instance(): SpringBootConfigurationType = ConfigurationTypeUtil.findConfigurationType(SpringBootConfigurationType::class.java)
  }
}

class SpringBootConfigurationFactory(type: SpringBootConfigurationType) : ConfigurationFactory(type) {
  override fun getId(): String = "Spring Boot"

  override fun createTemplateConfiguration(project: Project): RunConfiguration = SpringBootRunConfiguration("", project, this)
}

/** A Java application run configuration with the Spring profiles to activate. */
class SpringBootRunConfiguration(name: String, project: Project, factory: ConfigurationFactory) :
  ApplicationConfiguration(name, project, factory) {
  /** A comma-separated list of profiles, for example `dev,local`. */
  var activeProfiles: String = ""

  override fun readExternal(element: Element) {
    super.readExternal(element)
    activeProfiles = JDOMExternalizerUtil.readField(element, ACTIVE_PROFILES).orEmpty()
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    if (activeProfiles.isNotBlank()) JDOMExternalizerUtil.writeField(element, ACTIVE_PROFILES, activeProfiles)
  }

  private companion object {
    const val ACTIVE_PROFILES = "ACTIVE_PROFILES"
  }
}

/** Adds the "Active profiles" field to the editor and gives the profiles to the JVM as `-Dspring.profiles.active`. */
class SpringBootRunConfigurationExtension : RunConfigurationExtension() {
  override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration is SpringBootRunConfiguration

  override fun <T : RunConfigurationBase<*>> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
    val profiles = (configuration as? SpringBootRunConfiguration)?.activeProfiles?.trim().orEmpty()
    if (profiles.isNotEmpty() && params.vmParametersList.getPropertyValue(PROFILES_PROPERTY) == null) {
      params.vmParametersList.addProperty(PROFILES_PROPERTY, profiles)
    }
  }

  override fun <P : RunConfigurationBase<*>> createFragments(configuration: P): List<SettingsEditor<P>> {
    if (configuration !is SpringBootRunConfiguration) return emptyList()
    val field = JBTextField()
    val component = LabeledComponent.create(field, SpringBundle.message("run.profiles.label"), BorderLayout.WEST)
    val fragment = SettingsEditorFragment<P, LabeledComponent<JBTextField>>(
      "restudio.spring.profiles", SpringBundle.message("run.profiles.name"), null, component,
      { settings, editor -> editor.component.text = (settings as? SpringBootRunConfiguration)?.activeProfiles.orEmpty() },
      { settings, editor -> (settings as? SpringBootRunConfiguration)?.activeProfiles = editor.component.text.trim() },
      { true },
    )
    fragment.setHint(SpringBundle.message("run.profiles.hint"))
    return listOf(fragment)
  }

  companion object {
    const val PROFILES_PROPERTY = "spring.profiles.active"
  }
}

/** Creates a Spring Boot run configuration from a `@SpringBootApplication` class with a `main` method. */
class SpringBootRunConfigurationProducer : AbstractApplicationConfigurationProducer<SpringBootRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory = SpringBootConfigurationType.instance().configurationFactories[0]

  override fun setupConfigurationFromContext(configuration: SpringBootRunConfiguration, context: ConfigurationContext,
                                             sourceElement: Ref<PsiElement>): Boolean {
    if (!super.setupConfigurationFromContext(configuration, context, sourceElement)) return false
    return configuration.mainClass?.let(::isSpringBootApplication) == true
  }

  override fun isConfigurationFromContext(configuration: SpringBootRunConfiguration, context: ConfigurationContext): Boolean =
    super.isConfigurationFromContext(configuration, context) && configuration.mainClass?.let(::isSpringBootApplication) == true

  override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
    other.configuration is ApplicationConfiguration && other.configuration !is SpringBootRunConfiguration

  override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
    other.configuration is ApplicationConfiguration && other.configuration !is SpringBootRunConfiguration

  companion object {
    fun isSpringBootApplication(psiClass: PsiClass): Boolean =
      SpringAnnotations.hasMeta(psiClass, SpringNames.SPRING_BOOT_APPLICATION) || psiClass.hasAnnotation(SpringNames.SPRING_BOOT_APPLICATION)
  }
}
