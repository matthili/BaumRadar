package at.mafue.baumradar.app.ui

import at.mafue.baumradar.app.data.TreeSpeciesDTO
import org.junit.Assert.assertEquals
import org.junit.Test

class TaxonomyUtilsTest {

    @Test
    fun testExtractTrivialName_WithSpec() {
        val list = listOf(
            TreeSpeciesDTO("Acer spec. (Ahorn)", "Acer", "Acer", null)
        )
        val result = TaxonomyUtils.extractTrivialName(list)
        assertEquals("Ahorn", result)
    }

    @Test
    fun testExtractTrivialName_WithoutSpec() {
        val list = listOf(
            TreeSpeciesDTO("Quercus robur (Stieleiche)", "Quercus", "Quercus", null)
        )
        val result = TaxonomyUtils.extractTrivialName(list)
        assertEquals("Stieleiche", result)
    }

    @Test
    fun testExtractTrivialName_Empty() {
        val result = TaxonomyUtils.extractTrivialName(emptyList())
        assertEquals("", result)
    }

    @Test
    fun testSanitizeDisplayName_UnclosedParenthesis() {
        val result = TaxonomyUtils.sanitizeDisplayName("Zanthoxylum simulans (Täuschende Stachelesche")
        assertEquals("Zanthoxylum simulans (Täuschende Stachelesche)", result)
    }

    @Test
    fun testSanitizeDisplayName_Normal() {
        val result = TaxonomyUtils.sanitizeDisplayName("Quercus robur (Stieleiche)")
        assertEquals("Quercus robur (Stieleiche)", result)
        
        val result2 = TaxonomyUtils.sanitizeDisplayName("Acer")
        assertEquals("Acer", result2)
    }
}
