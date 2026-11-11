// AI API — docs/api/ai-v1.md #1–13 (UC90–UC94). BE đã cài (T77a–T78), FE ghép thật ở T82 (11/11/2026); mock ai.mock.ts chỉ dùng khi VITE_API_MOCK=true.
// Chatbot luôn 200 với mọi kết quả nghiệp vụ (giao diện rẽ theo `result`); chỉ đầu vào sai là 400 và một IP gọi quá nhiều là 429 (BR-AI-09).
// Khách đăng nhập gọi `askAsCustomer` (#2, CHỈ CUSTOMER), chưa đăng nhập gọi `ask` (#1, token gửi kèm bị bỏ qua nên không cá nhân hóa).
// Tri thức (#5–11) và hồ sơ gợi ý (#12–13) chỉ SUPER_MANAGER.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  AiDocument,
  AiDocumentQuery,
  AiDocumentSummary,
  ChatRequest,
  ChatResponse,
  CreateAiDocumentRequest,
  ItemProfile,
  ItemProfileQuery,
  ItemProfileRow,
  ItemType,
  RecommendationQuery,
  RecommendationResponse,
  UpdateAiDocumentRequest,
  VaccinationSummary,
} from '../types/ai';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const aiApi = {
  /** #1 Public. 400 câu hỏi rỗng / quá dài / quá 3 câu hỏi trước; 429 một IP quá nhiều lần (header `Retry-After`). */
  ask: (body: ChatRequest) => post<ChatResponse>('/api/public/ai/chat', body),
  /** #2 CUSTOMER. Như #1, thêm PERSONAL / NEED_PET_CHOICE; 403 khi không phải CUSTOMER. */
  askAsCustomer: (body: ChatRequest) => post<ChatResponse>('/api/me/ai/chat', body),
  /** #3 CUSTOMER, thú của mình. 400 BR-KH-05 thú đã mất; 404 thú của người khác (ADR-0006). */
  getVaccinationSummary: (petId: number) => get<VaccinationSummary>(`/api/me/ai/pets/${petId}/vaccination-summary`),
  /** #4 CUSTOMER, thú của mình. 400 BR-KH-05 thú đã mất / `limit` ngoài 1–12; 404 thú của người khác. */
  getRecommendations: (petId: number, query: RecommendationQuery = {}) =>
    get<RecommendationResponse>(`/api/me/ai/pets/${petId}/recommendations`, query),

  /** #5 SUPER_MANAGER. Mới sửa trước. */
  listDocuments: (query: AiDocumentQuery = {}) => get<PageResponse<AiDocumentSummary>>('/api/ai/documents', query),
  /** #6 → 201, DRAFT. 400 BR-AI-14 tiêu đề rỗng / quá 200 ký tự, nội dung quá 20 000 ký tự. */
  createDocument: (body: CreateAiDocumentRequest) => post<AiDocument>('/api/ai/documents', body),
  /** #7 */
  getDocument: (documentId: number) => get<AiDocument>(`/api/ai/documents/${documentId}`),
  /** #8 400 BR-AI-13 tài liệu CATALOG_PROTOCOL; 409 tài liệu ARCHIVED. Sửa nội dung tài liệu PUBLISHED tăng `version`. */
  updateDocument: (documentId: number, body: UpdateAiDocumentRequest) => patch<AiDocument>(`/api/ai/documents/${documentId}`, body),
  /** #9 DRAFT / ARCHIVED → PUBLISHED. 400 BR-AI-14 thiếu nguồn / người rà soát / nội dung; 409 INVALID_STATE_TRANSITION. */
  publishDocument: (documentId: number) => post<AiDocument>(`/api/ai/documents/${documentId}/publish`),
  /** #10 PUBLISHED / DRAFT → ARCHIVED, có hiệu lực ngay. 409 INVALID_STATE_TRANSITION. */
  archiveDocument: (documentId: number) => post<AiDocument>(`/api/ai/documents/${documentId}/archive`),
  /** #11 → 202, `indexStatus = PENDING` (idempotent). 409 khi không PUBLISHED. */
  reindexDocument: (documentId: number) => post<AiDocument>(`/api/ai/documents/${documentId}/reindex`),

  /** #12 SUPER_MANAGER. Chỉ dịch vụ thẩm mỹ và sản phẩm GOODS không kê đơn đang kinh doanh. */
  listItemProfiles: (query: ItemProfileQuery = {}) => get<PageResponse<ItemProfileRow>>('/api/ai/item-profiles', query),
  /** #13 Ghi đè toàn bộ; mọi trường rỗng = xóa hồ sơ. 400 BR-AI-18 mục là dịch vụ khám / tiêm, thuốc, vaccine, sản phẩm kê đơn; 404 không có mục. */
  saveItemProfile: (itemType: ItemType, itemId: number, body: ItemProfile) =>
    put<ItemProfileRow>(`/api/ai/item-profiles/${itemType}/${itemId}`, body),
};
