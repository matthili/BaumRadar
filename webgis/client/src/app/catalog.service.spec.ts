import { CatalogService } from './catalog.service';
import { City } from './models';

describe('CatalogService.sortByName', () => {
  const city = (id: string, name: string): City => ({
    id,
    name,
    country: 'DE',
    boundingBox: [0, 0, 1, 1],
    dataVersion: 'v1',
  });

  it('sortiert alphabetisch, Umlaute wie Basisbuchstaben (Köln vor Konstanz)', () => {
    const sorted = CatalogService.sortByName([
      city('konstanz', 'Konstanz'),
      city('koeln', 'Köln'),
      city('zug', 'Zug'),
      city('bonn', 'Bonn'),
    ]);
    expect(sorted.map((c) => c.name)).toEqual(['Bonn', 'Köln', 'Konstanz', 'Zug']);
  });

  it('lässt das Eingabe-Array unverändert (Kopie statt In-Place-Sort)', () => {
    const input = [city('b', 'Wien'), city('a', 'Graz')];
    CatalogService.sortByName(input);
    expect(input.map((c) => c.id)).toEqual(['b', 'a']);
  });
});
