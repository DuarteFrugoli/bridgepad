package dev.jonalakas.bridgepad.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationResourcesTest {
    private fun strings(directory: String): Map<String, String> {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "$directory/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            assertTrue("Duplicate string: $name in $directory", name !in result)
            assertTrue("Empty string: $name in $directory", node.textContent.isNotBlank())
            result[name] = node.textContent
        }
        return result
    }

    @Test
    fun portugueseCoversAllDefaultStringsAndPreservesFormatting() {
        val english = strings("values")
        val portuguese = strings("values-pt-rBR")
        assertEquals(english.keys, portuguese.keys)
        val placeholder = Regex("%[0-9]+\\\$[a-zA-Z]")
        english.forEach { (name, value) ->
            assertEquals(
                "Format arguments differ for $name",
                placeholder.findAll(value).map { it.value }.sorted().toList(),
                placeholder.findAll(portuguese.getValue(name)).map { it.value }.sorted().toList(),
            )
        }
    }
}
