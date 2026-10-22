/*
 * tools/sound: renders every launcher sound to WAV for desktop listening, with the app's own sound code.
 *
 * Nothing is copied. The app's Android-free sources are compiled straight from app/src/main/java: the whole
 * sound/ package plus home/data/AppCategory.kt. The filter is tools/purecheck's: a file with an android.*,
 * androidx.*, com.google.android.* or generated-R import, or a `// purecheck:skip` marker, is left out. So
 * FieldSound.kt, FieldSoundEngine.kt, DataStoreMotifOverrides.kt and SystemSoundSetting.kt are not compiled here.
 *
 * Run (from the repo root):  gradle -p tools/sound run --args="--out build/wavs"
 * Options and output: see the USAGE text in src/main/kotlin/.../tools/sound/Main.kt.
 */
plugins {
    kotlin("jvm") version "2.0.20"
    application
}

val appMainRoot: File = file("../../app/src/main/java").canonicalFile
val soundRoot: File = appMainRoot.resolve("com/andrewevmiller/tvlauncher/sound")
val appCategory: File = appMainRoot.resolve("com/andrewevmiller/tvlauncher/home/data/AppCategory.kt")

val impureImport = Regex(
    """^\s*import\s+(android|androidx|com\.google\.android)\.|^\s*import\s+com\.andrewevmiller\.tvlauncher\.R(\s|$|\.)""",
    RegexOption.MULTILINE,
)
val skipMarker = "// purecheck:skip"

fun isPure(f: File): Boolean {
    val text = f.readText()
    return !impureImport.containsMatchIn(text) && skipMarker !in text
}

// Canonical paths of the app files this tool compiles: pure files from sound/ and AppCategory.kt.
val reusedAppSources: Set<String> = (
    soundRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() + appCategory
).filter { isPure(it) }.map { it.canonicalPath }.toSet()

sourceSets {
    main {
        // The whole app source root is scanned, but every app file outside the allow-list is excluded. Our own
        // sources under src/main/kotlin are outside appMainRoot and are never excluded.
        kotlin.srcDir(appMainRoot)
        kotlin.exclude { element ->
            // Files only: excluding a directory would prune its whole subtree before the allowed files are seen.
            val path = element.file.canonicalPath
            !element.isDirectory && path.startsWith(appMainRoot.path + File.separator) && path !in reusedAppSources
        }
    }
}

dependencies {
    // MotifOverrides.kt (a pure file the tool compiles) uses kotlinx.coroutines.flow. Same version as the app.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

application {
    mainClass.set("com.andrewevmiller.tvlauncher.tools.sound.MainKt")
}
