// A small Spring Boot application in the shape of spring-petclinic, for manual checks of the ReStudio Spring plugin.
// The plugin tests read the jars that `./gradlew copyLibs` writes to build/runtime-libs.
plugins {
  java
}

repositories {
  mavenCentral()
}

tasks.withType<JavaCompile> {
  options.release = 21
}

dependencies {
  implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.0"))
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  compileOnly("org.projectlombok:lombok:1.18.40")
  annotationProcessor("org.projectlombok:lombok:1.18.40")
  runtimeOnly("com.h2database:h2")
}

tasks.register<Copy>("copyLibs") {
  from(configurations.runtimeClasspath, configurations.compileClasspath)
  into(layout.buildDirectory.dir("runtime-libs"))
}
