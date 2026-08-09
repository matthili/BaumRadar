import { GenusStat, SpeciesStat } from './models';

/** Gattung im Suchergebnis; `matchedVia` erklärt Treffer über einen Artnamen. */
export interface GenusMatch extends GenusStat {
  matchedVia: string | null;
}

/**
 * Namens-übergreifende Suche: matcht Gattung (deutsch + englisch) und alle
 * Artnamen (deutsch + botanisch). Wer nur „Acer" oder „irgendwas mit spitz"
 * im Kopf hat, landet so trotzdem bei „Ahorn" — ausgewählt wird weiterhin die
 * Gattung, passend zu den genus-geclusterten Allergiezonen.
 *
 * Reine Funktion (kein Angular), damit sie isoliert testbar ist.
 */
export function matchGenera(
  query: string,
  genera: readonly GenusStat[],
  speciesByGenus: ReadonlyMap<string, readonly SpeciesStat[]>,
): GenusMatch[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return genera.map((g) => ({ ...g, matchedVia: null }));
  }

  const result: GenusMatch[] = [];
  for (const g of genera) {
    const directHit =
      g.genusDe.toLowerCase().includes(q) ||
      (g.genusEn?.toLowerCase().includes(q) ?? false);
    if (directHit) {
      result.push({ ...g, matchedVia: null });
      continue;
    }
    const speciesHit = (speciesByGenus.get(g.genusDe) ?? []).find(
      (s) =>
        s.speciesDe.toLowerCase().includes(q) ||
        s.speciesEn.toLowerCase().includes(q),
    );
    if (speciesHit) {
      result.push({ ...g, matchedVia: speciesLabel(speciesHit) });
    }
  }
  return result;
}

/**
 * Anzeigename eines Art-Tupels: „Spitzahorn · Acer platanoides".
 *
 * Exportiert nur, damit der Test ihn isoliert prüfen kann; benutzt wird er
 * innerhalb von {@link matchGenera} für den `matchedVia`-Hinweis.
 */
export function speciesLabel(s: SpeciesStat): string {
  if (s.speciesDe && s.speciesEn) {
    return `${s.speciesDe} · ${s.speciesEn}`;
  }
  return s.speciesDe || s.speciesEn;
}
