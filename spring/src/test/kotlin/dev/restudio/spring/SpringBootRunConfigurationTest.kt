package dev.restudio.spring

import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.JavaParameters
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.execution.Location
import com.intellij.psi.PsiClass
import dev.restudio.spring.boot.run.SpringBootRunConfiguration
import dev.restudio.spring.boot.run.SpringBootRunConfigurationExtension
import org.jdom.Element

class SpringBootRunConfigurationTest : SpringTestCase() {
  private fun context(psiClass: PsiClass): ConfigurationContext {
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(PlatformCoreDataKeys.MODULE, module)
      .add(Location.DATA_KEY, PsiLocation(psiClass))
      .build()
    return ConfigurationContext.getFromContext(dataContext, "")
  }

  fun `test the producer creates a Spring Boot configuration for the application class`() {
    addSpringBootApplication()
    val app = myFixture.findClass("com.example.DemoApplication")
    val configurations = context(app).configurationsFromContext.orEmpty().map { it.configuration }
    val boot = configurations.filterIsInstance<SpringBootRunConfiguration>().single()
    assertEquals("com.example.DemoApplication", boot.mainClassName)
    assertEquals("DemoApplication", boot.name)
  }

  fun `test no Spring Boot configuration for a plain main class`() {
    val plain = myFixture.addClass("package com.example; public class Tool { public static void main(String[] args) {} }")
    val configurations = context(plain).configurationsFromContext.orEmpty().map { it.configuration }
    assertEmpty(configurations.filterIsInstance<SpringBootRunConfiguration>())
  }

  fun `test active profiles are saved and given to the JVM`() {
    addSpringBootApplication()
    val app = myFixture.findClass("com.example.DemoApplication")
    val configuration = context(app).configurationsFromContext.orEmpty().map { it.configuration }
      .filterIsInstance<SpringBootRunConfiguration>().single()
    configuration.activeProfiles = "dev,local"
    val element = Element("configuration")
    configuration.writeExternal(element)
    val copy = configuration.factory!!.createTemplateConfiguration(project) as SpringBootRunConfiguration
    copy.readExternal(element)
    assertEquals("dev,local", copy.activeProfiles)

    val parameters = JavaParameters()
    SpringBootRunConfigurationExtension().updateJavaParameters(copy, parameters, null)
    assertEquals("dev,local", parameters.vmParametersList.getPropertyValue("spring.profiles.active"))
  }
}
