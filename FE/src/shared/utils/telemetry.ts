// Minimal product telemetry: an in-memory ring buffer plus dev console output.
// Shipping to a collector later means replacing `emit` only.

export type TelemetryName =
  | 'workspace_open'
  | 'check_in'
  | 'visit_call'
  | 'visit_complete'
  | 'payment'
  | 'board_move'
  | 'board_move_rejected'
  | 'soap_save'
  | 'timing'
  | 'web_vital'
  | 'workspace_error';

export interface TelemetryEvent {
  name: TelemetryName;
  props?: Record<string, unknown>;
  at: number;
}

const MAX_EVENTS = 100;
const buffer: TelemetryEvent[] = [];

function emit(event: TelemetryEvent) {
  if (import.meta.env.DEV && import.meta.env.MODE !== 'test') console.debug('[telemetry]', event.name, event.props ?? '');
}

export function track(name: TelemetryName, props?: Record<string, unknown>): void {
  const event = { name, props, at: Date.now() };
  buffer.push(event);
  if (buffer.length > MAX_EVENTS) buffer.splice(0, buffer.length - MAX_EVENTS);
  emit(event);
}

export function recentEvents(): readonly TelemetryEvent[] {
  return buffer;
}

/** Start timing a user-visible step; calling the result records { step, ms }. */
export function startTimer(step: string): () => number {
  const started = performance.now();
  return () => {
    const ms = Math.round(performance.now() - started);
    track('timing', { step, ms });
    return ms;
  };
}

type LayoutShift = PerformanceEntry & { value: number; hadRecentInput: boolean };

/** LCP and CLS against the plan's targets (LCP < 2.5 s, CLS < 0.1). Browsers without the entry types skip it. */
export function observeWebVitals(): void {
  if (typeof PerformanceObserver === 'undefined') return;
  try {
    new PerformanceObserver((list) => {
      const entries = list.getEntries();
      const last = entries[entries.length - 1];
      if (last) track('web_vital', { metric: 'LCP', ms: Math.round(last.startTime), target: 2500 });
    }).observe({ type: 'largest-contentful-paint', buffered: true });

    let cls = 0;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as LayoutShift[]) {
        if (!entry.hadRecentInput) cls += entry.value;
      }
      track('web_vital', { metric: 'CLS', value: Number(cls.toFixed(3)), target: 0.1 });
    }).observe({ type: 'layout-shift', buffered: true });
  } catch {
    // Entry type not supported (Firefox/Safari for layout-shift): nothing to report.
  }
}
