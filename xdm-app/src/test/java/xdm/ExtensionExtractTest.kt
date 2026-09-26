package xdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xdm.app.ui.screens.settings.ExtensionSetupDialog
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * The bundled browser extension is copied out of the app jar with plain JarFile. It used a zip
 * FileSystem before, which the installers' trimmed runtime (no jdk.zipfs) cannot open, so
 * "Open extension folder" failed on every installed copy.
 */
class ExtensionExtractTest {

    private fun jarWith(entries: Map<String, String>): File {
        val file = Files.createTempFile("ext-test", ".jar").toFile()
        JarOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(JarEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun copiesOnlyTheRequestedFolderKeepingSubfolders() {
        val jar = jarWith(
            mapOf(
                "extension/chrome-extension/manifest.json" to "{}",
                "extension/chrome-extension/lib/app.js" to "js",
                "extension/firefox-extension/manifest.json" to "ff",
                "xdm/app/Other.class" to "x",
            )
        )
        val target = Files.createTempDirectory("ext-out").toFile()
        JarFile(jar).use { ExtensionSetupDialog.copyEntries(it, "extension/chrome-extension/", target) }

        assertEquals("{}", File(target, "manifest.json").readText())
        assertEquals("js", File(target, "lib/app.js").readText())
        assertFalse(File(target, "firefox-extension").exists())
        assertEquals(setOf("manifest.json", "lib"), target.list()!!.toSet())
    }

    @Test
    fun refusesEntriesThatEscapeTheFolder() {
        val jar = jarWith(mapOf("extension/chrome-extension/../../evil.txt" to "x"))
        val target = Files.createTempDirectory("ext-out").toFile()
        val error = runCatching {
            JarFile(jar).use { ExtensionSetupDialog.copyEntries(it, "extension/chrome-extension/", target) }
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $error", error is IllegalArgumentException)
        assertFalse(File(target.parentFile.parentFile, "evil.txt").exists())
    }
}
