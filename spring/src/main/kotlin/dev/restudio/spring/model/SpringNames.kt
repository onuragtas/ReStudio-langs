package dev.restudio.spring.model

/** The fully qualified names of the Spring, Jakarta and javax types that the bean model reads. */
object SpringNames {
  const val COMPONENT = "org.springframework.stereotype.Component"
  const val CONFIGURATION = "org.springframework.context.annotation.Configuration"
  const val BEAN = "org.springframework.context.annotation.Bean"
  const val COMPONENT_SCAN = "org.springframework.context.annotation.ComponentScan"
  const val COMPONENT_SCANS = "org.springframework.context.annotation.ComponentScans"
  const val IMPORT = "org.springframework.context.annotation.Import"
  const val IMPORT_RESOURCE = "org.springframework.context.annotation.ImportResource"
  const val PROFILE = "org.springframework.context.annotation.Profile"
  const val PRIMARY = "org.springframework.context.annotation.Primary"
  const val FALLBACK = "org.springframework.context.annotation.Fallback"
  const val CONDITIONAL = "org.springframework.context.annotation.Conditional"
  const val IMPORT_SELECTOR = "org.springframework.context.annotation.ImportSelector"
  const val IMPORT_REGISTRAR = "org.springframework.context.annotation.ImportBeanDefinitionRegistrar"

  const val AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired"
  const val QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier"
  const val VALUE = "org.springframework.beans.factory.annotation.Value"
  const val OBJECT_PROVIDER = "org.springframework.beans.factory.ObjectProvider"
  const val OBJECT_FACTORY = "org.springframework.beans.factory.ObjectFactory"
  const val FACTORY_BEAN = "org.springframework.beans.factory.FactoryBean"

  const val SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication"
  const val ENABLE_AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.EnableAutoConfiguration"
  const val AUTO_CONFIGURATION_IMPORT_SELECTOR = "org.springframework.boot.autoconfigure.AutoConfigurationImportSelector"
  const val AUTO_CONFIGURATION_PACKAGES_REGISTRAR = "org.springframework.boot.autoconfigure.AutoConfigurationPackages.Registrar"
  const val CONDITIONAL_ON_CLASS = "org.springframework.boot.autoconfigure.condition.ConditionalOnClass"
  const val CONDITIONAL_ON_MISSING_CLASS = "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass"
  const val CONFIGURATION_PROPERTIES = "org.springframework.boot.context.properties.ConfigurationProperties"
  const val ENABLE_CONFIGURATION_PROPERTIES = "org.springframework.boot.context.properties.EnableConfigurationProperties"
  const val CONFIGURATION_PROPERTIES_SCAN = "org.springframework.boot.context.properties.ConfigurationPropertiesScan"

  const val DATA_REPOSITORY = "org.springframework.data.repository.Repository"
  const val NO_REPOSITORY_BEAN = "org.springframework.data.repository.NoRepositoryBean"

  /** The name of the file that lists the auto-configuration classes of a Spring Boot 2.7+ jar. */
  const val AUTO_CONFIGURATION_IMPORTS = "org.springframework.boot.autoconfigure.AutoConfiguration.imports"

  val INJECT = listOf("jakarta.inject.Inject", "javax.inject.Inject")
  val NAMED = listOf("jakarta.inject.Named", "javax.inject.Named")
  val RESOURCE = listOf("jakarta.annotation.Resource", "javax.annotation.Resource")
  val MANAGED_BEAN = listOf("jakarta.annotation.ManagedBean", "javax.annotation.ManagedBean")
  val PRIORITY = listOf("jakarta.annotation.Priority", "javax.annotation.Priority")
  val INJECT_QUALIFIER = listOf("jakarta.inject.Qualifier", "javax.inject.Qualifier")
  val PROVIDER = listOf("jakarta.inject.Provider", "javax.inject.Provider")

  /** The annotations that mark a field or a method as an injection point. */
  val AUTOWIRING = listOf(AUTOWIRED) + INJECT

  /** Container types that Spring fills with all matching beans. */
  val COLLECTIONS = setOf(
    "java.util.Collection", "java.util.List", "java.util.Set", "java.util.SortedSet",
    "java.util.NavigableSet", "java.lang.Iterable", "java.util.stream.Stream",
  )
  val MAPS = setOf("java.util.Map", "java.util.SortedMap", "java.util.NavigableMap")

  /** Wrapper types that get the bean lazily. Spring does not fail at startup for them. */
  val LAZY_WRAPPERS = setOf(OBJECT_PROVIDER, OBJECT_FACTORY) + PROVIDER
}
