// ===========================================
// Shared Types - Pet-care Frontend
// ===========================================

// API Response wrapper
export interface ApiResponse<T> {
  data: T | null;
  message: string;
  code: number;
}

// Pagination
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

// User & Auth
// BE dung UUID cho moi khoa chinh -> string, khong phai number.
export interface User {
  userId: string;
  accountId: string;
  name: string;
  phone?: string;
  email?: string;
  avatar?: string;
  role: UserRole;
  accountStatus?: AccountStatus;
  storeId?: string;
  organizationId?: string;
}

/** FSM 1 (docs/03-state-machines.md) — accounts.status ben BE. */
export type AccountStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'LOCKED' | 'DEACTIVATED';

export type UserRole =
  | 'SUPER_ADMIN'
  | 'ORG_ADMIN'
  | 'STORE_MANAGER'
  | 'FINANCE_STAFF'
  | 'INVENTORY_STAFF'
  | 'RECEPTIONIST'
  | 'VETERINARIAN'
  | 'GROOMER'
  | 'CUSTOMER';

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

// Email la danh tinh dang nhap duy nhat (BE RULE-01-10), phone chi la lien he.
export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  phone?: string;
  password: string;
  name: string;
}

// Pet
export interface Pet {
  id: number;
  ownerId: number;
  ownerName?: string;
  name: string;
  species: 'CAT' | 'DOG' | 'BIRD' | 'RABBIT' | 'OTHER';
  speciesDisplayName?: string;
  breed?: string;
  birthDate?: string;
  ageYears?: number;
  ageMonths?: number;
  weight?: number;
  imageUrl?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

// Store
export interface Store {
  id: number;
  organizationId: number;
  name: string;
  code: string;
  address?: string;
  phone?: string;
  status: 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
  createdAt?: string;
}

export interface OperatingHours {
  id: number;
  storeId: number;
  dayOfWeek: number;
  openTime: string;
  closeTime: string;
  isClosed: boolean;
}

// Product
export interface Product {
  id: number;
  name: string;
  description?: string;
  category?: string;
  price: number;
  discountPercent?: number;
  finalPrice?: number;
  imageUrl?: string;
  stockQuantity?: number;
  isActive: boolean;
}

// Order
export interface Order {
  id: number;
  orderNumber: string;
  customerId: number;
  storeId: number;
  status: OrderStatus;
  subtotal: number;
  discount: number;
  total: number;
  voucherCode?: string;
  notes?: string;
  items?: OrderItem[];
  createdAt: string;
  updatedAt: string;
}

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'READY'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'REFUNDED';

export interface OrderItem {
  id: number;
  productId: number;
  productName?: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

// Cart
export interface Cart {
  id: number;
  customerId: number;
  storeId: number;
  items: CartItem[];
  subtotal: number;
  discount: number;
  total: number;
}

export interface CartItem {
  id: number;
  productId: number;
  productName?: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

// Payment
export interface Payment {
  id: number;
  orderId: number;
  amount: number;
  method: PaymentMethod;
  status: PaymentStatus;
  transactionId?: string;
  paidAt?: string;
  createdAt: string;
}

export type PaymentMethod = 'CASH' | 'ONLINE' | 'TRANSFER';
export type PaymentStatus =
  | 'PENDING'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED';

// Appointment
export interface Appointment {
  id: number;
  petId: number;
  pet?: Pet;
  storeId: number;
  store?: Store;
  serviceId: number;
  service?: Service;
  staffId?: number;
  staff?: User;
  customerId: number;
  customer?: User;
  scheduledAt: string;
  status: AppointmentStatus;
  notes?: string;
  checkedInAt?: string;
  startedAt?: string;
  completedAt?: string;
  cancelledAt?: string;
  cancellationReason?: string;
  createdAt: string;
}

export type AppointmentStatus =
  | 'BOOKED'
  | 'CONFIRMED'
  | 'CHECKED_IN'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW';

// Service
export interface Service {
  id: number;
  name: string;
  description?: string;
  category?: string;
  durationMinutes: number;
  price: number;
  isActive: boolean;
}

// Invoice
export interface Invoice {
  id: number;
  invoiceNumber: string;
  orderId?: number;
  appointmentId?: number;
  customerId: number;
  storeId: number;
  status: InvoiceStatus;
  subtotal: number;
  tax: number;
  discount: number;
  total: number;
  paidAmount: number;
  items?: InvoiceItem[];
  issuedAt?: string;
  voidedAt?: string;
}

export type InvoiceStatus =
  | 'DRAFT'
  | 'ISSUED'
  | 'PAID'
  | 'PARTIALLY_PAID'
  | 'VOID'
  | 'REFUNDED';

export interface InvoiceItem {
  id: number;
  type: 'PRODUCT' | 'SERVICE' | 'VACCINE' | 'MEDICINE';
  referenceId?: number;
  name: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

// Medical Record
export interface MedicalRecord {
  id: number;
  petId: number;
  pet?: Pet;
  appointmentId?: number;
  veterinarianId: number;
  veterinarian?: User;
  storeId: number;
  examinationDate: string;
  chiefComplaint?: string;
  symptoms?: string[];
  examinationResults?: Record<string, any>;
  diagnosis?: string;
  treatmentPlan?: string;
  status: string;
  diagnoses?: Diagnosis[];
  prescriptions?: Prescription[];
}

export interface Diagnosis {
  id: number;
  medicalRecordId: number;
  diagnosisCode?: string;
  diagnosisName: string;
  severity?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  notes?: string;
}

export interface Prescription {
  id: number;
  medicalRecordId: number;
  veterinarianId: number;
  prescriptionDate: string;
  instructions?: string;
  notes?: string;
  status: string;
  items?: PrescriptionItem[];
}

export interface PrescriptionItem {
  id: number;
  prescriptionId: number;
  medicationName: string;
  dosage?: string;
  frequency?: string;
  duration?: string;
  quantity?: string;
  instructions?: string;
  price?: number;
}

// Notification
export interface Notification {
  id: number;
  userId: number;
  type: string;
  title: string;
  message: string;
  referenceType?: string;
  referenceId?: number;
  isRead: boolean;
  readAt?: string;
  createdAt: string;
}

// Voucher
export interface Voucher {
  id: number;
  code: string;
  description?: string;
  discountType: 'PERCENT' | 'FIXED';
  discountValue: number;
  minOrderValue: number;
  maxUsage?: number;
  currentUsage: number;
  maxPerUser: number;
  validFrom: string;
  validTo: string;
  isActive: boolean;
}

// Pagination request
export interface PaginationParams {
  page?: number;
  size?: number;
  sort?: string;
}

// Filter types
export interface AppointmentFilters extends PaginationParams {
  status?: AppointmentStatus;
  storeId?: number;
  petId?: number;
  fromDate?: string;
  toDate?: string;
}

export interface OrderFilters extends PaginationParams {
  status?: OrderStatus;
  storeId?: number;
  customerId?: number;
}

export interface ProductFilters extends PaginationParams {
  category?: string;
  search?: string;
  lowStock?: boolean;
  storeId?: number;
}
