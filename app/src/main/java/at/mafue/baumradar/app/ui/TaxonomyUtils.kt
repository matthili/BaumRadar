package at.mafue.baumradar.app.ui

import at.mafue.baumradar.app.data.TreeSpeciesDTO

/**
 * Hilfsfunktionen für die Aufbereitung botanischer Namen in der UI.
 *
 * Kommunale Baumkataster liefern Artnamen in unterschiedlichen Formaten, z. B.:
 * - `Acer spec. (Ahorn)` – Gattung mit Trivialname in Klammern
 * - `Acer platanoides (Spitz-Ahorn)` – Volle Art mit Trivialname
 * - `Acer platanoides (Spitz-Ahorn` – Fehlende schließende Klammer (häufig!)
 *
 * Diese Klasse extrahiert und bereinigt die Trivialnamen für die Anzeige.
 */
object TaxonomyUtils {

    /**
     * Extrahiert den deutschen Trivialnamen einer Gattung aus der Artenliste.
     *
     * Strategie:
     * 1. Bevorzugt: Suche nach einem `spec.`-Eintrag (z. B. `Acer spec. (Ahorn)`),
     *    da dieser den allgemeinen Gattungsnamen enthält
     * 2. Fallback: Extrahiere den Klammerausdruck aus dem ersten Listeneintrag
     * 3. Letzter Fallback: Leerer String
     */
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

    /**
     * Repariert Anzeigenamen mit fehlender schließender Klammer.
     *
     * Viele Baumkataster-Datensätze haben inkonsistente Klammerformatierung.
     * Diese Funktion fügt eine fehlende `)` hinzu, falls eine `(` vorhanden ist.
     */
    fun sanitizeDisplayName(rawName: String): String {
        if (rawName.contains("(") && !rawName.contains(")")) {
            return "$rawName)"
        }
        return rawName
    }
}
