import { GenusStat, SpeciesStat } from './models';
import { matchGenera, speciesLabel } from './search';

describe('matchGenera', () => {
  const genera: GenusStat[] = [
    { genusDe: 'Ahorn', genusEn: 'Maple', treeCount: 538812 },
    { genusDe: 'Birke', genusEn: 'Birch', treeCount: 81670 },
    { genusDe: 'Linde', genusEn: 'Lime', treeCount: 430640 },
  ];

  const species = new Map<string, SpeciesStat[]>([
    [
      'Ahorn',
      [
        { genusDe: 'Ahorn', speciesDe: 'Spitzahorn', speciesEn: 'Acer platanoides', treeCount: 120000 },
        { genusDe: 'Ahorn', speciesDe: 'Bergahorn', speciesEn: 'Acer pseudoplatanus', treeCount: 90000 },
      ],
    ],
    [
      'Birke',
      [{ genusDe: 'Birke', speciesDe: 'Moorbirke', speciesEn: 'Betula pubescens', treeCount: 5000 }],
    ],
  ]);

  it('leere Suche liefert alle Gattungen ohne via-Hinweis', () => {
    const result = matchGenera('', genera, species);
    expect(result.length).toBe(3);
    expect(result.every((g) => g.matchedVia === null)).toBe(true);
  });

  it('findet über den botanischen Namen ("Acer" -> Ahorn)', () => {
    const result = matchGenera('acer', genera, species);
    expect(result.length).toBe(1);
    expect(result[0].genusDe).toBe('Ahorn');
    expect(result[0].matchedVia).toBe('Spitzahorn · Acer platanoides');
  });

  it('findet über den deutschen Artnamen ("spitz" -> Ahorn)', () => {
    const result = matchGenera('spitz', genera, species);
    expect(result.length).toBe(1);
    expect(result[0].genusDe).toBe('Ahorn');
    expect(result[0].matchedVia).toContain('Spitzahorn');
  });

  it('direkter Gattungstreffer hat keinen via-Hinweis', () => {
    const result = matchGenera('birke', genera, species);
    expect(result.length).toBe(1);
    expect(result[0].matchedVia).toBeNull();
  });

  it('findet über den englischen Gattungsnamen ("maple" -> Ahorn)', () => {
    const result = matchGenera('maple', genera, species);
    expect(result.length).toBe(1);
    expect(result[0].genusDe).toBe('Ahorn');
    expect(result[0].matchedVia).toBeNull();
  });

  it('Gattung ohne Art-Daten ist weiterhin über den Namen findbar', () => {
    const result = matchGenera('linde', genera, species);
    expect(result.length).toBe(1);
    expect(result[0].genusDe).toBe('Linde');
  });
});

describe('speciesLabel', () => {
  it('kombiniert deutschen und botanischen Namen', () => {
    expect(
      speciesLabel({ genusDe: 'x', speciesDe: 'Spitzahorn', speciesEn: 'Acer platanoides', treeCount: 1 }),
    ).toBe('Spitzahorn · Acer platanoides');
  });

  it('kommt mit fehlendem deutschen Namen aus', () => {
    expect(
      speciesLabel({ genusDe: 'x', speciesDe: '', speciesEn: 'Acer spec.', treeCount: 1 }),
    ).toBe('Acer spec.');
  });
});
