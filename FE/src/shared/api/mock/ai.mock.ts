// Mock ai-v1 (tầng 3, UC90–UC94; docs/api/ai-v1.md #1–13, openapi/ai-v1.yaml). BE tầng 3 CHƯA cài nên không có số đo thật: mock chép hợp đồng, 07-ai-subsystem.md (BR-AI-01…28) và RateLimitFilter / RateLimitProperties
// đã có trong BE (BR-AI-09). Chatbot KHÔNG gọi Gemini: tìm "đoạn" bằng độ phủ từ khóa trên chính các tài liệu PUBLISHED trong state, nên lưu trữ / xuất bản / sửa tài liệu ở UC93 đổi câu trả lời của chatbot như BE
// (BR-AI-16). Chỗ hợp đồng IM LẶNG hoặc mock đơn giản hóa so với BE là ASSUMPTION:
//  A1  Thứ tự kiểm, theo brief T57 cho cả 13 endpoint: 401 (adapter) → 400 sai kiểu path / query / body (MALFORMED_REQUEST) → 403 vai trò → 400 VALIDATION_FAILED → 404 → 400 BR-… → 409. KHÁC các mock GĐ1 (care, customer-pet…)
//      đặt kiểm @Valid của body TRƯỚC vai trò; mock này đặt SAU vai trò. Riêng hai endpoint chat, giới hạn tần suất theo IP (BR-AI-09) đứng ĐẦU TIÊN, trước cả vai trò và kiểu body, vì RateLimitFilter của BE chạy trước
//      JwtAuthenticationFilter và controller (nên request bị 403 vẫn tính vào hạn mức). Không mô phỏng được: 429 đến trước 401 (adapter của mock-api trả 401 trước handler) và header `Retry-After` (MockReply không có headers,
//      số giây chỉ nằm trong câu báo lỗi); BR-AI-17 audit (mock không export ghi audit).
//  A2  Giới hạn tần suất (BR-AI-09): 10 câu / phút rồi 60 câu / giờ cho MỖI IP, cả hai endpoint chat dùng chung; cửa sổ cố định bắt đầu ở lần gọi đầu, lần bị từ chối vẫn được tính, bị chặn ở cửa sổ phút thì không tiêu hao hạn mức
//      giờ (RateLimitFilter). Mock chỉ có MỘT "IP" (một trình duyệt). 429 TOO_MANY_REQUESTS, câu "Bạn thao tác quá nhanh, vui lòng thử lại sau N giây" (TooManyRequestsException).
//  A3  Hạn mức nhà cung cấp 20 lệnh sinh / ngày (07 §13 phát hiện 1: gói free `gemini-2.5-flash`, 429 `GenerateRequestsPerDayPerProjectPerModel-FreeTier`) KHÔNG phải [CFG] nên là hằng số `AI_DAILY_LLM_LIMIT`. Chỉ câu đi tới
//      bước LLM mới tốn một lệnh (cấp cứu, bệnh án, NEED_LOGIN, PERSONAL, NEED_PET_CHOICE và dưới ngưỡng τ thì không); hết hạn mức → UNAVAILABLE (BR-AI-10, error_kind PROVIDER_RATE_LIMIT) ở HTTP 200 cho tới ngày sau
//      (theo ngày Việt Nam; Google tính theo giờ Thái Bình Dương). Không mô phỏng TIMEOUT / PROVIDER_ERROR / NO_KEY (sẽ là cùng câu UNAVAILABLE).
//  A4  BR-AI-08: câu hỏi rỗng / quá `ai.chat.max_question_chars`, quá `ai.chat.max_previous_questions` câu hỏi trước, một câu hỏi trước quá giới hạn → 400 VALIDATION_FAILED (07 §4.1 ghi đúng mã này; yaml ghi "VALIDATION_FAILED / BR-AI-08").
//      Giới hạn đọc từ [CFG] của mock identity (mặc định 500 / 3), nên sửa ở màn "Tham số hệ thống" có hiệu lực ngay. Cùng cách đó cho `ai.retrieval.min_similarity`, `ai.personal.appointments_limit`,
//      `ai.reco.*`, `reminder.vaccine_days_before`. Mock không kiểm tổng ba trọng số = 1 (BE cũng không, V35).
//  A5  Truy hồi: độ tương đồng giả = 0,45 + 0,45 × (tỉ lệ từ khóa của câu hỏi có trong tài liệu, mỗi từ nặng theo idf trong kho tài liệu đã nạp); câu "gần chủ đề" (giá, mua thuốc, thuốc, liều… kèm từ chỉ thú) được coi là có độ tương đồng 0,62 như số đo T56
//      (0,58–0,67, phần lớn vượt τ). Chỉ tài liệu PUBLISHED có đoạn đã nạp (`served`, phiên bản `indexedVersion`) mới được tìm: nháp / lưu trữ không bao giờ; tài liệu FAILED chưa nạp lại thì trả lời theo bản cũ (BR-AI-16, tài liệu mẫu #8);
//      Nạp chỉ mục (ST22) chạy "nền": response của lệnh ghi còn PENDING, request AI kế tiếp đã thấy INDEXED (cửa sổ PENDING ngắn không quan sát được ở nơi khác). Tối đa 3 nguồn, cao nhất trước, và không vượt `ai.retrieval.top_k`.
//      Bước "LLM" quyết định theo từ khóa: liều thuốc / chẩn đoán → MEDICAL_REFUSAL (BR-AI-02, 06), giá / mua ở đâu / chèn chỉ thị → OUT_OF_SCOPE (inScope=false), còn lại ANSWERED bằng đoạn trích tài liệu.
//  A6  Tên thú ở `/me` bị thay bằng `[thú]` mọi lần xuất hiện (BR-AI-12b theo văn bản đã duyệt); đề xuất T56 (07 §13 phát hiện 2: tên thông dụng chỉ che khi đi sau "bé", "con"…) CHƯA áp dụng. Bằng chứng che dữ liệu:
//      `aiProviderLog()` (như lớp giả LlmClient / EmbeddingClient của test BE) ghi đúng chuỗi "nhà cung cấp" nhận được, và chỉ ghi khi câu đi tới bước nhúng.
//  A7  Thông báo "câu hỏi gửi tới Google" (BR-AI-25) là việc của giao diện: hợp đồng không có trường nên chỉ export `AI_PROVIDER_NOTICE` (types/ai.ts).
//  A8  Tài liệu: sửa tiêu đề / nội dung tài liệu PUBLISHED tăng `version` (tiêu đề nằm trong văn bản đem nhúng, BR-AI-15), sửa nháp thì không; sửa NỘI DUNG tài liệu PUBLISHED bắt buộc gửi kèm `reviewerName` và `reviewedOn`
//      (BR-AI-14: "phải cập nhật lại người và ngày rà soát"), xóa nguồn / người rà soát của tài liệu PUBLISHED → 400 BR-AI-14. `PATCH` không có gì đổi thì trả nguyên văn, không tăng version. CATALOG_PROTOCOL: PATCH và xuất bản lại
//      từ ARCHIVED → 400 BR-AI-13 (bảng FSM chỉ cho MANUAL xuất bản tay; ST22 mới dựng lại). Tạo: tiêu đề rỗng / quá 200 ký tự, nội dung quá 20 000 ký tự là BR-AI-14 (hợp đồng .md), thiếu `topic` / `species` / `content` và
//      `sourceNote` > 500, `reviewerName` > 100 là VALIDATION_FAILED. Xuất bản thiếu gì thì message liệt kê đủ. `reindex` luôn 202 + PENDING với tài liệu PUBLISHED (idempotent), không đụng `updatedAt`.
//  A9  Thú: cân nặng, giống, ngày sinh, thú còn sống lấy từ `GET /api/me/pets` của chính khách (khách chờ liên kết hồ sơ BR-TK-19 bị 400 thì coi như chưa có thú). Mũi tiêm đọc `GET /api/pets/:id/health-record` (mũi mẫu + mũi do
//      mock lâm sàng ghi). `nextDoseNumber` = mũi kế trong phác đồ, hết phác đồ thì lặp lại mũi cuối (nhắc lại hằng năm, cùng cách recordDoseForMock). Ngày "hôm nay" là `vnDate()`.
//  A10 Gợi ý: lịch sử dịch vụ lấy từ lượt khám COMPLETED của mock tiếp nhận, sản phẩm từ Order PAID của mock bán hàng, lưu trú từ đặt chỗ CHECKED_IN / OVERDUE / CHECKED_OUT của mock lưu trú; độ phổ biến P là số lượt 90 ngày
//      TOÀN CHUỖI cố định (SEED_POPULARITY), không tách theo (loài, nhóm tuổi) như 07 §7.3. Điều kiện ứng viên (BR-AI-19): dịch vụ đang kinh doanh và bật ở ≥ 1 chi nhánh ACTIVE; sản phẩm GOODS không kê đơn đang kinh doanh còn
//      tồn khả dụng > 0 ở ≥ 1 chi nhánh ACTIVE; loại chuồng đúng loài, đủ sức chứa cân nặng và (như dịch vụ) bật ở ≥ 1 chi nhánh. Không cache (BR-AI-23 cho phép).
//  A11 Hồ sơ gợi ý: danh sách sắp dịch vụ trước sản phẩm, rồi id tăng dần (hợp đồng không nêu thứ tự); từ khóa giống được chuẩn hóa chữ thường không dấu, bỏ trùng; hồ sơ toàn trường rỗng = xóa (`hasProfile` false);
//      mục ngừng kinh doanh vẫn ghi được hồ sơ nếu tồn tại. Dịch vụ khám / tiêm, loại chuồng, thuốc, vaccine, sản phẩm kê đơn → 400 BR-AI-18.
import type {
  AiDocument,
  AiDocumentSummary,
  ChatResponse,
  ChatResult,
  ChatSource,
  DocumentSpecies,
  DocumentStatus,
  DocumentTopic,
  EmergencyBranch,
  ItemProfile,
  ItemProfileRow,
  ItemType,
  PetChoice,
  ProfileSpecies,
  RecoGroup,
  RecommendedItem,
  RecommendationResponse,
  VaccinationItem,
  VaccinationStatus,
  VaccinationSummary,
} from '../../types/ai';
import { AI_LIMITS, DOCUMENT_SPECIES, DOCUMENT_STATUSES, DOCUMENT_TOPICS, ITEM_TYPES, PROFILE_SPECIES, RECO_GROUPS } from '../../types/ai';
import type { Appointment } from '../../types/appointment';
import type { Booking } from '../../types/boarding';
import { SPECIES_LABELS, type Species, type VaccinationProtocol, type VaccineType } from '../../types/catalog';
import type { PetResponse, VaccinationRecord } from '../../../domains/pets/types';
import { foldVi } from '../../utils/text';
import { vnDate } from '../../utils/vn-time';
import { chunkCountOf, seedManualDocuments, SEED_POPULARITY, SEED_PROFILES, type DocRow } from './ai.seed';
import { appointmentRoutes } from './appointment.mock';
import { boardingRoutes } from './boarding.mock';
import { activeBranchSummaries, branchBooking } from './branch.mock';
import { catalogProducts, catalogProtocols, catalogServices, catalogVaccineTypes } from './catalog.mock';
import { customerPetRoutes } from './customer-pet.mock';
import { denied, fail, has, invalid, notFound, ok, optional, ruleFail, tooLong, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { SEED_CONFIGS } from './identity-admin.seed';
import { load as loadIdentity } from './identity.mock';
import { availableQuantity } from './inventory.mock';
import { clampPaging, pageOf, typeError } from './public.mock';
import { allOrderRows, serviceIdsOfVisit } from './sales.mock';
import { completedVisitsOfPet } from './visit.mock';
// Nạp mock lâm sàng để mũi tiêm do nó ghi hiện trong `GET /api/pets/:id/health-record` (nó đăng ký nguồn lúc nạp, customer-pet.mock.ts `registerHealthSource`).
import './visit-clinical.mock';

/** Hạn mức lệnh sinh mỗi ngày của gói free (A3). */
export const AI_DAILY_LLM_LIMIT = 20;
/** BR-AI-09 (khởi điểm của `app.rate-limit.ai` và `ai-hour`). */
export const AI_RATE_LIMITS = { minute: { limit: 10, windowMs: 60_000 }, hour: { limit: 60, windowMs: 3_600_000 } } as const;

/** Một lần "gọi nhà cung cấp" (A6): chuỗi đã che; `llm` = có tới bước sinh câu trả lời hay mới dừng ở nhúng. */
export interface ProviderCall {
  question: string;
  previousQuestions: string[];
  llm: boolean;
}

interface CountWindow {
  start: number;
  count: number;
}
interface State {
  nextDocumentId: number;
  documents: DocRow[];
  /** `SERVICE:<id>` / `PRODUCT:<id>` → hồ sơ (`ai_item_profiles`). */
  profiles: Record<string, ItemProfile>;
  /** Lệnh sinh đã dùng trong ngày (A3). */
  llm: { date: string; count: number };
  limiter: { minute: CountWindow; hour: CountWindow };
  provider: ProviderCall[];
}

const STATE_KEY = 'petcare-mock-ai';
const DAY = 86_400_000;
const nowIso = () => new Date().toISOString();

/** [CFG] đang hiệu lực: giá trị admin đã sửa ở màn tham số (identity mock) hoặc mặc định của seed (V35…). */
export function aiConfig(key: string): number {
  const seed = SEED_CONFIGS.find((c) => c.key === key);
  if (!seed) throw new Error(`Không có tham số [CFG] ${key}`);
  return Number(loadIdentity().configOverrides?.[key] ?? seed.value);
}

// ─── State ───

/** BR-AI-13: mỗi loại vaccine đang hoạt động CÓ phác đồ một tài liệu, sinh từ danh mục, PUBLISHED + INDEXED, chỉ đọc. */
function catalogDocuments(firstId: number, now: number): DocRow[] {
  const protocols = catalogProtocols().filter((p) => p.isActive);
  return catalogVaccineTypes()
    .filter((t) => t.isActive && protocols.some((p) => p.vaccineTypeId === t.vaccineTypeId))
    .map((t, i): DocRow => {
      const species = SPECIES_LABELS[t.species].toLowerCase();
      const doses = protocols.filter((p) => p.vaccineTypeId === t.vaccineTypeId).sort((a, b) => a.doseNumber - b.doseNumber);
      const title = `Lịch tiêm ${t.name} cho ${species}`;
      const content = [
        `# ${title}`,
        '',
        ...doses.map(
          (p) => `- Mũi ${p.doseNumber}: từ ${p.minAgeWeeks} tuần tuổi; tiêm nhắc sau ${p.intervalDays} ngày${p.requiredForBoarding ? '; bắt buộc khi gửi lưu trú' : ''}.`,
        ),
      ].join('\n');
      const at = new Date(now - 30 * DAY).toISOString();
      return {
        documentId: firstId + i,
        sourceType: 'CATALOG_PROTOCOL',
        title,
        topic: 'VACCINATION',
        species: t.species,
        content,
        sourceNote: null,
        reviewerName: null,
        reviewedOn: null,
        status: 'PUBLISHED',
        version: 1,
        indexedVersion: 1,
        indexStatus: 'INDEXED',
        indexError: null,
        chunkCount: chunkCountOf(content),
        publishedAt: at,
        updatedAt: at,
        served: { title, content },
      };
    });
}

function seedDocuments(now: number): DocRow[] {
  const manual = seedManualDocuments(now);
  return [...manual, ...catalogDocuments(manual.length + 1, now)];
}

const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/** State lưu từ bản cũ / thiếu trường: bù mặc định, không nổ (brief T57). */
function normalizeDoc(d: Partial<DocRow>, fallbackId: number): DocRow {
  const title = typeof d.title === 'string' ? d.title : 'Tài liệu';
  const content = typeof d.content === 'string' ? d.content : '';
  const status: DocumentStatus = DOCUMENT_STATUSES.includes(d.status as DocumentStatus) ? (d.status as DocumentStatus) : 'DRAFT';
  const indexedVersion = typeof d.indexedVersion === 'number' ? d.indexedVersion : status === 'DRAFT' ? 0 : 1;
  return {
    documentId: typeof d.documentId === 'number' ? d.documentId : fallbackId,
    sourceType: d.sourceType === 'CATALOG_PROTOCOL' ? 'CATALOG_PROTOCOL' : 'MANUAL',
    title,
    topic: DOCUMENT_TOPICS.includes(d.topic as DocumentTopic) ? (d.topic as DocumentTopic) : 'CARE',
    species: DOCUMENT_SPECIES.includes(d.species as DocumentSpecies) ? (d.species as DocumentSpecies) : 'ALL',
    content,
    sourceNote: d.sourceNote ?? null,
    reviewerName: d.reviewerName ?? null,
    reviewedOn: d.reviewedOn ?? null,
    status,
    version: typeof d.version === 'number' ? d.version : 1,
    indexedVersion,
    indexStatus: d.indexStatus === 'INDEXED' || d.indexStatus === 'FAILED' ? d.indexStatus : 'PENDING',
    indexError: d.indexError ?? null,
    chunkCount: typeof d.chunkCount === 'number' ? d.chunkCount : chunkCountOf(content),
    publishedAt: d.publishedAt ?? null,
    updatedAt: typeof d.updatedAt === 'string' ? d.updatedAt : nowIso(),
    served: d.served ?? (indexedVersion > 0 ? { title, content } : null),
  };
}

/** ST22 chạy "nền" (A5): tài liệu PUBLISHED đang PENDING được nạp ở lần đọc kế tiếp; đoạn cũ bị thay đúng lúc `indexedVersion` đổi (BR-AI-16). */
function settleIndexing(s: State): boolean {
  let changed = false;
  for (const d of s.documents) {
    if (d.status === 'PUBLISHED' && d.indexStatus === 'PENDING') {
      d.served = { title: d.title, content: d.content };
      d.indexedVersion = d.version;
      d.indexStatus = 'INDEXED';
      d.indexError = null;
      d.chunkCount = chunkCountOf(d.content);
      changed = true;
    }
  }
  return changed;
}

const freshWindow = (now: number): CountWindow => ({ start: now, count: 0 });
const validWindow = (w: unknown): w is CountWindow => isObject(w) && typeof w.start === 'number' && typeof w.count === 'number';

function load(now = Date.now()): State {
  let stored: Partial<State> | null = null;
  try {
    const raw = localStorage.getItem(STATE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    if (isObject(parsed)) stored = parsed as Partial<State>;
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const today = vnDate(now);
  const documents = Array.isArray(stored?.documents) ? stored.documents.map((d, i) => normalizeDoc(d ?? {}, i + 1)) : seedDocuments(now);
  const s: State = {
    nextDocumentId: Math.max(typeof stored?.nextDocumentId === 'number' ? stored.nextDocumentId : 0, ...documents.map((d) => d.documentId + 1), 1),
    documents,
    profiles: isObject(stored?.profiles) ? (stored.profiles as Record<string, ItemProfile>) : { ...SEED_PROFILES },
    llm: stored?.llm?.date === today && typeof stored.llm.count === 'number' ? stored.llm : { date: today, count: 0 },
    limiter: {
      minute: validWindow(stored?.limiter?.minute) ? stored.limiter.minute : freshWindow(now),
      hour: validWindow(stored?.limiter?.hour) ? stored.limiter.hour : freshWindow(now),
    },
    provider: Array.isArray(stored?.provider) ? stored.provider : [],
  };
  // Ghi ngay lần đầu: thời điểm mẫu tính theo lúc tạo, đọc lại phải ra cùng giá trị (như care.mock).
  if (!stored?.documents || settleIndexing(s)) save(s);
  return s;
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

/** Những gì "nhà cung cấp AI" đã nhận, cũ trước (A6); cho test kiểm che dữ liệu BR-AI-12. */
export function aiProviderLog(): ProviderCall[] {
  return load().provider;
}

// ─── Kiểm dữ liệu vào ───

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const isSuperManager = (req: MockRequest) => req.account!.role === 'SUPER_MANAGER';

const optString = (v: unknown) => v === undefined || v === null || typeof v === 'string';
const optEnum = (v: unknown, values: readonly string[]) => v === undefined || v === null || (typeof v === 'string' && values.includes(v));
const optInt = (v: unknown) => v === undefined || v === null || (typeof v === 'number' && Number.isInteger(v));
const optNumber = (v: unknown) => v === undefined || v === null || (typeof v === 'number' && Number.isFinite(v));
const optDate = (v: unknown) => {
  if (v === undefined || v === null) return true;
  const m = typeof v === 'string' ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(v) : null;
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
};
const isBlank = (v: unknown) => typeof v !== 'string' || v.trim() === '';

// ─── Tài liệu tri thức (#5–#11, UC93, FSM #13) ───

const summaryOf = (d: DocRow): AiDocumentSummary => ({
  documentId: d.documentId,
  sourceType: d.sourceType,
  title: d.title,
  topic: d.topic,
  species: d.species,
  status: d.status,
  version: d.version,
  indexStatus: d.indexStatus,
  updatedAt: d.updatedAt,
});
const documentOf = ({ served: _served, ...d }: DocRow): AiDocument => d;
const newestFirst = (a: DocRow, b: DocRow) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt) || b.documentId - a.documentId;
const stateError = (from: DocumentStatus, to: DocumentStatus) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái tài liệu tri thức từ ${from} sang ${to}`, 409);

/** Gác chung #7–#11: id sai kiểu / thân sai kiểu (400 MALFORMED) → vai trò → kiểm VALIDATION_FAILED → tài liệu (404). `shape` chạy trước vai trò, `validate` sau vai trò (A1). */
function withDocument(
  handler: (req: MockRequest, s: State, doc: DocRow) => MockReply,
  checks: { shape?: (req: MockRequest) => MockReply | null; validate?: (req: MockRequest) => MockReply | null } = {},
): MockHandler {
  return (req) => {
    const early = typeError(req.params, ['documentId']) ?? checks.shape?.(req) ?? null;
    if (early) return early;
    if (!isSuperManager(req)) return denied();
    const bad = checks.validate?.(req) ?? null;
    if (bad) return bad;
    const s = load();
    const doc = s.documents.find((d) => d.documentId === Number(req.params.documentId));
    return doc ? handler(req, s, doc) : notFound('tài liệu tri thức', req.params.documentId);
  };
}

const listDocuments: MockHandler = (req) => {
  const { status, topic, species, q } = req.query;
  if (status !== undefined && !DOCUMENT_STATUSES.includes(status as DocumentStatus)) return badParam('status');
  if (topic !== undefined && !DOCUMENT_TOPICS.includes(topic as DocumentTopic)) return badParam('topic');
  if (species !== undefined && !DOCUMENT_SPECIES.includes(species as DocumentSpecies)) return badParam('species');
  const badPaging = typeError(req.query, ['page', 'size']);
  if (badPaging) return badPaging;
  if (!isSuperManager(req)) return denied();
  const needle = q?.trim() ? foldVi(q.trim()) : null;
  const found = load()
    .documents.filter(
      (d) =>
        (!status || d.status === status) &&
        (!topic || d.topic === topic) &&
        (!species || d.species === species) &&
        (!needle || foldVi(d.title).includes(needle)),
    )
    .sort(newestFirst);
  return ok(pageOf(found.map(summaryOf), clampPaging(req.query)));
};

/** Thân #6 / #8 đúng kiểu JSON (Jackson): chuỗi, enum, ngày. */
function documentBodyTypesOk(b: Record<string, unknown>): boolean {
  return (
    optString(b.title) &&
    optString(b.content) &&
    optString(b.sourceNote) &&
    optString(b.reviewerName) &&
    optEnum(b.topic, DOCUMENT_TOPICS) &&
    optEnum(b.species, DOCUMENT_SPECIES) &&
    optDate(b.reviewedOn)
  );
}

const lengthChecks = (b: Record<string, unknown>) =>
  invalid([
    ['sourceNote', `Ghi chú nguồn tối đa ${AI_LIMITS.sourceNote} ký tự`, tooLong(b.sourceNote, AI_LIMITS.sourceNote)],
    ['reviewerName', `Tên người rà soát tối đa ${AI_LIMITS.reviewerName} ký tự`, tooLong(b.reviewerName, AI_LIMITS.reviewerName)],
  ]);

const createDocument: MockHandler = (req) => {
  const b = req.body;
  if (!isObject(b) || !documentBodyTypesOk(b)) return malformed();
  if (!isSuperManager(req)) return denied();
  const bad = invalid([
    ['topic', 'Vui lòng chọn chủ đề', !has(b.topic)],
    ['species', 'Vui lòng chọn loài áp dụng', !has(b.species)],
    ['content', 'Vui lòng nhập nội dung', !has(b.content)],
    ['sourceNote', `Ghi chú nguồn tối đa ${AI_LIMITS.sourceNote} ký tự`, tooLong(b.sourceNote, AI_LIMITS.sourceNote)],
    ['reviewerName', `Tên người rà soát tối đa ${AI_LIMITS.reviewerName} ký tự`, tooLong(b.reviewerName, AI_LIMITS.reviewerName)],
  ]);
  if (bad) return bad;
  if (isBlank(b.title)) return ruleFail('BR-AI-14', 'Tiêu đề không được để trống');
  if ((b.title as string).trim().length > AI_LIMITS.title) return ruleFail('BR-AI-14', `Tiêu đề tối đa ${AI_LIMITS.title} ký tự`);
  if ((b.content as string).length > AI_LIMITS.content) return ruleFail('BR-AI-14', 'Nội dung tối đa 20 000 ký tự');

  const s = load();
  const at = nowIso();
  const doc: DocRow = {
    documentId: s.nextDocumentId++,
    sourceType: 'MANUAL',
    title: (b.title as string).trim(),
    topic: b.topic as DocumentTopic,
    species: b.species as DocumentSpecies,
    content: b.content as string,
    sourceNote: optional(b.sourceNote),
    reviewerName: optional(b.reviewerName),
    reviewedOn: (b.reviewedOn as string | null | undefined) ?? null,
    status: 'DRAFT',
    version: 1,
    indexedVersion: 0,
    indexStatus: 'PENDING',
    indexError: null,
    chunkCount: 0,
    publishedAt: null,
    updatedAt: at,
    served: null,
  };
  s.documents.push(doc);
  save(s);
  return ok(documentOf(doc), 201, 'Đã tạo tài liệu nháp');
};

const getDocument = withDocument((_req, _s, doc) => ok(documentOf(doc)));

const updateDocument = withDocument(
  (req, s, doc) => {
    const b = req.body as Record<string, unknown>;
    if (doc.sourceType === 'CATALOG_PROTOCOL') return ruleFail('BR-AI-13', 'Tài liệu sinh từ phác đồ tiêm do hệ thống quản lý, không sửa tay');
    const next = {
      title: has(b.title) ? (b.title as string).trim() : doc.title,
      content: has(b.content) ? (b.content as string) : doc.content,
      topic: (b.topic as DocumentTopic | null | undefined) ?? doc.topic,
      species: (b.species as DocumentSpecies | null | undefined) ?? doc.species,
      sourceNote: has(b.sourceNote) ? optional(b.sourceNote) : doc.sourceNote,
      reviewerName: has(b.reviewerName) ? optional(b.reviewerName) : doc.reviewerName,
      reviewedOn: (b.reviewedOn as string | null | undefined) ?? doc.reviewedOn,
    };
    if (has(b.title) && next.title === '') return ruleFail('BR-AI-14', 'Tiêu đề không được để trống');
    if (next.title.length > AI_LIMITS.title) return ruleFail('BR-AI-14', `Tiêu đề tối đa ${AI_LIMITS.title} ký tự`);
    if (next.content.length > AI_LIMITS.content) return ruleFail('BR-AI-14', 'Nội dung tối đa 20 000 ký tự');
    const titleChanged = next.title !== doc.title;
    const contentChanged = next.content !== doc.content;
    if (doc.status === 'PUBLISHED') {
      if (isBlank(next.content)) return ruleFail('BR-AI-14', 'Tài liệu đã xuất bản không được để trống nội dung');
      if (!next.sourceNote) return ruleFail('BR-AI-14', 'Tài liệu đã xuất bản phải có ghi chú nguồn (lớp 1)');
      if (!next.reviewerName || !next.reviewedOn) return ruleFail('BR-AI-14', 'Tài liệu đã xuất bản phải có người và ngày rà soát (lớp 2)');
      if (contentChanged && !(has(b.reviewerName) && has(b.reviewedOn))) {
        return ruleFail('BR-AI-14', 'Sửa nội dung tài liệu đã xuất bản phải cập nhật lại người và ngày rà soát');
      }
    }
    if (doc.status === 'ARCHIVED') return fail('INVALID_STATE_TRANSITION', 'Tài liệu đã lưu trữ (ARCHIVED) không sửa được', 409);

    const changed = (Object.keys(next) as (keyof typeof next)[]).some((k) => next[k] !== doc[k]);
    if (!changed) return ok(documentOf(doc));
    Object.assign(doc, next, { updatedAt: nowIso() });
    // FSM #3: sửa tiêu đề / nội dung tài liệu PUBLISHED tăng version, đoạn cũ vẫn phục vụ tới khi ST22 nạp xong.
    if (doc.status === 'PUBLISHED' && (titleChanged || contentChanged)) {
      doc.version += 1;
      doc.indexStatus = 'PENDING';
      doc.indexError = null;
    }
    save(s);
    return ok(documentOf(doc), 200, 'Đã lưu tài liệu');
  },
  {
    shape: (req) => (isObject(req.body) && documentBodyTypesOk(req.body) ? null : malformed()),
    validate: (req) => lengthChecks(req.body as Record<string, unknown>),
  },
);

const publishDocument = withDocument((_req, s, doc) => {
  if (doc.sourceType === 'CATALOG_PROTOCOL') {
    if (doc.status !== 'PUBLISHED') return ruleFail('BR-AI-13', 'Tài liệu sinh từ phác đồ tiêm do hệ thống quản lý, không xuất bản tay');
  } else {
    const missing = [
      isBlank(doc.content) && 'nội dung',
      isBlank(doc.sourceNote) && 'ghi chú nguồn (lớp 1)',
      (isBlank(doc.reviewerName) || !doc.reviewedOn) && 'người rà soát và ngày rà soát (lớp 2)',
    ].filter(Boolean);
    if (missing.length) return ruleFail('BR-AI-14', `Không thể xuất bản, còn thiếu: ${missing.join('; ')}`);
  }
  if (doc.status === 'PUBLISHED') return stateError('PUBLISHED', 'PUBLISHED');
  const at = nowIso();
  Object.assign(doc, { status: 'PUBLISHED', indexStatus: 'PENDING', indexError: null, publishedAt: at, updatedAt: at });
  save(s);
  return ok(documentOf(doc), 200, 'Đã xuất bản tài liệu');
});

const archiveDocument = withDocument((_req, s, doc) => {
  if (doc.status === 'ARCHIVED') return stateError('ARCHIVED', 'ARCHIVED');
  Object.assign(doc, { status: 'ARCHIVED', updatedAt: nowIso() });
  save(s);
  return ok(documentOf(doc), 200, 'Đã lưu trữ tài liệu');
});

const reindexDocument = withDocument((_req, s, doc) => {
  if (doc.status !== 'PUBLISHED') return fail('INVALID_STATE_TRANSITION', `Tài liệu ${doc.status} không có chỉ mục để nạp lại`, 409);
  Object.assign(doc, { indexStatus: 'PENDING', indexError: null });
  save(s);
  return ok(documentOf(doc), 202, 'Đã nhận yêu cầu nạp lại chỉ mục');
});

// ─── Hồ sơ gợi ý (#12–#13, UC94) ───

interface Item {
  itemType: ItemType;
  itemId: number;
  name: string;
  group: 'GROOMING' | 'PRODUCT';
}
const profileKey = (type: ItemType, id: number) => `${type}:${id}`;

/** BR-AI-18, 20: chỉ dịch vụ thẩm mỹ và sản phẩm GOODS không kê đơn đang kinh doanh. */
function profileItems(): Item[] {
  return [
    ...catalogServices()
      .filter((x) => x.isActive && x.group === 'GROOMING')
      .map((x): Item => ({ itemType: 'SERVICE', itemId: x.serviceId, name: x.name, group: 'GROOMING' })),
    ...catalogProducts()
      .filter((x) => x.isActive && x.productType === 'GOODS' && !x.isPrescription)
      .map((x): Item => ({ itemType: 'PRODUCT', itemId: x.productId, name: x.name, group: 'PRODUCT' })),
  ].sort((a, b) => (a.itemType === b.itemType ? a.itemId - b.itemId : a.itemType === 'SERVICE' ? -1 : 1));
}

const rowOf = (item: Item, profile: ItemProfile | undefined): ItemProfileRow => ({ ...item, hasProfile: !!profile, ...(profile ? { profile } : {}) });

const listItemProfiles: MockHandler = (req) => {
  const { itemType, missingOnly, q } = req.query;
  if (itemType !== undefined && !ITEM_TYPES.includes(itemType as ItemType)) return badParam('itemType');
  if (missingOnly !== undefined && missingOnly !== 'true' && missingOnly !== 'false') return badParam('missingOnly');
  const badPaging = typeError(req.query, ['page', 'size']);
  if (badPaging) return badPaging;
  if (!isSuperManager(req)) return denied();
  const needle = q?.trim() ? foldVi(q.trim()) : null;
  const { profiles } = load();
  const rows = profileItems()
    .filter((i) => (!itemType || i.itemType === itemType) && (!needle || foldVi(i.name).includes(needle)))
    .map((i) => rowOf(i, profiles[profileKey(i.itemType, i.itemId)]))
    .filter((r) => missingOnly !== 'true' || !r.hasProfile);
  return ok(pageOf(rows, clampPaging(req.query)));
};

const normalizeKeyword = (k: string) => foldVi(k).replace(/\s+/g, ' ').trim();

const saveItemProfile: MockHandler = (req) => {
  const badPath = ITEM_TYPES.includes(req.params.itemType as ItemType) ? typeError(req.params, ['itemId']) : badParam('itemType');
  if (badPath) return badPath;
  const b = req.body;
  if (
    !isObject(b) ||
    !optEnum(b.species, PROFILE_SPECIES) ||
    !optInt(b.minAgeWeeks) ||
    !optInt(b.maxAgeWeeks) ||
    !optNumber(b.minWeightKg) ||
    !optNumber(b.maxWeightKg) ||
    !optInt(b.repeatIntervalDays) ||
    !(b.breedKeywords === undefined || b.breedKeywords === null || (Array.isArray(b.breedKeywords) && b.breedKeywords.every((k) => typeof k === 'string')))
  ) {
    return malformed();
  }
  if (!isSuperManager(req)) return denied();
  const num = (v: unknown) => (typeof v === 'number' ? v : null);
  const minAge = num(b.minAgeWeeks);
  const maxAge = num(b.maxAgeWeeks);
  const minKg = num(b.minWeightKg);
  const maxKg = num(b.maxWeightKg);
  const interval = num(b.repeatIntervalDays);
  const keywords = ((b.breedKeywords as string[] | null | undefined) ?? []).map(normalizeKeyword);
  const bad = invalid([
    ['breedKeywords', 'Vui lòng gửi danh sách từ khóa giống (có thể rỗng)', !Array.isArray(b.breedKeywords)],
    ['breedKeywords', `Mỗi từ khóa giống tối đa ${AI_LIMITS.breedKeyword} ký tự`, keywords.some((k) => k.length > AI_LIMITS.breedKeyword)],
    ['minAgeWeeks', 'Tuổi tối thiểu phải từ 0 đến 32767 tuần', minAge !== null && (minAge < 0 || minAge > 32767)],
    ['maxAgeWeeks', 'Tuổi tối đa phải từ 0 đến 32767 tuần', maxAge !== null && (maxAge < 0 || maxAge > 32767)],
    ['minWeightKg', 'Cân nặng tối thiểu phải từ 0 đến 9999.99 kg', minKg !== null && (minKg < 0 || minKg > 9999.99)],
    ['maxWeightKg', 'Cân nặng tối đa phải từ 0 đến 9999.99 kg', maxKg !== null && (maxKg < 0 || maxKg > 9999.99)],
    ['repeatIntervalDays', 'Chu kỳ dùng lại phải từ 1 đến 32767 ngày', interval !== null && (interval < 1 || interval > 32767)],
    ['minAgeWeeks', 'Tuổi tối thiểu không được lớn hơn tuổi tối đa', minAge !== null && maxAge !== null && minAge > maxAge],
    ['minWeightKg', 'Cân nặng tối thiểu không được lớn hơn cân nặng tối đa', minKg !== null && maxKg !== null && minKg > maxKg],
  ]);
  if (bad) return bad;

  const itemType = req.params.itemType as ItemType;
  const itemId = Number(req.params.itemId);
  const service = itemType === 'SERVICE' ? catalogServices().find((x) => x.serviceId === itemId) : undefined;
  const product = itemType === 'PRODUCT' ? catalogProducts().find((x) => x.productId === itemId) : undefined;
  if (!service && !product) return notFound(itemType === 'SERVICE' ? 'dịch vụ' : 'sản phẩm', itemId);
  // BR-AI-18: khám / tiêm, loại chuồng (đã có kennel_types), thuốc, vaccine, sản phẩm kê đơn không có hồ sơ.
  if ((service && service.group !== 'GROOMING') || (product && (product.productType !== 'GOODS' || product.isPrescription))) {
    return ruleFail('BR-AI-18', 'Chỉ lập hồ sơ gợi ý cho dịch vụ thẩm mỹ và sản phẩm bán lẻ không kê đơn');
  }
  const item: Item = service
    ? { itemType, itemId, name: service.name, group: 'GROOMING' }
    : { itemType, itemId, name: product!.name, group: 'PRODUCT' };

  const species = (b.species as ProfileSpecies | null | undefined) ?? null;
  const profile: ItemProfile = {
    ...(species ? { species } : {}),
    minAgeWeeks: minAge,
    maxAgeWeeks: maxAge,
    minWeightKg: minKg,
    maxWeightKg: maxKg,
    breedKeywords: [...new Set(keywords.filter(Boolean))],
    repeatIntervalDays: interval,
  };
  const empty = !species && minAge === null && maxAge === null && minKg === null && maxKg === null && profile.breedKeywords.length === 0 && interval === null;
  const s = load();
  if (empty) delete s.profiles[profileKey(itemType, itemId)];
  else s.profiles[profileKey(itemType, itemId)] = profile;
  save(s);
  return ok(rowOf(item, empty ? undefined : profile), 200, 'Đã lưu hồ sơ gợi ý');
};

// ─── Thú của người gọi, đọc qua route của mock khác (A9) ───

/** Gọi handler của mock khác bằng tài khoản người gọi; không phải 200 thì null (vd khách chờ liên kết hồ sơ bị 400 BR-TK-19 → coi như chưa có thú). */
function readAs<T>(handler: MockHandler, req: MockRequest, params: Record<string, string> = {}, query: Record<string, string> = {}): T | null {
  const reply = handler({ body: null, account: req.account, params, query });
  return reply.status === 200 ? (reply.body as { data: T }).data : null;
}
const myPets = (req: MockRequest) => readAs<PetResponse[]>(customerPetRoutes['GET /api/me/pets'], req) ?? [];
const daysBetween = (from: string, to: string) => Math.round((Date.parse(to) - Date.parse(from)) / DAY);
const weeksOld = (birthDate: string | null | undefined, today: string) => (birthDate ? Math.floor(daysBetween(birthDate, today) / 7) : null);
const dmy = (iso: string) => iso.split('-').reverse().join('/');
const isCustomer = (req: MockRequest) => req.account!.role === 'CUSTOMER';

/** Thú của mình theo `petId` (BR-AI-11): không có hoặc của người khác / chủ cũ → 404 cùng câu (ADR-0006); đã mất → 400 BR-KH-05. */
function ownLivingPet(req: MockRequest): PetResponse | MockReply {
  const pet = myPets(req).find((p) => p.petId === Number(req.params.petId));
  if (!pet) return notFound('thú cưng', req.params.petId);
  return pet.deceasedOn ? ruleFail('BR-KH-05', 'Thú cưng đã mất, hồ sơ chỉ đọc') : pet;
}
const isReply = (x: PetResponse | MockReply): x is MockReply => 'status' in x && !('petId' in x);

// ─── Lịch tiêm của thú (#3, BR-AI-11) ───

export interface DoseFact {
  doseNumber: number;
  administeredOn: string;
  nextDueDate: string;
}

/** Trạng thái của MỘT loại vaccine (BR-AI-11); `protocols` đã sắp theo số mũi, `last` là mũi mới nhất chưa bị thay thế. Thuần, test bảng. */
export function vaccinationItemOf(args: {
  type: Pick<VaccineType, 'vaccineTypeId' | 'name'>;
  protocols: Pick<VaccinationProtocol, 'doseNumber' | 'minAgeWeeks'>[];
  last: DoseFact | null;
  ageWeeks: number | null;
  today: string;
  dueSoonDays: number;
}): VaccinationItem {
  const { type, protocols, last, ageWeeks, today, dueSoonDays } = args;
  const base = { vaccineTypeId: type.vaccineTypeId, vaccineName: type.name };
  if (!last) {
    const firstDoseFromAgeWeeks = protocols[0]?.minAgeWeeks ?? null;
    const young = ageWeeks !== null && firstDoseFromAgeWeeks !== null && ageWeeks < firstDoseFromAgeWeeks;
    return {
      ...base,
      status: young ? 'NOT_YET_ELIGIBLE' : 'NOT_STARTED',
      lastDoseNumber: null,
      lastAdministeredOn: null,
      nextDoseNumber: protocols[0]?.doseNumber ?? 1,
      nextDueDate: null,
      firstDoseFromAgeWeeks,
    };
  }
  const dueLimit = vnDate(Date.parse(today) + dueSoonDays * DAY);
  const status: VaccinationStatus = last.nextDueDate < today ? 'OVERDUE' : last.nextDueDate <= dueLimit ? 'DUE_SOON' : 'UP_TO_DATE';
  return {
    ...base,
    status,
    lastDoseNumber: last.doseNumber,
    lastAdministeredOn: last.administeredOn,
    // Hết phác đồ thì nhắc lại mũi cuối (A9).
    nextDoseNumber: protocols.find((p) => p.doseNumber === last.doseNumber + 1)?.doseNumber ?? last.doseNumber,
    nextDueDate: last.nextDueDate,
    firstDoseFromAgeWeeks: null,
  };
}

function vaccinationLine(item: VaccinationItem): string {
  const ageNote = item.firstDoseFromAgeWeeks != null ? `mũi 1 từ ${item.firstDoseFromAgeWeeks} tuần tuổi` : '';
  switch (item.status) {
    case 'NOT_YET_ELIGIBLE':
      return `- ${item.vaccineName}: bé chưa đủ tuổi tiêm, ${ageNote}.`;
    case 'NOT_STARTED':
      return `- ${item.vaccineName}: chưa có mũi nào ghi nhận tại hệ thống, ${ageNote}.`;
    case 'UP_TO_DATE':
      return `- ${item.vaccineName}: đã tiêm mũi ${item.lastDoseNumber} ngày ${dmy(item.lastAdministeredOn!)}, mũi tiếp theo dự kiến ${dmy(item.nextDueDate!)}.`;
    case 'DUE_SOON':
      return `- ${item.vaccineName}: đã tiêm mũi ${item.lastDoseNumber} ngày ${dmy(item.lastAdministeredOn!)}, sắp đến hạn tiêm tiếp ngày ${dmy(item.nextDueDate!)}.`;
    case 'OVERDUE':
      return `- ${item.vaccineName}: đã tiêm mũi ${item.lastDoseNumber} ngày ${dmy(item.lastAdministeredOn!)}, đã quá hạn tiêm tiếp từ ${dmy(item.nextDueDate!)}.`;
  }
}

const VACCINATION_DISCLAIMER = 'Chỉ gồm các mũi tiêm được ghi nhận tại hệ thống Pet Care; thông tin tham khảo, không thay thế tư vấn của bác sĩ thú y.';

function vaccinationSummaryOf(req: MockRequest, pet: PetResponse): VaccinationSummary {
  const today = vnDate();
  const protocols = catalogProtocols().filter((p) => p.isActive && p.species === pet.species);
  const doses = readAs<{ vaccinations: VaccinationRecord[] }>(customerPetRoutes['GET /api/pets/:petId/health-record'], req, { petId: String(pet.petId) })?.vaccinations ?? [];
  const newest = (a: VaccinationRecord, b: VaccinationRecord) => b.administeredOn.localeCompare(a.administeredOn) || b.vaccinationId - a.vaccinationId;
  const items = catalogVaccineTypes()
    .filter((t) => t.isActive && t.species === pet.species && protocols.some((p) => p.vaccineTypeId === t.vaccineTypeId))
    .sort((a, b) => a.vaccineTypeId - b.vaccineTypeId)
    .map((type) => {
      const last = doses.filter((d) => d.vaccineTypeId === type.vaccineTypeId).sort(newest)[0] ?? null;
      return vaccinationItemOf({
        type,
        protocols: protocols.filter((p) => p.vaccineTypeId === type.vaccineTypeId).sort((a, b) => a.doseNumber - b.doseNumber),
        last: last && { doseNumber: last.doseNumber, administeredOn: last.administeredOn, nextDueDate: last.nextDueDate },
        ageWeeks: weeksOld(pet.birthDate, today),
        today,
        dueSoonDays: aiConfig('reminder.vaccine_days_before'),
      });
    });
  const message = items.length
    ? [`Lịch tiêm của ${pet.name}:`, ...items.map(vaccinationLine)].join('\n')
    : `Hiện hệ thống chưa có phác đồ tiêm phòng cho loài của ${pet.name}.`;
  return { petId: pet.petId, petName: pet.name, items, message, disclaimer: VACCINATION_DISCLAIMER };
}

const getVaccinationSummary: MockHandler = (req) => {
  const bad = typeError(req.params, ['petId']);
  if (bad) return bad;
  if (!isCustomer(req)) return denied();
  const pet = ownLivingPet(req);
  return isReply(pet) ? pet : ok(vaccinationSummaryOf(req, pet));
};

// ─── Gợi ý cá nhân hóa (#4, BR-AI-18…23, 07 §7.3) ───

export interface RecoPet {
  species: Species;
  breed?: string | null;
  birthDate?: string | null;
  weightKg?: number | null;
}
/** Thuộc tính đối tượng của mục: hồ sơ `ai_item_profiles`, hoặc (loại chuồng) loài + sức chứa từ `kennel_types`. */
export interface RecoCriteria {
  species?: string | null;
  minAgeWeeks?: number | null;
  maxAgeWeeks?: number | null;
  minWeightKg?: number | null;
  maxWeightKg?: number | null;
  breedKeywords?: string[];
}

/**
 * A ∈ [0, 1] (07 §7.3). Tiêu chí đánh giá được = có định nghĩa VÀ thú có dữ liệu. Loài / tuổi / cân nặng đánh giá được mà không khớp → loại mục; giống không khớp chỉ không được tính điểm.
 * Không có tiêu chí đánh giá được → 0,3 ("Phù hợp chung", BR-AI-20).
 */
export function attributeFit(pet: RecoPet, criteria: RecoCriteria | null, today: string): { excluded: boolean; fit: number; reasons: string[] } {
  const excluded = { excluded: true, fit: 0, reasons: [] as string[] };
  const reasons: string[] = [];
  let evaluable = 0;
  let matched = 0;
  const c = criteria ?? {};
  if (c.species) {
    evaluable++;
    if (c.species !== pet.species) return excluded;
    matched++;
    reasons.push(`Phù hợp ${SPECIES_LABELS[pet.species].toLowerCase()}`);
  }
  const weeks = weeksOld(pet.birthDate, today);
  if ((c.minAgeWeeks != null || c.maxAgeWeeks != null) && weeks !== null) {
    evaluable++;
    if ((c.minAgeWeeks != null && weeks < c.minAgeWeeks) || (c.maxAgeWeeks != null && weeks > c.maxAgeWeeks)) return excluded;
    matched++;
    reasons.push(`Phù hợp độ tuổi ${weeks < 52 ? `${weeks} tuần` : `${Math.floor(weeks / 52)} năm`}`);
  }
  const kg = pet.weightKg ?? null;
  if ((c.minWeightKg != null || c.maxWeightKg != null) && kg !== null) {
    evaluable++;
    if ((c.minWeightKg != null && kg < c.minWeightKg) || (c.maxWeightKg != null && kg > c.maxWeightKg)) return excluded;
    matched++;
    reasons.push(`Phù hợp cân nặng ${kg} kg`);
  }
  if (c.breedKeywords?.length && pet.breed) {
    evaluable++;
    if (c.breedKeywords.some((k) => foldVi(pet.breed!).includes(k))) {
      matched++;
      reasons.push(`Phù hợp giống ${pet.breed}`);
    }
  }
  return evaluable === 0 ? { excluded: false, fit: 0.3, reasons: ['Phù hợp chung'] } : { excluded: false, fit: matched / evaluable, reasons };
}

/** `score = 100 × (wA·A + wH·H + wP·P)` (BR-AI-21), làm tròn 1 chữ số thập phân. */
export const recoScore = (fit: number, history: number, popularity: number, w: { attr: number; history: number; popularity: number }) =>
  Math.round(100 * (w.attr * fit + w.history * history + w.popularity * popularity) * 10) / 10;

interface History {
  serviceLast: Map<number, string>;
  productLast: Map<number, string>;
  productCategories: Set<number>;
  stayKennels: Set<number>;
}

/** Lịch sử của thú / khách từ các mock nguồn (A10). */
function historyOf(req: MockRequest, pet: PetResponse): History {
  const latest = (map: Map<number, string>, id: number, date: string) => {
    if (!map.has(id) || map.get(id)! < date) map.set(id, date);
  };
  const serviceLast = new Map<number, string>();
  for (const visit of completedVisitsOfPet(pet.petId)) {
    const date = vnDate(Date.parse(visit.completedAt ?? visit.checkedInAt));
    for (const serviceId of serviceIdsOfVisit(visit.visitId)) latest(serviceLast, serviceId, date);
  }
  const productLast = new Map<number, string>();
  for (const order of allOrderRows()) {
    if (order.status !== 'PAID' || order.customerId !== pet.customerId || !order.paidAt) continue;
    for (const line of order.lines) if (line.productId != null) latest(productLast, line.productId, vnDate(Date.parse(order.paidAt)));
  }
  const products = catalogProducts();
  const bookings = readAs<{ content: Booking[] }>(boardingRoutes['GET /api/boarding-bookings'], req, {}, { petId: String(pet.petId), size: '100' })?.content ?? [];
  return {
    serviceLast,
    productLast,
    productCategories: new Set([...productLast.keys()].flatMap((id) => products.find((p) => p.productId === id)?.categoryId ?? [])),
    stayKennels: new Set(bookings.filter((b) => ['CHECKED_IN', 'OVERDUE', 'CHECKED_OUT'].includes(b.status)).map((b) => b.kennelTypeId)),
  };
}

interface Candidate {
  group: RecoGroup;
  itemType: ItemType;
  itemId: number;
  name: string;
  price: number;
  priceIsFrom: boolean;
  imageUrl: string | null;
  criteria: RecoCriteria | null;
  intervalDays: number | null;
  categoryId: number | null;
}

/** Ứng viên đủ điều kiện (BR-AI-18, 19): chưa xét độ phù hợp với thú. */
function candidatesFor(profiles: Record<string, ItemProfile>): Candidate[] {
  const branchIds = activeBranchSummaries().map((b) => b.branchId);
  const enabledSomewhere = (serviceId: number) => branchIds.some((id) => branchBooking(id)?.isServiceEnabled(serviceId));
  const services = catalogServices()
    .filter((x) => x.isActive && (x.group === 'GROOMING' || (x.group === 'BOARDING' && x.kennelType)) && enabledSomewhere(x.serviceId))
    .map((x): Candidate => {
      const grooming = x.group === 'GROOMING';
      const profile = profiles[profileKey('SERVICE', x.serviceId)];
      return {
        group: grooming ? 'GROOMING' : 'BOARDING',
        itemType: 'SERVICE',
        itemId: x.serviceId,
        name: x.name,
        price: x.price,
        priceIsFrom: x.priceIsFrom,
        imageUrl: null,
        criteria: grooming ? (profile ?? null) : { species: x.kennelType!.species, maxWeightKg: x.kennelType!.maxWeightKg },
        intervalDays: grooming ? (profile?.repeatIntervalDays ?? null) : null,
        categoryId: null,
      };
    });
  const products = catalogProducts()
    .filter((x) => x.isActive && x.productType === 'GOODS' && !x.isPrescription && branchIds.some((id) => availableQuantity(id, x.productId) > 0))
    .map((x): Candidate => {
      const profile = profiles[profileKey('PRODUCT', x.productId)];
      return {
        group: 'PRODUCT',
        itemType: 'PRODUCT',
        itemId: x.productId,
        name: x.name,
        price: x.price,
        priceIsFrom: false,
        imageUrl: x.imageUrl ?? null,
        criteria: profile ?? null,
        intervalDays: profile?.repeatIntervalDays ?? null,
        categoryId: x.categoryId,
      };
    });
  return [...services, ...products];
}

/** H ∈ [0, 1] (07 §7.3) và lý do đi kèm. */
function historyFit(c: Candidate, h: History, today: string): { value: number; reason: string | null } {
  if (c.group === 'GROOMING') {
    const interval = c.intervalDays ?? aiConfig('ai.reco.default_groom_interval_days');
    const last = h.serviceLast.get(c.itemId);
    if (last) {
      const days = daysBetween(last, today);
      return days >= interval ? { value: 1, reason: `Đã ${days} ngày kể từ lần dùng gần nhất, đến hạn dùng lại` } : { value: (0.2 * days) / interval, reason: null };
    }
    const groomingIds = new Set(catalogServices().filter((x) => x.group === 'GROOMING').map((x) => x.serviceId));
    return [...h.serviceLast.keys()].some((id) => groomingIds.has(id)) ? { value: 0.4, reason: 'Bé từng dùng dịch vụ thẩm mỹ khác' } : { value: 0, reason: null };
  }
  if (c.group === 'PRODUCT') {
    const interval = c.intervalDays ?? aiConfig('ai.reco.default_product_interval_days');
    const last = h.productLast.get(c.itemId);
    if (last) {
      const days = daysBetween(last, today);
      return days >= interval ? { value: 1, reason: `Đã ${days} ngày kể từ lần mua gần nhất, đến lúc mua lại` } : { value: 0, reason: null };
    }
    return c.categoryId !== null && h.productCategories.has(c.categoryId) ? { value: 0.4, reason: 'Bạn đã mua sản phẩm cùng danh mục' } : { value: 0, reason: null };
  }
  if (h.stayKennels.has(c.itemId)) return { value: 1, reason: 'Bé từng lưu trú loại chuồng này' };
  return h.stayKennels.size ? { value: 0.6, reason: 'Bé từng lưu trú loại chuồng khác' } : { value: 0, reason: null };
}

function recommendationsOf(req: MockRequest, pet: PetResponse, group: RecoGroup | undefined, limit: number): RecommendedItem[] {
  const today = vnDate();
  const history = historyOf(req, pet);
  const weights = { attr: aiConfig('ai.reco.weight_attr'), history: aiConfig('ai.reco.weight_history'), popularity: aiConfig('ai.reco.weight_popularity') };
  const recoPet: RecoPet = { species: pet.species, breed: pet.breed, birthDate: pet.birthDate, weightKg: pet.currentWeightKg };
  const candidates = candidatesFor(load().profiles).filter((c) => !group || c.group === group);
  const popularityMax = (g: RecoGroup) => Math.max(0, ...Object.entries(SEED_POPULARITY).filter(([k]) => k.startsWith(`${g}:`)).map(([, n]) => n));
  const scored = candidates.flatMap((c) => {
    const fit = attributeFit(recoPet, c.criteria, today);
    if (fit.excluded) return [];
    const hist = historyFit(c, history, today);
    const max = popularityMax(c.group);
    const popularity = max > 0 ? (SEED_POPULARITY[`${c.group}:${c.itemId}`] ?? 0) / max : 0;
    const reasons = [...fit.reasons, ...(hist.reason ? [hist.reason] : []), ...(popularity >= 0.5 ? ['Được nhiều khách chọn'] : [])];
    const item: RecommendedItem = {
      group: c.group,
      itemType: c.itemType,
      itemId: c.itemId,
      name: c.name,
      price: c.price,
      priceIsFrom: c.priceIsFrom,
      priceUnit: c.group === 'BOARDING' ? 'đêm' : null,
      imageUrl: c.imageUrl,
      score: recoScore(fit.fit, hist.value, popularity, weights),
      reasons,
    };
    return [item];
  });
  const byGroup = (g: RecoGroup) => scored.filter((i) => i.group === g).sort((a, b) => b.score - a.score || a.itemId - b.itemId).slice(0, limit);
  return RECO_GROUPS.flatMap(byGroup);
}

const getRecommendations: MockHandler = (req) => {
  const { group, limit } = req.query;
  const badPath = typeError(req.params, ['petId']) ?? typeError(req.query, ['limit']);
  if (badPath) return badPath;
  if (group !== undefined && !RECO_GROUPS.includes(group as RecoGroup)) return badParam('group');
  if (!isCustomer(req)) return denied();
  const { min, max } = AI_LIMITS.recommendationLimit;
  const bad = invalid([['limit', `limit phải từ ${min} đến ${max}`, limit !== undefined && (Number(limit) < min || Number(limit) > max)]]);
  if (bad) return bad;
  const pet = ownLivingPet(req);
  if (isReply(pet)) return pet;
  const response: RecommendationResponse = {
    petId: pet.petId,
    generatedAt: nowIso(),
    items: recommendationsOf(req, pet, group as RecoGroup | undefined, limit === undefined ? aiConfig('ai.reco.max_per_group') : Number(limit)),
  };
  return ok(response);
};

// ─── Chatbot (#1, #2; UC90, UC91; 07 §5.2) ───

/** Khớp theo cụm từ có ranh giới từ, ưu tiên dạng có dấu; dạng bỏ dấu chỉ dùng cho cụm từ 2 âm tiết trở lên (BR-AI-03, 26): "giá" không trùng "già", "tiêm" không trùng "tiệm". */
const lowerNfc = (t: string) => t.normalize('NFC').toLowerCase().replace(/\s+/g, ' ').trim();
const escapeRegExp = (t: string) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const wordRegExp = (phrase: string) => new RegExp(`(?<![\\p{L}\\p{N}])${escapeRegExp(phrase).replace(/ /g, '\\s+')}(?![\\p{L}\\p{N}])`, 'u');
export function hasPhrase(text: string, phrase: string): boolean {
  const t = lowerNfc(text);
  const p = lowerNfc(phrase);
  return wordRegExp(p).test(t) || (p.includes(' ') && wordRegExp(foldVi(p)).test(foldVi(t)));
}
const hasAny = (text: string, phrases: readonly string[]) => phrases.some((p) => hasPhrase(text, p));

// Luật là danh sách trong tệp tài nguyên của BE (07 §4.1–4.2); đây là bản khởi điểm cho mock.
const EMERGENCY_PHRASES = [
  'khó thở', 'thở dốc', 'co giật', 'ngộ độc', 'ăn nhầm thuốc', 'ăn nhầm chocolate', 'ăn nhầm hóa chất', 'chảy máu không cầm', 'bất tỉnh', 'ngất', 'bị liệt', 'liệt chân', 'liệt nửa người',
  'bụng căng cứng', 'xe đâm', 'rơi từ trên cao', 'không đi tiểu',
] as const;
const APPOINTMENT_PHRASES = ['lịch hẹn', 'đã đặt lịch', 'lịch khám', 'có lịch', 'hẹn khám'] as const;
const VACCINATION_PHRASES = ['tiêm', 'vaccine', 'vắc xin', 'tái chủng', 'mũi tiêm'] as const;
const STRONG_OWNERSHIP = ['của tôi', 'của mình', 'nhà tôi', 'nhà mình', 'bé nhà', 'con nhà'] as const;
const WEAK_OWNERSHIP = ['sắp tới', 'sắp đến', 'kế tiếp', 'tiếp theo', 'đã đặt', 'đến hạn', 'quá hạn', 'hôm nay', 'ngày mai', 'tuần này'] as const;
const PRICE_PHRASES = ['giá bao nhiêu', 'giá tiêm', 'bao nhiêu tiền', 'chi phí', 'mấy tiền', 'giá', 'mua ở đâu', 'mua thuốc'] as const;
const PRICE_BLOCKERS = ['giá bao nhiêu', 'giá tiêm', 'bao nhiêu tiền', 'chi phí', 'mấy tiền'] as const;
const MEDICAL_RECORD_PHRASES = ['bệnh án', 'đơn thuốc', 'toa thuốc', 'kết quả xét nghiệm', 'xét nghiệm', 'hồ sơ bệnh'] as const;
const INJECTION_PHRASES = ['bỏ qua mọi quy tắc', 'bỏ qua các quy tắc', 'bỏ qua hướng dẫn', 'lời nhắc hệ thống', 'system prompt', 'từ giờ bạn là'] as const;
const ANIMAL_WORDS = ['chó', 'mèo', 'thú', 'bé', 'cún', 'hamster', 'thỏ'] as const;
/** Tên thú trùng từ thông dụng chỉ là dấu hiệu sở hữu khi đi sau "bé", "con", "chú", "em" (BR-AI-26). */
const COMMON_PET_NAMES = ['vàng', 'mít', 'đen', 'nâu', 'trắng', 'xám'] as const;

/** Tên thú xuất hiện trong câu hỏi (không phân biệt hoa thường, đúng ranh giới từ, theo dạng viết của tên). */
export function mentionsPet(text: string, name: string): boolean {
  const t = lowerNfc(text);
  const n = lowerNfc(name);
  const rx = COMMON_PET_NAMES.includes(n as (typeof COMMON_PET_NAMES)[number]) ? new RegExp(`(?<![\\p{L}\\p{N}])(?:bé|con|chú|em)\\s+${escapeRegExp(n)}(?![\\p{L}\\p{N}])`, 'u') : wordRegExp(escapeRegExp(n));
  return rx.test(t);
}

export type PersonalIntent = 'APPOINTMENT_UPCOMING' | 'VACCINATION_SCHEDULE';

/**
 * BR-AI-26: từ khóa ý định CÙNG dấu hiệu sở hữu. Dấu hiệu mạnh (của tôi, nhà tôi…, hoặc tên thú) đủ một mình; dấu hiệu yếu (sắp tới, quá hạn…) chỉ đủ cho lịch hẹn, không đủ cho tiêm chủng.
 * Câu chứa cụm chỉ giá không là ý định cá nhân. `petNames` rỗng ở endpoint công khai (BR-AI-28a: không có bước so khớp tên thú).
 */
export function detectPersonalIntent(question: string, petNames: readonly string[]): PersonalIntent | null {
  if (hasAny(question, PRICE_BLOCKERS)) return null;
  const strong = hasAny(question, STRONG_OWNERSHIP) || petNames.some((n) => mentionsPet(question, n));
  const weak = hasAny(question, WEAK_OWNERSHIP);
  if (hasAny(question, APPOINTMENT_PHRASES) && (strong || weak)) return 'APPOINTMENT_UPCOMING';
  return hasAny(question, VACCINATION_PHRASES) && strong ? 'VACCINATION_SCHEDULE' : null;
}

/** BR-AI-12a: email, số điện thoại (`0…`, `+84…`) và dãy 9 hoặc 12 chữ số → `[email]`, `[sđt]`, `[số]`. */
export function maskPersonal(text: string): string {
  return text
    .replace(/[\w.+-]+@[\w-]+(?:\.[\w-]+)+/g, '[email]')
    .replace(/(?<![\d])(?:\+84|0)(?:[ .-]?\d){9}(?!\d)/g, '[sđt]')
    .replace(/(?<!\d)(?:\d{12}|\d{9})(?!\d)/g, '[số]');
}
/** BR-AI-12b: tên thú còn sống của khách → `[thú]` (mọi lần xuất hiện, A6). */
const maskPetNames = (text: string, names: readonly string[]) =>
  names.reduce((out, name) => out.replace(new RegExp(`(?<![\\p{L}\\p{N}])${escapeRegExp(name)}(?![\\p{L}\\p{N}])`, 'giu'), '[thú]'), text);

const DISCLAIMER = 'Thông tin tham khảo, không thay thế khám thú y.';
const REPLIES = {
  outOfScope: 'Mình chưa có thông tin cho câu hỏi này. Bạn có thể đặt lịch để bác sĩ tư vấn trực tiếp.',
  medicalRefusal: 'Mình không thể chẩn đoán bệnh, kê đơn hay nêu liều dùng thuốc. Bạn vui lòng đặt lịch khám để bác sĩ thú y tư vấn trực tiếp.',
  medicalRecord: 'Mình không xem được hồ sơ bệnh án. Bạn xem ở Hồ sơ sức khỏe của thú (UC25) hoặc hỏi bác sĩ.',
  unavailable: 'Trợ lý đang bận, bạn vui lòng thử lại sau ít phút hoặc đặt lịch để được tư vấn trực tiếp.',
  emergency: 'Đây có thể là tình huống khẩn cấp. Hãy đưa thú tới ngay chi nhánh nhận cấp cứu dưới đây hoặc gọi trước khi đến.',
  emergencyNoBranch: 'Đây có thể là tình huống khẩn cấp. Hãy gọi ngay cho chi nhánh Pet Care gần nhất để được hướng dẫn.',
  needLogin: 'Vui lòng đăng nhập để xem lịch hẹn / lịch tiêm của bạn.',
  noAppointments: 'Bạn chưa có lịch hẹn sắp tới.',
  noPets: 'Bạn chưa có thú cưng nào còn sống trong hồ sơ. Hãy thêm thú cưng để xem lịch tiêm.',
  pickPet: 'Bạn muốn xem lịch tiêm của bé nào?',
} as const;

/**
 * MỘT chỗ dựng `ChatResponse` (hợp đồng): luôn đủ `sources`, `bookingHint`, `emergencyBranches`, `pets`; `disclaimer` và `sources` chỉ khi ANSWERED,
 * `emergencyBranches` chỉ khi EMERGENCY, `pets` chỉ khi NEED_PET_CHOICE.
 */
export function chatResponse(
  result: ChatResult,
  answer: string,
  extra: { sources?: ChatSource[]; bookingHint?: boolean; emergencyBranches?: EmergencyBranch[]; pets?: PetChoice[] } = {},
): ChatResponse {
  return {
    result,
    answer,
    sources: result === 'ANSWERED' ? (extra.sources ?? []) : [],
    disclaimer: result === 'ANSWERED' ? DISCLAIMER : null,
    bookingHint: extra.bookingHint ?? false,
    emergencyBranches: result === 'EMERGENCY' ? (extra.emergencyBranches ?? []) : [],
    pets: result === 'NEED_PET_CHOICE' ? (extra.pets ?? []) : [],
  };
}

/** Chi nhánh nhận cấp cứu ngoài giờ (BR-CN-05) kèm tên, địa chỉ, SĐT. */
function emergencyBranches(): EmergencyBranch[] {
  return activeBranchSummaries().flatMap(({ branchId }) => {
    const branch = branchBooking(branchId)?.branch;
    return branch?.acceptsAfterHoursEmergency ? [{ branchId, name: branch.name, address: branch.address, phone: branch.phone }] : [];
  });
}

/** BR-AI-09: RateLimitFilter đứng trước controller (A2). Lần bị từ chối vẫn được tính; bị chặn ở cửa sổ phút thì không tiêu hao hạn mức giờ. */
function takeRateLimit(s: State, now: number): MockReply | null {
  for (const name of ['minute', 'hour'] as const) {
    const window = s.limiter[name];
    const { limit, windowMs } = AI_RATE_LIMITS[name];
    if (now - window.start >= windowMs) Object.assign(window, { start: now, count: 0 });
    window.count += 1;
    if (window.count > limit) {
      return fail('TOO_MANY_REQUESTS', `Bạn thao tác quá nhanh, vui lòng thử lại sau ${Math.max(1, Math.ceil((window.start + windowMs - now) / 1000))} giây`, 429);
    }
  }
  return null;
}

// Truy hồi giả (A5): độ phủ từ khóa trên các tài liệu PUBLISHED đã nạp.
const STOP_WORDS = new Set([
  'là', 'và', 'có', 'không', 'thế', 'nào', 'gì', 'cho', 'của', 'tôi', 'mình', 'bạn', 'như', 'thì', 'bị', 'được', 'nên', 'ở', 'từ', 'đến', 'làm', 'sao', 'một', 'những', 'các', 'này', 'đó',
  'rất', 'hay', 'khi', 'để', 'với', 'về', 'trong', 'nhiều', 'bao', 'nhiêu', 'mấy', 'lúc', 'đi', 'ra', 'vào', 'hôm', 'nay', 'thì', 'mà', 'cần', 'phải', 'nếu', 'sau', 'trước', 'cũng',
]);
const tokensOf = (text: string) =>
  [...new Set(lowerNfc(text).split(/[^\p{L}\p{N}]+/u).filter((t) => t.length >= 2 && !STOP_WORDS.has(t) && !/^\d+$/.test(t)).map(foldVi))];

interface Hit {
  doc: DocRow;
  similarity: number;
}

function retrieve(s: State, question: string): Hit[] {
  const q = tokensOf(question);
  const docs = s.documents.filter((d) => d.status === 'PUBLISHED' && d.served).map((doc) => ({ doc, body: new Set(tokensOf(`${doc.served!.title} ${doc.served!.content}`)) }));
  // Từ càng hiếm trong kho (idf) càng nặng; từ câu hỏi không có trong tài liệu nào nặng nhất, nên câu lạc đề chỉ trùng vài từ phổ thông ("ăn", "ngày") vẫn dưới τ.
  const weight = (t: string) => Math.log(1 + docs.length / Math.max(1, docs.filter((d) => d.body.has(t)).length));
  const total = q.reduce((sum, t) => sum + weight(t), 0);
  // Câu hỏi nói rõ một loài thì không lấy tài liệu dành riêng cho loài kia (loài áp dụng đi kèm mỗi đoạn, 07 §5.4).
  const dog = hasPhrase(question, 'chó');
  const cat = hasPhrase(question, 'mèo');
  return docs
    .filter(({ doc }) => !((cat && !dog && doc.species === 'DOG') || (dog && !cat && doc.species === 'CAT')))
    .map(({ doc, body }): Hit => {
      const coverage = total ? q.filter((t) => body.has(t)).reduce((sum, t) => sum + weight(t), 0) / total : 0;
      return { doc, similarity: Math.round((0.45 + 0.45 * coverage) * 10_000) / 10_000 };
    })
    .sort((x, y) => y.similarity - x.similarity || x.doc.documentId - y.doc.documentId);
}

/** Đoạn trích trả lời: hai dòng nội dung (không phải tiêu đề / chú thích) trùng nhiều từ khóa nhất, giữ thứ tự gốc, tối đa ~420 ký tự. */
function excerptOf(doc: DocRow, question: string): string {
  const q = new Set(tokensOf(question));
  const lines = doc.served!.content
    .split('\n')
    .map((l) => l.replace(/^\s*(?:[-*]|\d+\.)\s+/, '').replace(/\*\*/g, '').trim())
    .filter((l) => l && !l.startsWith('#') && !l.startsWith('>'));
  const best = lines
    .map((line, index) => ({ line, index, score: tokensOf(line).filter((t) => q.has(t)).length }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .slice(0, 2)
    .sort((a, b) => a.index - b.index)
    .map((x) => x.line)
    .join(' ');
  const text = best || doc.served!.title;
  const stop = text.lastIndexOf('.', 420);
  return text.length <= 420 ? text : text.slice(0, stop > 0 ? stop + 1 : 420);
}

/** BR-AI-06: liều thuốc mạnh (mg, mcg, µg, IU / UI, viên, ống) hoặc ml / giọt cùng câu có từ thuốc; ml / giọt ở câu về nước hay vệ sinh tai không bị tính. */
function mentionsDosage(question: string): boolean {
  const f = foldVi(question);
  if (/\b\d+(?:[.,]\d+)?\s*(?:mg|mcg|ug|iu|ui|vien|ong)\b|\bbao nhieu\s+(?:mg|mcg|iu|ui|vien|ong)\b|\blieu\b/.test(f)) return true;
  return /\b\d+(?:[.,]\d+)?\s*(?:ml|giot)\b|\b(?:ml|giot)\b/.test(f) && /\b(?:thuoc|khang sinh|siro|vaccine|tay giun|xo giun)\b/.test(f);
}
const asksDiagnosis = (question: string) => /\b(?:chan doan|ke don|benh gi|thuoc gi|thuoc nao|dung thuoc)\b/.test(foldVi(question));

function upcomingAppointments(req: MockRequest, pets: PetResponse[], now: number): Appointment[] {
  const livingIds = new Set(pets.map((p) => p.petId));
  const list = readAs<{ content: Appointment[] }>(appointmentRoutes['GET /api/appointments'], req, {}, { status: 'BOOKED', size: '100' })?.content ?? [];
  // Lọc ở module ai: lịch BOOKED đã quá giờ mà ST05 chưa chuyển NO_SHOW vẫn nằm trong dữ liệu (BR-AI-27).
  return list
    .filter((a) => livingIds.has(a.petId) && Date.parse(`${a.slotDate}T${a.slotStart}:00+07:00`) >= now)
    .sort((a, b) => `${a.slotDate} ${a.slotStart}`.localeCompare(`${b.slotDate} ${b.slotStart}`) || a.appointmentId - b.appointmentId)
    .slice(0, aiConfig('ai.personal.appointments_limit'));
}

/** BR-AI-27: câu mẫu, không qua LLM, khách lấy từ token. */
function personalReply(req: MockRequest, intent: PersonalIntent, question: string, pets: PetResponse[], now: number): ChatResponse {
  if (intent === 'APPOINTMENT_UPCOMING') {
    const rows = upcomingAppointments(req, pets, now);
    if (!rows.length) return chatResponse('PERSONAL', REPLIES.noAppointments, { bookingHint: true });
    const lines = rows.map((a, i) => `${i + 1}) ${a.petName} — ${a.serviceName} — ${dmy(a.slotDate)} ${a.slotStart} — ${a.branchName} (${a.code})`);
    return chatResponse('PERSONAL', [`Bạn có ${rows.length} lịch hẹn sắp tới:`, ...lines].join('\n'));
  }
  if (!pets.length) return chatResponse('PERSONAL', REPLIES.noPets, { bookingHint: false });
  const named = pets.filter((p) => mentionsPet(question, p.name));
  const candidates = named.length ? named : pets;
  if (candidates.length === 1) return chatResponse('PERSONAL', vaccinationSummaryOf(req, candidates[0]).message);
  return chatResponse('NEED_PET_CHOICE', REPLIES.pickPet, { pets: candidates.map((p) => ({ petId: p.petId, name: p.name })) });
}

const REMEMBERED_PROVIDER_CALLS = 50;
const NEAR_TOPIC_SIMILARITY = 0.62;

function chat(req: MockRequest, scope: 'public' | 'me'): MockReply {
  const now = Date.now();
  const s = load(now);
  const limited = takeRateLimit(s, now);
  save(s);
  if (limited) return limited;

  const b = req.body;
  const previousRaw = isObject(b) ? b.previousQuestions : undefined;
  if (!isObject(b) || !optString(b.question) || !(previousRaw == null || (Array.isArray(previousRaw) && previousRaw.every((q) => typeof q === 'string')))) return malformed();
  if (scope === 'me' && !isCustomer(req)) return denied();
  const maxChars = aiConfig('ai.chat.max_question_chars');
  const maxPrevious = aiConfig('ai.chat.max_previous_questions');
  const question = typeof b.question === 'string' ? b.question.trim() : '';
  const previous = ((previousRaw as string[] | null | undefined) ?? []).map((q) => q.trim());
  const bad = invalid([
    ['question', 'Vui lòng nhập câu hỏi', question === ''],
    ['question', `Câu hỏi tối đa ${maxChars} ký tự`, question.length > maxChars],
    ['previousQuestions', `Tối đa ${maxPrevious} câu hỏi trước`, previous.length > maxPrevious],
    ['previousQuestions', `Mỗi câu hỏi trước tối đa ${maxChars} ký tự`, previous.some((q) => q.length > maxChars)],
  ]);
  if (bad) return bad;

  // 2. Che email, SĐT, số giấy tờ (BR-AI-12a).
  const masked = maskPersonal(question);
  // 3. Cấp cứu → không gọi nhúng hay LLM (BR-AI-03).
  if (hasAny(masked, EMERGENCY_PHRASES)) {
    const branches = emergencyBranches();
    return ok(branches.length ? chatResponse('EMERGENCY', REPLIES.emergency, { emergencyBranches: branches }) : chatResponse('EMERGENCY', REPLIES.emergencyNoBranch));
  }
  const pets = scope === 'me' ? myPets(req).filter((p) => !p.deceasedOn) : [];
  const names = pets.map((p) => p.name);
  // 4. Bệnh án / đơn thuốc của thú cụ thể → từ chối với mọi người dùng (BR-AI-28b).
  if (hasAny(masked, MEDICAL_RECORD_PHRASES) && (hasAny(masked, STRONG_OWNERSHIP) || names.some((n) => mentionsPet(masked, n)))) {
    return ok(chatResponse('MEDICAL_REFUSAL', REPLIES.medicalRecord));
  }
  // 5. Ý định cá nhân: công khai → NEED_LOGIN (BR-AI-28a), /me → PERSONAL / NEED_PET_CHOICE; không nhúng, không LLM, không ghi nội dung.
  const intent = detectPersonalIntent(masked, names);
  if (intent) return ok(scope === 'public' ? chatResponse('NEED_LOGIN', REPLIES.needLogin) : personalReply(req, intent, masked, pets, now));

  // 6. Chỉ ở /me: tên thú → [thú], SAU khi định tuyến ý định (BR-AI-12b).
  const sent = scope === 'me' ? maskPetNames(masked, names) : masked;
  const sentPrevious = previous.map((q) => (scope === 'me' ? maskPetNames(maskPersonal(q), names) : maskPersonal(q)));
  const call: ProviderCall = { question: sent, previousQuestions: sentPrevious, llm: false };
  s.provider = [...s.provider, call].slice(-REMEMBERED_PROVIDER_CALLS);

  // 7. Nhúng + truy hồi. Câu "gần chủ đề" (y khoa, giá, mua thuốc…) có độ tương đồng 0,58–0,67 nên phần lớn vượt τ dù tài liệu không có đáp án (T56): mock cho nó tới được bước LLM.
  const nearTopic = hasAny(sent, ANIMAL_WORDS) && (mentionsDosage(sent) || asksDiagnosis(sent) || hasAny(sent, PRICE_PHRASES) || hasAny(sent, INJECTION_PHRASES) || hasPhrase(sent, 'thuốc'));
  const tau = aiConfig('ai.retrieval.min_similarity');
  const found = retrieve(s, sent);
  const hits = found.filter((h) => h.similarity >= tau).slice(0, aiConfig('ai.retrieval.top_k'));
  if (!hits.length && !(nearTopic && NEAR_TOPIC_SIMILARITY >= tau && found.length)) {
    save(s);
    return ok(chatResponse('OUT_OF_SCOPE', REPLIES.outOfScope, { bookingHint: true }));
  }
  // 8. LLM: hết hạn mức ngày của gói free → UNAVAILABLE (BR-AI-10, A3).
  if (s.llm.count >= AI_DAILY_LLM_LIMIT) {
    save(s);
    return ok(chatResponse('UNAVAILABLE', REPLIES.unavailable, { bookingHint: true }));
  }
  s.llm.count += 1;
  call.llm = true;
  save(s);
  // 9. Hậu kiểm / inScope giả bằng từ khóa (A5).
  if (mentionsDosage(sent) || asksDiagnosis(sent)) return ok(chatResponse('MEDICAL_REFUSAL', REPLIES.medicalRefusal, { bookingHint: true }));
  if (hasAny(sent, PRICE_PHRASES) || hasAny(sent, INJECTION_PHRASES)) return ok(chatResponse('OUT_OF_SCOPE', REPLIES.outOfScope, { bookingHint: true }));
  if (!hits.length) return ok(chatResponse('OUT_OF_SCOPE', REPLIES.outOfScope, { bookingHint: true })); // các đoạn truy hồi không đủ trả lời đúng câu hỏi: inScope = false
  const sources = hits.slice(0, 3).map((h) => ({ documentId: h.doc.documentId, title: h.doc.title }));
  return ok(chatResponse('ANSWERED', excerptOf(hits[0].doc, sent), { sources }));
}

export const aiRoutes: Record<string, MockHandler> = {
  'POST /api/public/ai/chat': (req) => chat(req, 'public'),
  'POST /api/me/ai/chat': (req) => chat(req, 'me'),
  'GET /api/me/ai/pets/:petId/vaccination-summary': getVaccinationSummary,
  'GET /api/me/ai/pets/:petId/recommendations': getRecommendations,
  'GET /api/ai/documents': listDocuments,
  'POST /api/ai/documents': createDocument,
  'GET /api/ai/documents/:documentId': getDocument,
  'PATCH /api/ai/documents/:documentId': updateDocument,
  'POST /api/ai/documents/:documentId/publish': publishDocument,
  'POST /api/ai/documents/:documentId/archive': archiveDocument,
  'POST /api/ai/documents/:documentId/reindex': reindexDocument,
  'GET /api/ai/item-profiles': listItemProfiles,
  'PUT /api/ai/item-profiles/:itemType/:itemId': saveItemProfile,
};
