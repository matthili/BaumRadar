import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Zustand eines Stack-Moduls für die Anzeige im Logo-Overlay. */
export interface ModuleStatus {
  key: string;
  label: string;
  state: 'disabled' | 'pending' | 'ready' | 'stalled';
  /** Klartext, z. B. „lädt Stadt-Daten (17/19: stuttgart)" oder „bereit". */
  text: string;
}

/** stack.json — was dieser Stack laden SOLL (vom Start-Skript via nginx). */
interface StackConfig {
  routing: boolean;
  geocoding: boolean;
  cityFilter: string;
}

/** /status/<job>.json — Phasen-Meldung eines Lade-Jobs (Entrypoint-Skripte). */
interface JobStatus {
  phase: string;
  detail?: string;
  updatedAt?: string;
}

/**
 * Sammelt den Ladezustand des Stacks aus drei ECHTEN Quellen — nichts ist geraten:
 *
 * 1. `stack.json`: welche Module gewollt sind (sonst: „deaktiviert" statt „lädt").
 * 2. `/status/<job>.json`: woran ein Job gerade arbeitet (dieselben Meldungen wie
 *    in `docker logs`, von den Entrypoints in ein geteiltes Volume gespiegelt).
 * 3. Live-Probes: ob ein Dienst tatsächlich antwortet (= das einzige „bereit").
 *
 * Statt einer Wanduhr-Frist gibt es nur eine daten-basierte Warnung: bewegt sich
 * der {@code updatedAt}-Stempel einer laufenden Phase 15 Minuten nicht, gilt der
 * Job als „hängt möglicherweise".
 */
@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly http = inject(HttpClient);

  private static readonly POLL_MS = 5_000;
  private static readonly STALL_MS = 15 * 60_000;

  readonly modules = signal<ModuleStatus[]>([]);
  readonly cityFilter = signal<string>('');
  /** true, solange mindestens ein gewolltes Modul noch nicht bereit ist. */
  readonly anyPending = computed(() =>
    this.modules().some((m) => m.state === 'pending' || m.state === 'stalled'),
  );

  private stack: StackConfig = { routing: true, geocoding: true, cityFilter: '' };
  private timer?: ReturnType<typeof setTimeout>;

  /** Einmal beim App-Start aufrufen; pollt, bis alles bereit ist. */
  start(): void {
    void this.init();
  }

  private async init(): Promise<void> {
    try {
      this.stack = await firstValueFrom(this.http.get<StackConfig>('/stack.json'));
    } catch {
      // Alter Stack ohne stack.json → alles gilt als gewollt.
    }
    this.cityFilter.set(this.stack.cityFilter ?? '');
    await this.refresh();
  }

  private async refresh(): Promise<void> {
    const [geoserver, trees, routing, geocoding] = await Promise.all([
      this.probeGeoServer(),
      this.probeTrees(),
      this.moduleStatus('routing', this.stack.routing, this.probeRouting(),
        ['graph-builder', 'graphhopper']),
      this.moduleStatus('geocoding', this.stack.geocoding, this.probePhoton(), ['photon']),
    ]);

    this.modules.set([
      { key: 'geoserver', label: 'Karten-Dienste', ...geoserver },
      { key: 'trees', label: 'Baumdaten', ...trees },
      { key: 'routing', label: 'Routenplanung', ...routing },
      { key: 'geocoding', label: 'Adresssuche', ...geocoding },
    ]);

    clearTimeout(this.timer);
    if (this.anyPending()) {
      this.timer = setTimeout(() => void this.refresh(), StatusService.POLL_MS);
    }
  }

  // --- Die vier Modul-Zustände ---------------------------------------------

  private async probeGeoServer(): Promise<Pick<ModuleStatus, 'state' | 'text'>> {
    const ok = await this.ok('/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetCapabilities');
    return ok ? { state: 'ready', text: 'bereit' } : { state: 'pending', text: 'startet …' };
  }

  /**
   * Prüft nicht die Baum-Tabelle selbst, sondern `genus_stats`: Diese Statistik
   * füllt der Loader erst *nach* dem Import einer Stadt. Sie ist damit das
   * verlässliche „fertig"-Signal — die Baum-Tabelle enthält schon mitten im
   * Import Zeilen und würde zu früh „bereit" melden.
   */
  private async probeTrees(): Promise<Pick<ModuleStatus, 'state' | 'text'>> {
    try {
      const params = new HttpParams({
        fromObject: {
          service: 'WFS', version: '2.0.0', request: 'GetFeature',
          typeNames: 'baumradar:genus_stats', outputFormat: 'application/json', count: '1',
        },
      });
      const fc = await firstValueFrom(
        this.http.get<{ features?: unknown[] }>('/geoserver/baumradar/wfs', { params }),
      );
      if ((fc.features ?? []).length > 0) return { state: 'ready', text: 'bereit' };
      return { state: 'pending', text: 'importiert …' };
    } catch {
      return { state: 'pending', text: 'importiert …' };
    }
  }

  private async probeRouting(): Promise<boolean> {
    return this.ok('/graphhopper/info');
  }

  private async probePhoton(): Promise<boolean> {
    return this.ok('/photon/api?q=probe&limit=1');
  }

  /**
   * Kombiniert für ein optionales Modul: gewollt? → Probe (bereit?) → Status-Datei
   * (woran arbeitet es?) → Staleness (hängt es?).
   */
  private async moduleStatus(
    key: string,
    expected: boolean,
    probe: Promise<boolean>,
    jobs: string[],
  ): Promise<Pick<ModuleStatus, 'state' | 'text'>> {
    if (!expected) return { state: 'disabled', text: 'deaktiviert' };
    if (await probe) return { state: 'ready', text: 'bereit' };

    // Nicht bereit → die jüngste Job-Meldung erklärt, was gerade passiert.
    let latest: JobStatus | null = null;
    for (const job of jobs) {
      const s = await this.jobStatus(job);
      if (s && (!latest || (s.updatedAt ?? '') > (latest.updatedAt ?? ''))) latest = s;
    }
    if (!latest) return { state: 'pending', text: 'startet …' };

    const detail = latest.detail ? ` (${latest.detail})` : '';
    const age = latest.updatedAt ? Date.now() - Date.parse(latest.updatedAt) : 0;
    if (age > StatusService.STALL_MS && latest.phase !== 'fertig') {
      return {
        state: 'stalled',
        text: `${latest.phase}${detail} — seit ${Math.round(age / 60_000)} min keine Aktivität, hängt evtl. (docker logs)`,
      };
    }
    return { state: 'pending', text: `${latest.phase}${detail} …` };
  }

  private async jobStatus(job: string): Promise<JobStatus | null> {
    try {
      return await firstValueFrom(this.http.get<JobStatus>(`/status/${job}.json`));
    } catch {
      return null;
    }
  }

  private async ok(url: string): Promise<boolean> {
    try {
      await firstValueFrom(this.http.get(url, { responseType: 'text' }));
      return true;
    } catch {
      return false;
    }
  }
}
