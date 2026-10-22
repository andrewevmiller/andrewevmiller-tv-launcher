import org.gradle.api.tasks.testing.logging.TestExceptionFormat

/*
 * purecheck: compiles and unit-tests every Android-free Kotlin file under app/src with plain JVM Gradle.
 *
 * Why: the cloud environment can reach Maven Central and the Gradle plugin portal but not Google Maven,
 * so `:app` (AGP, Compose) cannot compile there. Logic written without android.* / androidx.* imports can.
 *
 * A file is "pure" when it has no import of android.*, androidx.*, com.google.android.* or the generated R,
 * and no `// purecheck:skip` marker. Pure files that reference an impure type fail to compile here,
 * which is the point: split the logic from the glue.
 *
 * Run:  ./gradlew -p tools/purecheck test      (or: gradle -p tools/purecheck test)
 * List: ./gradlew -p tools/purecheck pureReport
 */
plugins {
    kotlin("jvm") version "2.0.20"
}

val mainRoot = file("../../app/src/main/java")
val testRoot = file("../../app/src/test/java")

val impureImport = Regex(
    """^\s*import\s+(android|androidx|com\.google\.android)\.|^\s*import\s+com\.andrewevmiller\.tvlauncher\.R(\s|$|\.)""",
    RegexOption.MULTILINE,
)
val skipMarker = "// purecheck:skip"

fun ktFiles(root: File): List<File> =
    if (!root.exists()) emptyList()
    else root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

fun isPure(f: File): Boolean {
    val text = f.readText()
    return !impureImport.containsMatchIn(text) && skipMarker !in text
}

val impureMain = ktFiles(mainRoot).filterNot(::isPure).map { it.canonicalPath }.toSet()
val impureTest = ktFiles(testRoot).filterNot(::isPure).map { it.canonicalPath }.toSet()

sourceSets {
    main {
        kotlin.srcDir(mainRoot)
        kotlin.exclude { it.file.canonicalPath in impureMain }
    }
    test {
        kotlin.srcDir(testRoot)
        kotlin.exclude { it.file.canonicalPath in impureTest }
    }
}

dependencies {
    // Same coroutines version as the app (gradle/libs.versions.toml). Tests use runBlocking, not coroutines-test,
    // because :app does not depend on coroutines-test.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null) {
                println("purecheck: ${result.testCount} tests, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
            }
        }),
    )
}

tasks.register("pureReport") {
    group = "verification"
    description = "Lists which app sources purecheck compiles and which it skips as Android-dependent."
    doLast {
        fun rel(f: File) = f.relativeTo(file("../../app/src")).path
        println("-- main, compiled")
        ktFiles(mainRoot).filter { it.canonicalPath !in impureMain }.forEach { println("   ${rel(it)}") }
        println("-- main, skipped (Android imports or purecheck:skip)")
        ktFiles(mainRoot).filter { it.canonicalPath in impureMain }.forEach { println("   ${rel(it)}") }
        println("-- test, compiled")
        ktFiles(testRoot).filter { it.canonicalPath !in impureTest }.forEach { println("   ${rel(it)}") }
        println("-- test, skipped")
        ktFiles(testRoot).filter { it.canonicalPath in impureTest }.forEach { println("   ${rel(it)}") }
    }
}
