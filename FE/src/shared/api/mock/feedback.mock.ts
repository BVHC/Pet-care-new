// Mock feedback (T47, UC83 + UC84; content-v1 #18–22; BR-DG-01…04, BR-TK-19): khách gửi và xem lại, SUPER_MANAGER / BRANCH_MANAGER
// xem, mở (NEW → SEEN) và đánh dấu đã xử lý. Luật, thứ tự kiểm và câu báo lỗi chép từ BE (FeedbackSubmitService,
// FeedbackReviewService, FeedbackController). State ở localStorage (FEEDBACK_STATE_KEY), đọc lại mỗi request.
//
// Các điểm hợp đồng im lặng, hoặc BE khác kế hoạch (ASSUMPTION):
//  - GET /me/feedbacks KHÔNG chặn khách còn chờ quyết định liên kết: BE (FeedbackSubmitService.listMine) chỉ kiểm BR-TK-19 ở POST, nên
//    khách đó nhận feedback của chính mình (thực tế rỗng vì họ chưa gửi được). Trang /review nằm sau RequireLinkDecision nên không gọi tới.
//  - GET /feedbacks: BE kiểm `from > to` (400) TRƯỚC phạm vi chi nhánh (403), ngược với thứ tự chung "phạm vi trước 400 rule" của kế hoạch.
//  - Gửi: chi nhánh có gửi `branchId` thì phải ACTIVE với MỌI chủ đề (không chỉ BRANCH_SERVICE); không ACTIVE hoặc không có là 404.
//  - Ghi chú xử lý tối đa 2000 theo đơn vị UTF-16 (`@Size` của BE), khác nội dung feedback đếm code point.
//  - Giới hạn mỗi ngày là hằng số DAILY_LIMIT = 5 (mặc định của [CFG] feedback.max_per_day; mock identity chưa có tham số này).
//  - Thời điểm trả theo `…Z` như BE thật (hợp đồng ghi +07:00); FE định dạng theo giờ Việt Nam tường minh nên đúng cho cả hai.
//  - `Feedback.resolvedBy` chỉ là mã tài khoản (hợp đồng không kèm tên người xử lý).
//  - Dữ liệu mẫu có feedback của khách 3, 4 (hồ sơ tại quầy chưa gắn tài khoản, chỉ để danh sách quản lý có nhiều tên): nếu khachmoi@ liên
//    kết với một trong hai hồ sơ đó (cùng SĐT 0905111222) thì thấy các feedback này ở "Góp ý của tôi". Khách 2 (khachmoi@) không có feedback.
import type { AccountSummary, Role } from '../../types/auth';
import {
  FEEDBACK_CONTENT_MAX,
  FEEDBACK_CONTENT_MIN,
  FEEDBACK_STATUS_LABELS,
  FEEDBACK_TOPIC_LABELS,
  RESOLUTION_NOTE_MAX,
  feedbackContentLength,
  type Feedback,
  type FeedbackStatus,
  type FeedbackTopic,
  type MyFeedback,
} from '../../types/feedback';
import { vnDate } from '../../utils/vn-time';
import { activeBranchSummaries, branchBooking } from './branch.mock';
import { allCustomers, customerOfAccount, markHasData } from './customer.mock';
import { denied, fail, has, invalid, notFound, ok, ruleFail, scopeDenied, tooLong, type MockHandler, type MockReply } from './envelope';
import { branchIdOf } from './identity.mock';
import { clampPaging, notPositive, pageOf, typeError } from './public.mock';

export const FEEDBACK_STATE_KEY = 'petcare-mock-feedback';

export interface FeedbackRecord {
  feedbackId: number;
  customerId: number;
  topic: FeedbackTopic;
  branchId: number | null;
  rating: number | null;
  content: string;
  status: FeedbackStatus;
  seenAt: string | null;
  resolvedBy: number | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
  createdAt: string;
}

interface FeedbackState {
  feedbacks: FeedbackRecord[];
}

/** Mặc định của [CFG] `feedback.max_per_day` (BR-DG-01). */
const DAILY_LIMIT = 5;
const DAY_MS = 86_400_000;

/** Trường tùy chọn của một bản ghi; gộp vào bản ghi đã lưu để state cũ (thiếu trường thêm sau) không làm handler throw. */
const DEFAULTS = { branchId: null, rating: null, seenAt: null, resolvedBy: null, resolvedAt: null, resolutionNote: null };

/**
 * Dữ liệu mẫu: thời điểm tính lùi từ hôm nay theo giờ Việt Nam, đều trước 00:00 hôm nay nên ổn định trong ngày và không tính vào giới
 * hạn hôm nay. Khách 1 (khach@) có đủ ba trạng thái; #2 và #5 không gắn chi nhánh (chỉ SUPER_MANAGER xem); chi nhánh 2, 3 là DRAFT
 * nên không có feedback (BE chỉ nhận chi nhánh ACTIVE).
 */
function seedFeedbacks(): FeedbackRecord[] {
  const ago = (days: number, hhmm: string) => new Date(`${vnDate(Date.now() - days * DAY_MS)}T${hhmm}:00+07:00`).toISOString();
  const rec = (r: Partial<FeedbackRecord> & Pick<FeedbackRecord, 'feedbackId' | 'customerId' | 'topic' | 'content' | 'createdAt'>): FeedbackRecord => ({
    ...DEFAULTS,
    status: 'NEW',
    ...r,
  });
  return [
    rec({
      feedbackId: 1,
      customerId: 1,
      topic: 'BRANCH_SERVICE',
      branchId: 1,
      rating: 5,
      content: 'Bác sĩ khám rất kỹ và giải thích dễ hiểu, lễ tân hướng dẫn nhiệt tình. Bé Mochi hợp tác hơn mình nghĩ.',
      status: 'RESOLVED',
      createdAt: ago(9, '10:15'),
      seenAt: ago(9, '14:00'),
      resolvedBy: 3,
      resolvedAt: ago(8, '09:30'),
      resolutionNote: 'Đã gọi lại cảm ơn khách, chuyển lời khen tới bác sĩ phụ trách (ghi chú nội bộ).',
    }),
    rec({
      feedbackId: 2,
      customerId: 1,
      topic: 'WEBSITE',
      rating: 4,
      content: 'Trang đặt lịch dễ dùng, nhưng mình muốn chọn bác sĩ ngay ở bước chọn giờ thay vì phải gọi hỏi lại.',
      status: 'SEEN',
      createdAt: ago(3, '20:40'),
      seenAt: ago(2, '08:30'),
    }),
    rec({
      feedbackId: 3,
      customerId: 1,
      topic: 'BRANCH_SERVICE',
      branchId: 1,
      rating: 3,
      content: 'Phòng chờ hơi đông vào cuối tuần, mong có thêm ghế ngồi và nước uống cho khách đi cùng thú cưng.',
      createdAt: ago(1, '16:20'),
    }),
    rec({
      feedbackId: 4,
      customerId: 3,
      topic: 'BRANCH_SERVICE',
      branchId: 1,
      rating: 2,
      content: 'Mình chờ lấy thuốc gần 40 phút dù đã đặt lịch trước. Mong phòng khám sắp xếp lại quy trình vào giờ cao điểm.',
      status: 'SEEN',
      createdAt: ago(2, '09:05'),
      seenAt: ago(2, '10:00'),
    }),
    rec({
      feedbackId: 5,
      customerId: 4,
      topic: 'OTHER',
      content: 'Phòng khám có thể gửi nhắc lịch tiêm phòng qua tin nhắn điện thoại không? Email đôi khi mình không kịp đọc.',
      createdAt: ago(1, '08:10'),
    }),
    rec({
      feedbackId: 6,
      customerId: 4,
      topic: 'BRANCH_SERVICE',
      branchId: 1,
      rating: 4,
      content: 'Bé được tắm rất sạch nhưng trả bé trễ hơn giờ hẹn khoảng 30 phút. Nhân viên có xin lỗi nên mình thấy ổn.',
      status: 'RESOLVED',
      createdAt: ago(6, '15:45'),
      seenAt: ago(6, '16:30'),
      resolvedBy: 2,
      resolvedAt: ago(5, '10:00'),
      resolutionNote: 'Đã xin lỗi khách và nhắc nhóm chăm sóc kiểm tra lại giờ trả bé sau khi tắm.',
    }),
  ];
}

function load(): FeedbackState {
  try {
    const raw = localStorage.getItem(FEEDBACK_STATE_KEY);
    const saved = raw ? (JSON.parse(raw) as Partial<FeedbackState> | null) : null;
    if (Array.isArray(saved?.feedbacks)) return { feedbacks: saved.feedbacks.map((f) => ({ ...DEFAULTS, ...f })) };
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  return { feedbacks: seedFeedbacks() };
}

function save(s: FeedbackState) {
  try {
    localStorage.setItem(FEEDBACK_STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Dữ liệu vào, như Spring + Bean Validation của BE ───

const TOPICS = Object.keys(FEEDBACK_TOPIC_LABELS);
const STATUSES = Object.keys(FEEDBACK_STATUS_LABELS);
const INT = /^-?\d+$/;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** `yyyy-MM-dd` là ngày có thật (LocalDate của BE từ chối 2026-02-30 dù Date.parse của JS tự cuộn sang tháng sau). */
const isDay = (v: string) => {
  const ms = ISO_DAY.test(v) ? Date.parse(`${v}T00:00:00Z`) : NaN;
  return !Number.isNaN(ms) && new Date(ms).toISOString().slice(0, 10) === v;
};

/** Theo đúng thứ tự tham số của controller: Spring báo lỗi kiểu của tham số đầu tiên sai. */
const QUERY_TYPES: [name: string, valid: (v: string) => boolean][] = [
  ['branchId', (v) => INT.test(v)],
  ['status', (v) => STATUSES.includes(v)],
  ['topic', (v) => TOPICS.includes(v)],
  ['from', isDay],
  ['to', isDay],
  ['page', (v) => INT.test(v)],
  ['size', (v) => INT.test(v)],
];

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isObject = (b: unknown): b is Record<string, unknown> => typeof b === 'object' && b !== null && !Array.isArray(b);
const isInteger = (v: unknown) => typeof v === 'number' && Number.isInteger(v);

/** Như ContentText.normalize của BE: NFC rồi strip. */
const normalize = (text: string) => text.normalize('NFC').trim();

/** `yyyy-MM-dd` thành `dd/MM/yyyy`. */
const dayMonthYear = (day: string) => day.split('-').reverse().join('/');

const newestFirst = (a: FeedbackRecord, b: FeedbackRecord) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.feedbackId - a.feedbackId;

// ─── Quyền và phạm vi chi nhánh (BR-DG-03) ───

const REVIEWERS: readonly Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER'];
const ownBranch = (account: AccountSummary) => account.branchId ?? branchIdOf(account.id) ?? null;

/** BranchScope: SUPER_MANAGER thấy mọi bản ghi; BRANCH_MANAGER chỉ bản ghi gắn chi nhánh mình, bản ghi không gắn chi nhánh thì không. */
const inScope = (account: AccountSummary, branchId: number | null) =>
  account.role === 'SUPER_MANAGER' || (branchId !== null && branchId === ownBranch(account));

// ─── Dạng trả về ───

/** Dựng từng trường (không trải bản ghi) để ghi chú nội bộ và seenAt không lọt vào MyFeedback (BR-DG-04). */
const myView = (f: FeedbackRecord): MyFeedback => ({
  feedbackId: f.feedbackId,
  topic: f.topic,
  branchId: f.branchId,
  rating: f.rating,
  content: f.content,
  status: f.status,
  createdAt: f.createdAt,
});

/** Tên khách và tên chi nhánh (mọi trạng thái, như BranchQueryApi.findBranch) tra một lần cho mỗi request. */
function fullViews(records: FeedbackRecord[]): Feedback[] {
  const customers = new Map(allCustomers().map((c) => [c.customerId, c.fullName]));
  const branches = new Map<number, string | null>();
  const branchName = (id: number) => {
    if (!branches.has(id)) branches.set(id, branchBooking(id)?.branch.name ?? null);
    return branches.get(id) ?? null;
  };
  return records.map((f) => ({
    feedbackId: f.feedbackId,
    customerId: f.customerId,
    customerName: customers.get(f.customerId) ?? '',
    topic: f.topic,
    branchId: f.branchId,
    branchName: f.branchId === null ? null : branchName(f.branchId),
    rating: f.rating,
    content: f.content,
    status: f.status,
    seenAt: f.seenAt,
    resolvedBy: f.resolvedBy,
    resolvedAt: f.resolvedAt,
    resolutionNote: f.resolutionNote,
    createdAt: f.createdAt,
  }));
}

// ─── Khách: gửi và xem lại (#18, #19) ───

/**
 * Thứ tự (BE): body sai 400 → vai trò 403 → BR-TK-19 → nội dung → điểm → thiếu chi nhánh → chi nhánh không ACTIVE 404 → giới hạn ngày.
 * Feedback không sửa, không xóa.
 */
const submitFeedback: MockHandler = ({ account, body }) => {
  if (!isObject(body)) return malformed();
  const { topic, branchId, rating, content } = body;
  if (
    (has(topic) && !TOPICS.includes(topic as string)) ||
    (has(branchId) && !isInteger(branchId)) ||
    (has(rating) && !isInteger(rating)) ||
    (has(content) && typeof content !== 'string')
  ) {
    return malformed();
  }
  const bad = invalid([
    ['topic', 'Vui lòng chọn chủ đề', !has(topic)],
    ['branchId', 'Mã chi nhánh phải là số dương', has(branchId) && (branchId as number) <= 0],
    ['content', 'Vui lòng nhập nội dung', !has(content)],
  ]);
  if (bad) return bad;
  if (account!.role !== 'CUSTOMER') return denied();

  const me = customerOfAccount(account!.id)!;
  if (me.linkDecisionPending) return ruleFail('BR-TK-19', 'Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám');
  const text = normalize(content as string);
  const length = feedbackContentLength(text);
  if (length < FEEDBACK_CONTENT_MIN || length > FEEDBACK_CONTENT_MAX) {
    return ruleFail('BR-DG-01', `Nội dung feedback phải từ ${FEEDBACK_CONTENT_MIN} đến ${FEEDBACK_CONTENT_MAX} ký tự`);
  }
  if (has(rating) && ((rating as number) < 1 || (rating as number) > 5)) return ruleFail('BR-DG-01', 'Mức hài lòng phải từ 1 đến 5');
  if (topic === 'BRANCH_SERVICE' && !has(branchId)) {
    return ruleFail('BR-DG-01', 'Vui lòng chọn chi nhánh khi góp ý về dịch vụ tại chi nhánh');
  }
  if (has(branchId) && !activeBranchSummaries().some((b) => b.branchId === branchId)) return notFound('chi nhánh', branchId as number);

  const state = load();
  const now = Date.now();
  const today = vnDate(now);
  const sentToday = state.feedbacks.filter((f) => f.customerId === me.customerId && vnDate(Date.parse(f.createdAt)) === today).length;
  if (sentToday >= DAILY_LIMIT) {
    return ruleFail(
      'BR-DG-01',
      `Bạn đã gửi đủ ${DAILY_LIMIT} feedback hôm nay, có thể gửi tiếp từ 00:00 ngày ${dayMonthYear(vnDate(now + DAY_MS))}`,
    );
  }
  const created: FeedbackRecord = {
    ...DEFAULTS,
    feedbackId: Math.max(0, ...state.feedbacks.map((f) => f.feedbackId)) + 1,
    customerId: me.customerId,
    topic: topic as FeedbackTopic,
    branchId: has(branchId) ? (branchId as number) : null,
    rating: has(rating) ? (rating as number) : null,
    content: text,
    status: 'NEW',
    createdAt: new Date(now).toISOString(),
  };
  state.feedbacks.push(created);
  save(state);
  // Hồ sơ online đã có feedback thì không liên kết với hồ sơ tại quầy được nữa (BR-TK-19).
  markHasData(me.customerId);
  return ok(myView(created), 201);
};

/** Không có cổng BR-TK-19 (BE chỉ kiểm ở POST); chỉ feedback của chính mình, mới nhất trước, không phân trang. */
const listMyFeedbacks: MockHandler = ({ account }) => {
  if (account!.role !== 'CUSTOMER') return denied();
  const me = customerOfAccount(account!.id)!;
  return ok(
    load()
      .feedbacks.filter((f) => f.customerId === me.customerId)
      .sort(newestFirst)
      .map(myView),
  );
};

// ─── Quản lý: xem, mở, xử lý (#20–22) ───

/** Tham số sai kiểu (400) đến trước vai trò (403); từ đó `from > to` (400) đến trước phạm vi chi nhánh (403), như FeedbackReviewService.list. */
const listFeedbacks: MockHandler = ({ account, query: raw }) => {
  const query = Object.fromEntries(Object.entries(raw).filter(([, v]) => v !== ''));
  const wrongType = QUERY_TYPES.find(([name, valid]) => query[name] !== undefined && !valid(query[name]));
  if (wrongType) return fail('MALFORMED_REQUEST', `Tham số '${wrongType[0]}' sai kiểu dữ liệu`, 400);
  const bad = notPositive(query, ['branchId']);
  if (bad) return bad;
  if (!REVIEWERS.includes(account!.role)) return denied();
  if (query.from !== undefined && query.to !== undefined && query.from > query.to) {
    return fail('VALIDATION_FAILED', 'Ngày bắt đầu không được sau ngày kết thúc', 400);
  }
  const requested = query.branchId === undefined ? null : Number(query.branchId);
  if (account!.role === 'BRANCH_MANAGER' && requested !== null && requested !== ownBranch(account!)) return scopeDenied();

  // `from` 00:00 giờ Việt Nam (gồm), `to` hết ngày đó (gồm cả ngày `to`).
  const fromMs = query.from === undefined ? -Infinity : Date.parse(`${query.from}T00:00:00+07:00`);
  const toMs = query.to === undefined ? Infinity : Date.parse(`${query.to}T00:00:00+07:00`) + DAY_MS;
  const found = load()
    .feedbacks.filter((f) => {
      const at = Date.parse(f.createdAt);
      return (
        inScope(account!, f.branchId) &&
        (requested === null || f.branchId === requested) &&
        (query.status === undefined || f.status === query.status) &&
        (query.topic === undefined || f.topic === query.topic) &&
        at >= fromMs &&
        at < toMs
      );
    })
    .sort(newestFirst);
  const paged = pageOf(found, clampPaging(query));
  return ok({ ...paged, content: fullViews(paged.content) });
};

/** 404 trước, rồi 403 phạm vi (feedback không gắn chi nhánh chỉ SUPER_MANAGER mở được). */
function authorize(account: AccountSummary, feedbackId: string, state: FeedbackState): FeedbackRecord | MockReply {
  const found = state.feedbacks.find((f) => f.feedbackId === Number(feedbackId));
  if (!found) return notFound('feedback', feedbackId);
  return inScope(account, found.branchId) ? found : scopeDenied();
}
const isRecord = (v: FeedbackRecord | MockReply): v is FeedbackRecord => 'feedbackId' in v;

/** CÓ GHI DỮ LIỆU (A2): mở lần đầu NEW → SEEN, ghi `seenAt` một lần; mở lại không ghi gì. Ngoài phạm vi không đổi trạng thái. */
const openFeedback: MockHandler = ({ account, params }) => {
  const bad = typeError(params, ['feedbackId']);
  if (bad) return bad;
  if (!REVIEWERS.includes(account!.role)) return denied();
  const state = load();
  const found = authorize(account!, params.feedbackId, state);
  if (!isRecord(found)) return found;
  if (found.status === 'NEW') {
    found.status = 'SEEN';
    found.seenAt = new Date().toISOString();
    save(state);
  }
  return ok(fullViews([found])[0]);
};

/** Thứ tự (BE): path / body sai 400 → vai trò 403 → 404 → phạm vi 403 → ghi chú trống 400 BR-DG-04 → chỉ từ SEEN, còn lại 409. */
const resolveFeedback: MockHandler = ({ account, params, body }) => {
  const bad = typeError(params, ['feedbackId']);
  if (bad) return bad;
  if (!isObject(body) || (has(body.resolutionNote) && typeof body.resolutionNote !== 'string')) return malformed();
  const note = body.resolutionNote;
  const invalidBody = invalid([
    ['resolutionNote', 'Vui lòng nhập ghi chú xử lý', !has(note)],
    ['resolutionNote', `Ghi chú tối đa ${RESOLUTION_NOTE_MAX} ký tự`, tooLong(note, RESOLUTION_NOTE_MAX)],
  ]);
  if (invalidBody) return invalidBody;
  if (!REVIEWERS.includes(account!.role)) return denied();
  const state = load();
  const found = authorize(account!, params.feedbackId, state);
  if (!isRecord(found)) return found;

  const text = normalize(note as string);
  if (text === '') return ruleFail('BR-DG-04', 'Vui lòng nhập ghi chú xử lý');
  if (found.status !== 'SEEN') {
    return fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái Feedback từ ${found.status} sang RESOLVED`, 409);
  }
  found.status = 'RESOLVED';
  found.resolvedBy = account!.id;
  found.resolvedAt = new Date().toISOString();
  found.resolutionNote = text;
  save(state);
  return ok(fullViews([found])[0]);
};

/** Cho mock báo cáo (T49, như *ReportApi của BE, 06 §8 Q3): mọi feedback của mọi chi nhánh, chỉ đọc. */
export const feedbackRecords = (): readonly FeedbackRecord[] => load().feedbacks;

export const feedbackRoutes: Record<string, MockHandler> = {
  'POST /api/me/feedbacks': submitFeedback,
  'GET /api/me/feedbacks': listMyFeedbacks,
  'GET /api/feedbacks': listFeedbacks,
  'GET /api/feedbacks/:feedbackId': openFeedback,
  'POST /api/feedbacks/:feedbackId/resolve': resolveFeedback,
};
