// AI (tầng 3) — docs/api/ai-v1.md, openapi/ai-v1.yaml (13 endpoint, UC90–UC94). Ngày 'YYYY-MM-DD' (giờ Việt Nam), thời điểm ISO có offset.

/** Kết quả của một lượt hỏi đáp; giao diện rẽ theo giá trị này. PERSONAL / NEED_PET_CHOICE chỉ ở `/me/ai/chat`, NEED_LOGIN chỉ ở `/public/ai/chat`. */
export type ChatResult =
  | 'ANSWERED'
  | 'OUT_OF_SCOPE'
  | 'MEDICAL_REFUSAL'
  | 'EMERGENCY'
  | 'UNAVAILABLE'
  | 'PERSONAL'
  | 'NEED_PET_CHOICE'
  | 'NEED_LOGIN';
export type VaccinationStatus = 'NOT_YET_ELIGIBLE' | 'NOT_STARTED' | 'UP_TO_DATE' | 'DUE_SOON' | 'OVERDUE';
export type RecoGroup = 'GROOMING' | 'BOARDING' | 'PRODUCT';
export type ItemType = 'SERVICE' | 'PRODUCT';
export type DocumentSource = 'MANUAL' | 'CATALOG_PROTOCOL';
export type DocumentTopic = 'CARE' | 'NUTRITION' | 'BEHAVIOR' | 'HYGIENE' | 'VACCINATION' | 'CLINIC_INFO';
export type DocumentSpecies = 'DOG' | 'CAT' | 'OTHER' | 'ALL';
/** FSM #13. */
export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type IndexStatus = 'PENDING' | 'INDEXED' | 'FAILED';
export type ProfileSpecies = 'DOG' | 'CAT' | 'OTHER';

export const DOCUMENT_TOPICS: readonly DocumentTopic[] = ['CARE', 'NUTRITION', 'BEHAVIOR', 'HYGIENE', 'VACCINATION', 'CLINIC_INFO'];
export const DOCUMENT_SPECIES: readonly DocumentSpecies[] = ['DOG', 'CAT', 'OTHER', 'ALL'];
export const DOCUMENT_STATUSES: readonly DocumentStatus[] = ['DRAFT', 'PUBLISHED', 'ARCHIVED'];
export const RECO_GROUPS: readonly RecoGroup[] = ['GROOMING', 'BOARDING', 'PRODUCT'];
export const ITEM_TYPES: readonly ItemType[] = ['SERVICE', 'PRODUCT'];
export const PROFILE_SPECIES: readonly ProfileSpecies[] = ['DOG', 'CAT', 'OTHER'];

/** Giới hạn của hợp đồng (độ dài tối đa); `question` và `previousQuestions` là mặc định của [CFG] `ai.chat.*` (BR-AI-08). */
export const AI_LIMITS = {
  question: 500,
  previousQuestions: 3,
  title: 200,
  content: 20_000,
  sourceNote: 500,
  reviewerName: 100,
  breedKeyword: 100,
  recommendationLimit: { min: 1, max: 12 },
} as const;

/** BR-AI-25: khung chatbot LUÔN hiện thông báo này khi còn chạy gói free của nhà cung cấp (hợp đồng không có trường cho nó). */
export const AI_PROVIDER_NOTICE =
  'Câu hỏi được gửi tới nhà cung cấp AI (Google) và có thể được dùng để cải thiện sản phẩm; đừng nhập thông tin cá nhân (kể cả tên thú).';

// ─── Chatbot (#1, #2) ───

export interface ChatRequest {
  question: string;
  /** Tối đa 3 câu hỏi trước CỦA CHÍNH người dùng; không có chỗ cho câu trả lời của trợ lý (BR-AI-08). */
  previousQuestions?: string[] | null;
}
export interface ChatSource {
  documentId: number;
  title: string;
}
export interface EmergencyBranch {
  branchId: number;
  name: string;
  address: string;
  phone: string;
}
export interface PetChoice {
  petId: number;
  name: string;
}
export interface ChatResponse {
  result: ChatResult;
  /** Câu trả lời hoặc câu mẫu cố định. */
  answer: string;
  /** Tối đa 3 tài liệu; rỗng khi không ANSWERED. */
  sources: ChatSource[];
  /** Có với ANSWERED (BR-AI-05). */
  disclaimer?: string | null;
  /** true thì giao diện hiện nút đặt lịch khám (UC39). */
  bookingHint: boolean;
  /** Chỉ có với EMERGENCY (BR-AI-03). */
  emergencyBranches: EmergencyBranch[];
  /** Chỉ có với NEED_PET_CHOICE: giao diện cho chọn rồi gọi `getVaccinationSummary` (BR-AI-27). */
  pets: PetChoice[];
}

// ─── Trợ lý theo thú (#3, #4) ───

export interface VaccinationItem {
  vaccineTypeId: number;
  vaccineName: string;
  status: VaccinationStatus;
  lastDoseNumber?: number | null;
  lastAdministeredOn?: string | null;
  nextDoseNumber?: number | null;
  nextDueDate?: string | null;
  /** Tuổi tối thiểu (tuần) của mũi 1, có với NOT_YET_ELIGIBLE / NOT_STARTED. */
  firstDoseFromAgeWeeks?: number | null;
}
export interface VaccinationSummary {
  petId: number;
  petName: string;
  items: VaccinationItem[];
  /** Câu hiển thị trong khung chat, sinh từ mẫu cố định, không qua LLM (BR-AI-11). */
  message: string;
  disclaimer: string;
}

export interface RecommendationQuery {
  /** Vắng = cả ba nhóm. */
  group?: RecoGroup;
  /** 1–12; vắng = `ai.reco.max_per_group` mỗi nhóm. */
  limit?: number;
}
export interface RecommendedItem {
  group: RecoGroup;
  itemType: ItemType;
  itemId: number;
  name: string;
  /** VND; loại chuồng là giá theo đêm. */
  price: number;
  /** BR-CK-02: hiện "Từ X đ". */
  priceIsFrom: boolean;
  /** `đêm` với BOARDING. */
  priceUnit?: string | null;
  imageUrl?: string | null;
  /** 0–100 (BR-AI-21). */
  score: number;
  /** Sinh từ mẫu, không dùng LLM (BR-AI-22). */
  reasons: string[];
}
export interface RecommendationResponse {
  petId: number;
  generatedAt: string;
  /** Sắp theo nhóm rồi điểm giảm dần. */
  items: RecommendedItem[];
}

// ─── Tri thức (#5–#11) ───

export interface AiDocumentQuery {
  status?: DocumentStatus;
  topic?: DocumentTopic;
  species?: DocumentSpecies;
  /** Tìm theo tiêu đề. */
  q?: string;
  page?: number;
  size?: number;
}
export interface AiDocumentSummary {
  documentId: number;
  sourceType: DocumentSource;
  title: string;
  topic: DocumentTopic;
  species: DocumentSpecies;
  status: DocumentStatus;
  version: number;
  indexStatus: IndexStatus;
  updatedAt: string;
}
export interface AiDocument extends AiDocumentSummary {
  /** Markdown ≤ 20 000 ký tự. */
  content: string;
  /** Lớp 1 — nguồn (BR-AI-14). */
  sourceNote?: string | null;
  /** Lớp 2 — bác sĩ thú y rà soát. */
  reviewerName?: string | null;
  /** Ngày rà soát. */
  reviewedOn?: string | null;
  /** 0 = chưa nạp. */
  indexedVersion: number;
  indexError?: string | null;
  chunkCount: number;
  publishedAt?: string | null;
}
export interface CreateAiDocumentRequest {
  title: string;
  topic: DocumentTopic;
  species: DocumentSpecies;
  content: string;
  sourceNote?: string | null;
  reviewerName?: string | null;
  reviewedOn?: string | null;
}
export type UpdateAiDocumentRequest = Partial<CreateAiDocumentRequest>;

// ─── Hồ sơ gợi ý (#12, #13) ───

export interface ItemProfileQuery {
  itemType?: ItemType;
  /** true = chỉ mục chưa có hồ sơ. */
  missingOnly?: boolean;
  /** Tìm theo tên. */
  q?: string;
  page?: number;
  size?: number;
}
export interface ItemProfile {
  /** Vắng = mọi loài. */
  species?: ProfileSpecies | null;
  minAgeWeeks?: number | null;
  maxAgeWeeks?: number | null;
  minWeightKg?: number | null;
  maxWeightKg?: number | null;
  /** Từ khóa giống viết thường không dấu; rỗng = mọi giống. */
  breedKeywords: string[];
  /** Chu kỳ dùng lại (ngày); vắng = mặc định [CFG]. */
  repeatIntervalDays?: number | null;
}
export interface ItemProfileRow {
  itemType: ItemType;
  itemId: number;
  name: string;
  group: RecoGroup;
  hasProfile: boolean;
  profile?: ItemProfile | null;
}
