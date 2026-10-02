// Single source of React Query keys for the staff workspaces. Every key starts with its
// resource root, so invalidating QUERY_KEYS.<resource>.all refreshes every query of it.
export const QUERY_KEYS = {
  visits: {
    all: ['visits'] as const,
    today: () => ['visits', 'today'] as const,
    detail: (visitId: string) => ['visits', 'detail', visitId] as const,
  },
  appointments: {
    all: ['appointments'] as const,
    arrivals: () => ['appointments', 'arrivals'] as const,
  },
  orders: {
    all: ['orders'] as const,
    pending: () => ['orders', 'pending'] as const,
  },
  customers: {
    all: ['customers'] as const,
    search: (query: string) => ['customers', 'search', query] as const,
  },
  catalog: { all: ['catalog'] as const },
  staff: { all: ['staff'] as const },
  shift: {
    all: ['shift'] as const,
    current: (receptionistId: string) => ['shift', 'current', receptionistId] as const,
  },
  dashboard: {
    all: ['dashboard'] as const,
    summary: () => ['dashboard', 'summary'] as const,
  },
} as const;

const { visits, appointments, orders, catalog, shift, dashboard } = QUERY_KEYS;

/** What each command changes — across workspaces (a check-in at reception must reach the exam room). */
export const INVALIDATE = {
  checkIn: [visits.all, appointments.all, orders.all, dashboard.all],
  assignVisit: [visits.all, dashboard.all],
  callVisit: [visits.all, dashboard.all],
  completeVisit: [visits.all, orders.all, appointments.all, dashboard.all],
  cancelVisit: [visits.all, orders.all, appointments.all, dashboard.all],
  saveRecord: [visits.all],
  editVisitLines: [visits.all, catalog.all],
  openShift: [shift.all, dashboard.all],
  collectPayment: [orders.all, visits.all, shift.all, catalog.all, dashboard.all],
} satisfies Record<string, readonly (readonly unknown[])[]>;
