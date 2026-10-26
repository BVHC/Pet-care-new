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
  /** Hồ sơ khách tại quầy (UC22..26) — theo contract docs/api/customer-v1.md #8..21. */
  counterCustomers: {
    all: ['counter-customers'] as const,
    search: (params: Record<string, string>) => ['counter-customers', 'search', params] as const,
    detail: (customerId: number) => ['counter-customers', 'detail', customerId] as const,
  },
  pets: {
    all: ['pets'] as const,
    mine: () => ['pets', 'mine'] as const,
    detail: (petId: number) => ['pets', 'detail', petId] as const,
    weights: (petId: number) => ['pets', 'weights', petId] as const,
    health: (petId: number) => ['pets', 'health', petId] as const,
  },
  catalog: { all: ['catalog'] as const },
  /** Trang công khai (UC15–17); `branches` và `services(branchId)` trùng khóa của usePublicBranches / usePublicServices. */
  public: {
    all: ['public'] as const,
    branches: () => ['public', 'branches'] as const,
    services: (params?: object) => ['public', 'services', 'list', params ?? {}] as const,
    vets: (branchId?: number) => ['public', 'vets', branchId ?? null] as const,
    productCategories: () => ['public', 'product-categories'] as const,
    products: (query: object) => ['public', 'products', 'list', query] as const,
    product: (productId: number) => ['public', 'products', 'detail', productId] as const,
    articleCategories: () => ['public', 'article-categories'] as const,
    articles: (query: object) => ['public', 'articles', 'list', query] as const,
    article: (slug: string) => ['public', 'articles', 'detail', slug] as const,
  },
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

const { visits, appointments, orders, catalog, shift, dashboard, counterCustomers, pets } = QUERY_KEYS;

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
  // KH module: lễ tân tạo / sửa hồ sơ khách + thêm thú thì cả customer detail và search.
  createCounterCustomer: [counterCustomers.all, pets.all],
  updateCounterCustomer: [counterCustomers.all, pets.all],
  addPetForCustomer: [counterCustomers.all, pets.all],
  createMyPet: [pets.all, counterCustomers.all],
  updatePet: [pets.all, counterCustomers.all],
  markDeceased: [pets.all, counterCustomers.all],
  deletePet: [pets.all, counterCustomers.all],
  transferOwner: [pets.all, counterCustomers.all],
  addWeight: [pets.all],
} satisfies Record<string, readonly (readonly unknown[])[]>;
