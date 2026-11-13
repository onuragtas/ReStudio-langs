# Spring sample

A small Spring Boot application in the shape of spring-petclinic, for manual checks of the ReStudio Spring plugin (`spring/`).

- `org.restudio.petclinic`: a correct application. The plugin must show no autowiring problem in it.
- `org.restudio.cases`: an application with known problems. The comments in `AutowiringCases.java` give the result that
  IntelliJ IDEA Ultimate shows.

Open it in the fork build with `tools/open-ide.sh spring`. To run the plugin integration test with the real Spring jars:

```
cd testdata/spring && ../../gradlew copyLibs && cd ../..
./gradlew :spring:test --tests '*PetclinicIntegrationTest*'
```
