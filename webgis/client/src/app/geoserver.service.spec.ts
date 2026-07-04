import { GeoServerService } from './geoserver.service';

describe('GeoServerService.genusCql', () => {
  it('liefert null ohne Auswahl', () => {
    expect(GeoServerService.genusCql(new Set())).toBeNull();
  });

  it('baut ein IN-Prädikat über die Auswahl', () => {
    const cql = GeoServerService.genusCql(new Set(['Birke']));
    expect(cql).toBe("genus_de IN ('Birke')");
  });

  it('escaped einfache Anführungszeichen CQL-konform', () => {
    const cql = GeoServerService.genusCql(new Set(["O'Baum"]));
    expect(cql).toBe("genus_de IN ('O''Baum')");
  });
});
