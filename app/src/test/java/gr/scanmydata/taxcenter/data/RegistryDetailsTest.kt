package gr.scanmydata.taxcenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Συνθετικά στοιχεία — κανένα πραγματικό πρόσωπο. */
class RegistryDetailsTest {

    @Test
    fun `parses sections in order and skips empty ones`() {
        val json = """
            [
              {"title": "Φυσικό πρόσωπο", "rows": [["Πατρώνυμο", "ΓΕΩΡΓΙΟΣ"], ["Αριθμός ταυτότητας", "ΑΒ123456"]]},
              {"title": "Κενή", "rows": []},
              {"title": "Δραστηριότητες (ΚΑΔ)", "rows": [["69201000", "Λογιστικές υπηρεσίες · ΚΥΡΙΑ"]]}
            ]
        """.trimIndent()
        val sections = RegistryDetails.parse(json)
        assertEquals(listOf("Φυσικό πρόσωπο", "Δραστηριότητες (ΚΑΔ)"), sections.map { it.title })
        assertEquals("Πατρώνυμο" to "ΓΕΩΡΓΙΟΣ", sections[0].rows[0])
        assertEquals(3, RegistryDetails.count(sections))
    }

    @Test
    fun `unreadable input is no sections`() {
        assertTrue(RegistryDetails.parse("").isEmpty())
        assertTrue(RegistryDetails.parse("{not json").isEmpty())
        assertTrue(RegistryDetails.parse("""[{"rows": [["a","b"]]}]""").isEmpty())
    }
}
