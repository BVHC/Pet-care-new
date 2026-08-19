// API endpoints
export const API_ENDPOINTS = {
  AUTH: {
    LOGIN: '/api/auth/login',
    REGISTER: '/api/auth/register',
    OTP_VERIFY: '/api/auth/verify-otp',
    OTP_RESEND: '/api/auth/resend-otp',
    REFRESH: '/api/auth/refresh',
    LOGOUT: '/api/auth/logout',
  },
  USERS: {
    LIST: '/api/users',
    GET: '/api/users/:id',
    UPDATE: '/api/users/:id',
  },
  ORGANIZATIONS: {
    LIST: '/api/organizations',
    GET: '/api/organizations/:id',
    CREATE: '/api/organizations',
    UPDATE: '/api/organizations/:id',
  },
  STORES: {
    LIST: '/api/stores',
    GET: '/api/stores/:id',
    CREATE: '/api/stores',
    UPDATE: '/api/stores/:id',
  },
  PETS: {
    LIST: '/api/pets',
    GET: '/api/pets/:id',
    CREATE: '/api/pets',
    UPDATE: '/api/pets/:id',
    DELETE: '/api/pets/:id',
    CAREGIVERS: '/api/pets/:id/caregivers',
  },
  PRODUCTS: {
    LIST: '/api/products',
    GET: '/api/products/:id',
    CREATE: '/api/products',
    UPDATE: '/api/products/:id',
  },
  INVENTORY: {
    LIST: '/api/inventory',
    UPDATE: '/api/inventory/:id',
  },
  APPOINTMENTS: {
    LIST: '/api/appointments',
    GET: '/api/appointments/:id',
    CREATE: '/api/appointments',
    UPDATE: '/api/appointments/:id',
    CANCEL: '/api/appointments/:id/cancel',
    CHECK_IN: '/api/appointments/:id/check-in',
  },
  ORDERS: {
    LIST: '/api/orders',
    GET: '/api/orders/:id',
    CREATE: '/api/orders',
    CANCEL: '/api/orders/:id/cancel',
    CART: '/api/cart',
    CART_ADD: '/api/cart/items',
    CART_REMOVE: '/api/cart/items/:id',
  },
  PAYMENTS: {
    LIST: '/api/payments',
    GET: '/api/payments/:id',
    CREATE: '/api/payments',
    CALLBACK: '/api/payments/callback',
  },
  INVOICES: {
    LIST: '/api/invoices',
    GET: '/api/invoices/:id',
    CREATE: '/api/invoices',
    VOID: '/api/invoices/:id/void',
  },
  REFUNDS: {
    LIST: '/api/refunds',
    GET: '/api/refunds/:id',
    REQUEST: '/api/refunds',
    APPROVE: '/api/refunds/:id/approve',
    REJECT: '/api/refunds/:id/reject',
  },
  CLINICAL: {
    RECORDS: '/api/clinical/records',
    GET: '/api/clinical/records/:id',
    CREATE: '/api/clinical/records',
    UPDATE: '/api/clinical/records/:id',
    PRESCRIPTIONS: '/api/clinical/prescriptions',
  },
  VACCINATIONS: {
    LIST: '/api/vaccinations',
    GET: '/api/vaccinations/:id',
    CREATE: '/api/vaccinations',
    SCHEDULE: '/api/vaccinations/schedules',
  },
  PROMOTIONS: {
    LIST: '/api/vouchers',
    VALIDATE: '/api/vouchers/validate',
  },
}

// Roles
export const ROLES = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  STORE_MANAGER: 'STORE_MANAGER',
  RECEPTIONIST: 'RECEPTIONIST',
  VETERINARIAN: 'VETERINARIAN',
  GROOMER: 'GROOMER',
  CUSTOMER: 'CUSTOMER',
} as const

// Storage keys
export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'access_token',
  REFRESH_TOKEN: 'refresh_token',
  USER: 'user',
} as const
