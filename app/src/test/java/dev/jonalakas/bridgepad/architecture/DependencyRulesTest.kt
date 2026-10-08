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

    @Test
    fun activityDoesNotControlOutputAdaptersDirectly() {
        val activity = sourceRoot().resolve("MainActivity.kt")
        val importsOutput = activity.readText().lineSequence().any { line ->
            line.startsWith("import dev.jonalakas.bridgepad.output.")
        }
        assertTrue("MainActivity must use SessionCoordinator", !importsOutput)
    }

    @Test
    fun activityUsesUnifiedSessionUiState() {
        val source = sourceRoot().resolve("MainActivity.kt").readText()
        assertTrue("MainActivity must use SessionUiViewModel", "SessionUiViewModel" in source)
        assertTrue("Legacy touch-controller flag must not return", "showTouchController" !in source)
        assertTrue("Legacy mouse-touchpad flag must not return", "showMouseTouchpad" !in source)
        assertTrue("Legacy connection timing flag must not return", "openAfterConnection" !in source)
    }

    @Test
    fun desktopGameplayHasAUnifiedAndroidLifecycleHost() {
        val service = sourceRoot().resolve("session/GameplaySessionService.kt").readText()
        val resources = sourceRoot().resolve("session/GameplaySessionResources.kt").readText()
        val manifest = Path.of("src/main/AndroidManifest.xml").readText()
        val coordinator = sourceRoot().resolve("session/NetworkDesktopCoordinator.kt").readText()
        val bluetoothDesktop = sourceRoot().resolve("session/BluetoothDesktopSessionAdapter.kt").readText()
        val bluetoothHid = sourceRoot().resolve("output/hid/BluetoothHidService.kt").readText()

        assertTrue("Desktop gameplay must use a foreground service", "startForeground" in service)
        assertTrue("Screen-off gameplay must keep the CPU awake", "PARTIAL_WAKE_LOCK" in resources)
        assertTrue("GameplaySessionService must be registered", ".session.GameplaySessionService" in manifest)
        assertTrue("Starting gameplay must start the lifecycle host", "GameplaySessionService.startNetwork" in coordinator)
        assertTrue("Stopping gameplay must stop the lifecycle host", "GameplaySessionService.stop" in coordinator)
        assertTrue(
            "Bluetooth Desktop must use the unified lifecycle host",
            "GameplaySessionService.startBluetoothDesktop" in bluetoothDesktop,
        )
        assertTrue(
            "Direct HID must share the same background resource contract",
            "GameplaySessionResources" in bluetoothHid,
        )
        assertTrue("Direct HID must be restartable after process reclamation", "START_STICKY" in bluetoothHid)
    }

    @Test
    fun interactiveGameplaySurfacesKeepTheScreenAwake() {
        val activity = sourceRoot().resolve("MainActivity.kt").readText()

        assertTrue("Interactive gameplay must use Android's keep-screen-on flag", "FLAG_KEEP_SCREEN_ON" in activity)
        assertTrue("Background USB must remain eligible for screen-off play", "compatibilityInputNeedsScreen" in activity)
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
