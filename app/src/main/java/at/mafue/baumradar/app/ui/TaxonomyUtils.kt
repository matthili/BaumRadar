package at.mafue.baumradar.app.ui

import at.mafue.baumradar.app.data.TreeSpeciesDTO

object TaxonomyUtils {

    fun extractTrivialName(speciesList: List<TreeSpeciesDTO>): String {
        // Look for the generic entry like "spec. (Ahorn)"
        val specEntry = speciesList.find { it.genusDe?.contains("spec.") == true }
        if (specEntry != null) {
            val match = Regex("""spec\.\s*\((.+?)\)""").find(specEntry.genusDe ?: "")
            if (match != null) return match.groupValues[1]
        }
        // Fallback: extract any parenthesis from the first entry if 'spec.' wasn't found
        val first = speciesList.firstOrNull()?.genusDe ?: ""
        val match = Regex("""\((.+?)\)""").find(first)
        if (match != null) return match.groupValues[1]
        
        return ""
    }

    fun sanitizeDisplayName(rawName: String): String {
        if (rawName.contains("(") && !rawName.contains(")")) {
            return "$rawName)"
        }
        return rawName
    }
}
