package dev.jonalakas.bridgepad.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyRulesTest {
    @Test
    fun outputAdaptersDoNotImportConcreteInputs() {
        assertNoImport(sourceRoot().resolve("output"), "dev.jonalakas.bridgepad.input.")
    }

    @Test
    fun uiDoesNotImportOutputAdapters() {
        assertNoImport(sourceRoot().resolve("ui"), "dev.jonalakas.bridgepad.output.")
    }

    private fun assertNoImport(root: Path, forbiddenPackage: String) {
        val violations = Files.walk(root).use { files ->
            files.filter { it.extension == "kt" }
                .filter { file -> file.readText().lineSequence().any { line ->
                    line.startsWith("import $forbiddenPackage")
                } }
                .map(root::relativize)
                .toList()
        }
        assertTrue("Forbidden dependency on $forbiddenPackage in $violations", violations.isEmpty())
    }

    private fun sourceRoot(): Path = Path.of("src/main/java/dev/jonalakas/bridgepad")
}
