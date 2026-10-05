plugins {
    java
}

group = "cn.dsh.lottery"
version = "1.1.0"

java {
    // Paper 26.2 的 API 以 Java 25（class 文件版本 69）编译，
    // 因此编译工具链必须为 JDK 25；产物同样是 Java 25 字节码。
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    // 依赖全部来自本地 lib/ 快照，无需联网解析。
    flatDir { dirs("lib") }
}

dependencies {
    compileOnly(fileTree("lib") { include("*.jar") })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-deprecation,-removal")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("PaperLottery")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Implementation-Title" to "PaperLottery",
            "Implementation-Version" to project.version,
            "Built-For" to "Paper 26.2"
        )
    }
}

tasks.build {
    dependsOn(tasks.jar)
}
