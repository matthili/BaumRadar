package at.mafue.baumradar.webgis.loader;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @Test
    void emptyFilterMeansAllCities() {
        assertTrue(Config.parseCityFilter("").isEmpty());
        assertTrue(Config.parseCityFilter(null).isEmpty());
        assertTrue(Config.parseCityFilter("   ").isEmpty());
    }

    @Test
    void filterIsTrimmedLowercasedAndDeduplicated() {
        assertEquals(Set.of("wien", "linz"),
                Config.parseCityFilter(" Wien , linz,,WIEN "));
    }
}
