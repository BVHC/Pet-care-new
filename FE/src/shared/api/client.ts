// ===========================================
// API Client - Pet-care Frontend
// ===========================================

import type {
  ApiResponse,
  PageResponse,
  User,
  AuthTokens,
  LoginRequest,
  RegisterRequest,
  Pet,
  Product,
  Order,
  Cart,
  Payment,
  Appointment,
  Service,
  Invoice,
  Notification,
  Voucher,
  PaginationParams,
} from '../types';

// Base URL from environment
const API_BASE_URL = (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_URL) || 'http://localhost:8080/api';

// Token storage
const getAccessToken = () => typeof localStorage !== 'undefined' ? localStorage.getItem('accessToken') : null;
const getRefreshToken = () => typeof localStorage !== 'undefined' ? localStorage.getItem('refreshToken') : null;
const setTokens = (tokens: AuthTokens) => {
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem('accessToken', tokens.accessToken);
    localStorage.setItem('refreshToken', tokens.refreshToken);
  }
};
const clearTokens = () => {
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
  }
};

// HTTP Client
class ApiClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<ApiResponse<T>> {
    const url = `${this.baseUrl}${endpoint}`;
    const token = getAccessToken();

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(token && { Authorization: `Bearer ${token}` }),
      ...(options.headers as Record<string, string>),
    };

    try {
      const response = await fetch(url, {
        ...options,
        headers,
      });

      const data = await response.json();

      if (!response.ok) {
        // Handle 401 - try refresh token
        if (response.status === 401 && getRefreshToken()) {
          const refreshed = await this.refreshToken();
          if (refreshed) {
            // Retry original request
            const newToken = localStorage.getItem('accessToken');
            const retryHeaders: Record<string, string> = {
              ...headers,
              Authorization: `Bearer ${newToken}`,
            };
            const retryResponse = await fetch(url, { ...options, headers: retryHeaders });
            return retryResponse.json();
          }
        }
        throw new Error(data.message || 'Request failed');
      }

      return data;
    } catch (error) {
      console.error('API Error:', error);
      throw error;
    }
  }

  // Auth endpoints
  async login(credentials: LoginRequest): Promise<ApiResponse<AuthTokens & { user: User }>> {
    const response = await this.request<AuthTokens & { user: User }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    });
    if (response.data) {
      setTokens(response.data);
    }
    return response;
  }

  async register(data: RegisterRequest): Promise<ApiResponse<{ accountId: number }>> {
    return this.request('/auth/register', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async verifyOtp(phone: string, otp: string): Promise<ApiResponse<{ userId: number; accountId: number }>> {
    return this.request('/auth/verify-otp', {
      method: 'POST',
      body: JSON.stringify({ phone, otp }),
    });
  }

  async refreshToken(): Promise<boolean> {
    const refreshToken = getRefreshToken();
    if (!refreshToken) return false;

    try {
      const response = await this.request<AuthTokens>('/auth/refresh-token', {
        method: 'POST',
        body: JSON.stringify({ refreshToken }),
      });
      if (response.data) {
        setTokens(response.data);
        return true;
      }
    } catch {
      clearTokens();
    }
    return false;
  }

  async logout(): Promise<void> {
    await this.request('/auth/logout', { method: 'POST' });
    clearTokens();
  }

  // User endpoints
  async getCurrentUser(): Promise<ApiResponse<User>> {
    return this.request('/users/me');
  }

  // Pet endpoints
  async getMyPets(params?: PaginationParams): Promise<ApiResponse<PageResponse<Pet>>> {
    const query = params ? `?${new URLSearchParams(params as Record<string, string>)}` : '';
    return this.request(`/pets${query}`);
  }

  async getPet(id: number): Promise<ApiResponse<Pet>> {
    return this.request(`/pets/${id}`);
  }

  async createPet(data: Partial<Pet>): Promise<ApiResponse<Pet>> {
    return this.request('/pets', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async updatePet(id: number, data: Partial<Pet>): Promise<ApiResponse<Pet>> {
    return this.request(`/pets/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  async deletePet(id: number): Promise<ApiResponse<void>> {
    return this.request(`/pets/${id}`, { method: 'DELETE' });
  }

  // Product endpoints
  async getProducts(params?: Record<string, any>): Promise<ApiResponse<PageResponse<Product>>> {
    const query = params ? `?${new URLSearchParams(params)}` : '';
    return this.request(`/products${query}`);
  }

  async getProduct(id: number): Promise<ApiResponse<Product>> {
    return this.request(`/products/${id}`);
  }

  // Order endpoints
  async getMyOrders(params?: PaginationParams): Promise<ApiResponse<PageResponse<Order>>> {
    const query = params ? `?${new URLSearchParams(params as Record<string, string>)}` : '';
    return this.request(`/orders${query}`);
  }

  async getOrder(id: number): Promise<ApiResponse<Order>> {
    return this.request(`/orders/${id}`);
  }

  async checkout(data: { storeId: number; paymentMethod: string; voucherCode?: string }): Promise<ApiResponse<Order & { paymentUrl?: string }>> {
    return this.request('/orders/checkout', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  // Cart endpoints
  async getCart(): Promise<ApiResponse<Cart>> {
    return this.request('/cart');
  }

  async addToCart(data: { productId: number; quantity: number }): Promise<ApiResponse<void>> {
    return this.request('/cart/items', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async updateCartItem(id: number, quantity: number): Promise<ApiResponse<void>> {
    return this.request(`/cart/items/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ quantity }),
    });
  }

  async removeFromCart(id: number): Promise<ApiResponse<void>> {
    return this.request(`/cart/items/${id}`, { method: 'DELETE' });
  }

  // Appointment endpoints
  async getAppointments(params?: Record<string, any>): Promise<ApiResponse<PageResponse<Appointment>>> {
    const query = params ? `?${new URLSearchParams(params)}` : '';
    return this.request(`/appointments${query}`);
  }

  async getAppointment(id: number): Promise<ApiResponse<Appointment>> {
    return this.request(`/appointments/${id}`);
  }

  async bookAppointment(data: {
    petId: number;
    storeId: number;
    serviceId: number;
    scheduledAt: string;
    notes?: string;
  }): Promise<ApiResponse<Appointment>> {
    return this.request('/appointments', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async rescheduleAppointment(id: number, newScheduledAt: string): Promise<ApiResponse<Appointment>> {
    return this.request(`/appointments/${id}/reschedule`, {
      method: 'POST',
      body: JSON.stringify({ newScheduledAt }),
    });
  }

  async cancelAppointment(id: number, reason?: string): Promise<ApiResponse<void>> {
    return this.request(`/appointments/${id}/cancel`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  }

  // Services
  async getServices(params?: Record<string, any>): Promise<ApiResponse<PageResponse<Service>>> {
    const query = params ? `?${new URLSearchParams(params)}` : '';
    return this.request(`/services${query}`);
  }

  // Invoices
  async getInvoices(params?: PaginationParams): Promise<ApiResponse<PageResponse<Invoice>>> {
    const query = params ? `?${new URLSearchParams(params as Record<string, string>)}` : '';
    return this.request(`/invoices${query}`);
  }

  async getInvoice(id: number): Promise<ApiResponse<Invoice>> {
    return this.request(`/invoices/${id}`);
  }

  // Notifications
  async getNotifications(params?: PaginationParams): Promise<ApiResponse<PageResponse<Notification>>> {
    const query = params ? `?${new URLSearchParams(params as Record<string, string>)}` : '';
    return this.request(`/notifications${query}`);
  }

  async markNotificationRead(id: number): Promise<ApiResponse<void>> {
    return this.request(`/notifications/${id}/read`, { method: 'PATCH' });
  }

  // Vouchers
  async validateVoucher(code: string, orderTotal: number): Promise<ApiResponse<Voucher>> {
    return this.request('/vouchers/validate', {
      method: 'POST',
      body: JSON.stringify({ code, orderTotal }),
    });
  }

  // Payment
  async makePayment(orderId: number, amount: number, method: string): Promise<ApiResponse<Payment & { paymentUrl?: string }>> {
    return this.request('/payments', {
      method: 'POST',
      body: JSON.stringify({ orderId, amount, method }),
    });
  }

  // Stores
  async getStores(params?: Record<string, any>): Promise<ApiResponse<PageResponse<any>>> {
    const query = params ? `?${new URLSearchParams(params)}` : '';
    return this.request(`/stores${query}`);
  }
}

// Export singleton instance
export const apiClient = new ApiClient(API_BASE_URL);
export default apiClient;
