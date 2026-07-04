import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  // Bewusst ohne detectChanges(): ngAfterViewInit würde die echte
  // OpenLayers-Karte erzeugen — das gehört nicht in einen jsdom-Unit-Test.
  it('instanziert die Shell mit Default-Zustand', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
    expect(app.showTrees()).toBe(true);
    expect(app.showZones()).toBe(true);
    expect(app.selectedGenera().size).toBe(0);
  });

  it('toggleGenus arbeitet immutable auf dem Set', () => {
    const app = TestBed.createComponent(App).componentInstance;
    app.toggleGenus('Birke');
    app.toggleGenus('Erle');
    expect(app.selectedGenera().has('Birke')).toBe(true);
    app.toggleGenus('Birke');
    expect(app.selectedGenera().has('Birke')).toBe(false);
    expect(app.selectedGenera().has('Erle')).toBe(true);
  });

  it('toggleExpand klappt Gattungen unabhängig von der Auswahl auf/zu', () => {
    const app = TestBed.createComponent(App).componentInstance;
    app.toggleExpand('Ahorn');
    expect(app.expandedGenera().has('Ahorn')).toBe(true);
    expect(app.selectedGenera().size).toBe(0);
    app.toggleExpand('Ahorn');
    expect(app.expandedGenera().has('Ahorn')).toBe(false);
  });

  it('speciesFor liefert die Arten einer Gattung nach Häufigkeit sortiert', () => {
    const app = TestBed.createComponent(App).componentInstance;
    app.species.set([
      { genusDe: 'Ahorn', speciesDe: 'Bergahorn', speciesEn: 'Acer pseudoplatanus', treeCount: 90 },
      { genusDe: 'Ahorn', speciesDe: 'Spitzahorn', speciesEn: 'Acer platanoides', treeCount: 120 },
      { genusDe: 'Birke', speciesDe: 'Moorbirke', speciesEn: 'Betula pubescens', treeCount: 5 },
    ]);
    const ahorn = app.speciesFor('Ahorn');
    expect(ahorn.length).toBe(2);
    expect(ahorn[0].speciesDe).toBe('Spitzahorn');
    expect(app.speciesFor('Eiche').length).toBe(0);
  });
});
