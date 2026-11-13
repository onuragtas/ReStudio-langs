package dev.restudio.spring

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object SpringIcons {
  @JvmField val Bean: Icon = IconLoader.getIcon("/icons/springBean.svg", SpringIcons::class.java)
  @JvmField val Injection: Icon = IconLoader.getIcon("/icons/springInjection.svg", SpringIcons::class.java)
}
