// Mock report-v1 (UC89; docs/api/report-v1.md #1–7): doanh thu, thất thu, lượt dịch vụ hoàn tất, NO_SHOW / hủy muộn, quay lại tái chủng,
// tồn kho cảnh báo, feedback. Chỉ đọc, KHÔNG state: mỗi request tính lại từ mock nguồn (sales, kho, feedback) và dữ liệu mẫu tất định
// trong file này, nên không có bẫy gộp state cũ. Mock nguồn export hàm đọc (`allOrderRows`, `stockSnapshot`, `feedbackRecords`) như các
// `*ReportApi` của BE (06 §8 Q3); không ai import file này nên import thẳng, không cần `register*Source`.
//
// BE report CHƯA CÓ (T44): hợp đồng + BR-BC-01…04 là nguồn duy nhất. Chỗ im lặng là ASSUMPTION của T49:
//  A1  Thứ tự kiểm: 401 → 400 tham số (đúng thứ tự `branchId`, `from`, `to` hoặc `branchId`, `withinDays`; thiếu / sai kiểu là 400 MALFORMED_REQUEST,
//      `branchId` ≤ 0 và `withinDays` < 0 là 400 VALIDATION_FAILED; Spring đọc tham số TRƯỚC @PreAuthorize) → 403 vai trò (chỉ SUPER_MANAGER và
//      BRANCH_MANAGER) → 403 phạm vi (ACCESS_DENIED_SCOPE_MISMATCH, message có đuôi "(BR-BC-01)") → 400 BR-BC-01 (`from` > `to`, rồi kỳ quá dài).
//      Giá trị rỗng (`from=`) bị adapter bỏ nên thành "Thiếu tham số". #6 không đọc `from`, `to` (gửi kèm cũng bỏ qua).
//  A2  Biên "12 tháng" ([CFG] report.max_period_months, mock không đọc cấu hình nên là hằng số): kỳ gồm cả hai đầu, hợp lệ khi `to` ≤ `from` + 12
//      tháng theo lịch như LocalDate.plusMonths (ngày vượt cuối tháng đích thì lấy ngày cuối tháng đó; 29/02/2024 + 12 tháng = 28/02/2025).
//      Nên `to` = đúng ngày này năm sau vẫn qua, thêm một ngày là 400. BE T44 có thể tính khác (vd đếm cả ngày đầu): đối chiếu khi có BE.
//  A3  SUPER_MANAGER bỏ trống `branchId` = toàn chuỗi, truyền thì lọc một chi nhánh (chi nhánh không có dữ liệu / không tồn tại → rows rỗng, hợp đồng
//      không có 404). BRANCH_MANAGER bỏ trống = chi nhánh mình. Tên chi nhánh tra ở mock chi nhánh (mọi trạng thái, như BranchQueryApi.findBranch).
//  A4  #1: Order PAID thật của mock bán hàng (ngày VN của `paidAt`, theo chi nhánh của Order, giá trị = tổng dòng theo đơn giá snapshot) CỘNG
//      BASELINE_REVENUE (doanh thu lịch sử tất định, vì mock bán hàng chỉ có 1 Order PAID mẫu nên kỳ mặc định sẽ rỗng). Mỗi (chi nhánh, nguồn)
//      có dữ liệu là một dòng; sắp chi nhánh rồi VISIT, RETAIL, BOARDING.
//  A5  #2: CHỈ Order CANCELLED có `cancelType` = UNPAID (hủy theo BR-BH-05). VISIT_CANCELLED, RETAIL_UNCLOSED và CHECKOUT_ABORTED (hủy phiên trả
//      thú lưu trú, BR-LT-09 / BR-BC-02) không tính. Ngày theo `cancelledAt` (giờ VN); mới nhất trước; `reason` thiếu → ''. Không có dữ liệu
//      lịch sử mẫu: đầu tháng kỳ "tháng này" có thể rỗng.
//  A6  #3, #4, #5: dòng mẫu tất định theo ngày tương đối với hôm nay (mock lịch hẹn / lượt khám quá ít dòng và không có khái niệm "đến hạn").
//      #3 tên và nhóm dịch vụ lấy danh mục hiện tại (không snapshot), chỉ MEDICAL / GROOMING. #4 và #5 trả MỘT DÒNG cho mỗi chi nhánh (#4) hoặc chi
//      nhánh × loại vaccine (#5) có trong dữ liệu mẫu, kể cả kỳ không phát sinh gì (đếm 0, tỷ lệ 0). KHÔNG mô phỏng BR-BC-04 (7 ngày, thú mất):
//      `returnedCount` là số mẫu, ngày mẫu là ngày tái chủng.
//  A7  Tỷ lệ là số thập phân 0–1 làm tròn 4 chữ số (A3 của hợp đồng); mẫu số 0 → tỷ lệ 0 (không NaN).
//  A8  #6: cảnh báo thiếu khi tồn khả dụng (lô chưa hết hạn, BR-KO-01) < ngưỡng (ngưỡng 0 không bao giờ báo). Lô liệt kê khi còn tồn > 0, có hạn
//      dùng và (hạn dùng − hôm nay) ≤ `withinDays` tính cả ngày thứ `withinDays`; `expired` = hạn dùng < hôm nay (giờ VN, như M4 của kho). Sắp hạn
//      sớm nhất trước. `withinDays` mặc định 30 [CFG] (hằng), kiểu int32.
//  A9  #7: `count` = mọi feedback tạo trong kỳ (ngày VN của `createdAt`) của nhóm; `averageRating` = trung bình các feedback CÓ điểm (rating
//      tùy chọn, content-v1), làm tròn 2 chữ số, null khi nhóm không có điểm nào. Feedback không gắn chi nhánh là dòng `branchId: null` (xếp cuối),
//      chỉ SUPER_MANAGER bỏ trống `branchId` thấy (BR-DG-03). Chỉ nhóm có ít nhất một feedback.
//  A10 Câu báo lỗi rule / phạm vi là văn bản tự đặt (BE chưa có): "Chỉ xem được báo cáo của chi nhánh mình (BR-BC-01)",
//      "Ngày bắt đầu không được sau ngày kết thúc (BR-BC-01)", "Kỳ báo cáo tối đa 12 tháng (BR-BC-01)". Mã lỗi và mã rule theo hợp đồng.
import type { Role } from '../../types/auth';
import type {
  CompletedServicesReport,
  ExpiringLotRow,
  FeedbackReport,
  FeedbackStatRow,
  LostRevenueReport,
  LostRevenueRow,
  NoShowReport,
  ReportServiceGroup,
  ReportSource,
  RevaccinationReport,
  RevenueReport,
  RevenueRow,
  ServiceCountRow,
  StockAlertReport,
} from '../../types/report';
import { vnDate } from '../../utils/vn-time';
import { branchBooking } from './branch.mock';
import { catalogProducts, catalogServices, catalogVaccineTypes } from './catalog.mock';
import { denied, fail, ok, ruleFail, type MockHandler, type MockReply } from './envelope';
import { feedbackRecords } from './feedback.mock';
import { branchIdOf } from './identity.mock';
import { stockSnapshot } from './inventory.mock';
import { notPositive } from './public.mock';
import { allOrderRows, orderTotal } from './sales.mock';

/** [CFG] `report.max_period_months` (BR-BC-01, seed V2 của BE). */
const REPORT_MAX_PERIOD_MONTHS = 12;
/** Mặc định của `withinDays` (BR-KO-07, A2 của hợp đồng). */
const DEFAULT_WITHIN_DAYS = 30;
const DAY_MS = 86_400_000;
const MANAGERS: readonly Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER'];
const SOURCE_ORDER: readonly ReportSource[] = ['VISIT', 'RETAIL', 'BOARDING'];
const GROUP_ORDER: readonly ReportServiceGroup[] = ['MEDICAL', 'GROOMING'];

// ─── Dữ liệu mẫu tất định: [số ngày trước hôm nay (giờ VN), chi nhánh, …] ───
// Có ngày 0 (hôm nay) để kỳ "tháng này" không rỗng kể cả mùng 1; chi nhánh 2 có dữ liệu trong 30 ngày qua, chi nhánh 3 chỉ ở quá khứ xa.

/** [ago, branchId, source, orderCount, amount] — doanh thu Order PAID lịch sử (A4). */
const BASELINE_REVENUE: readonly (readonly [ago: number, branchId: number, source: ReportSource, orderCount: number, amount: number])[] = [
  [0, 1, 'VISIT', 3, 690_000], [0, 1, 'RETAIL', 2, 468_000],
  [1, 1, 'VISIT', 4, 920_000], [1, 1, 'BOARDING', 1, 1_400_000],
  [2, 1, 'RETAIL', 3, 540_000],
  [4, 1, 'VISIT', 5, 1_150_000],
  [6, 1, 'VISIT', 3, 610_000], [6, 1, 'RETAIL', 1, 450_000],
  [9, 1, 'BOARDING', 2, 2_100_000],
  [13, 1, 'VISIT', 6, 1_480_000],
  [18, 1, 'RETAIL', 4, 830_000],
  [24, 1, 'VISIT', 4, 880_000],
  [29, 1, 'BOARDING', 1, 1_050_000],
  [41, 1, 'VISIT', 5, 1_020_000],
  [75, 1, 'RETAIL', 3, 610_000],
  [130, 1, 'VISIT', 7, 1_760_000],
  [220, 1, 'BOARDING', 2, 2_400_000],
  [300, 1, 'VISIT', 4, 940_000],
  [0, 2, 'VISIT', 2, 380_000],
  [3, 2, 'RETAIL', 2, 290_000],
  [8, 2, 'VISIT', 3, 560_000],
  [15, 2, 'BOARDING', 1, 900_000],
  [33, 2, 'VISIT', 4, 720_000],
  [90, 2, 'RETAIL', 2, 340_000],
  [60, 3, 'VISIT', 3, 510_000],
  [150, 3, 'RETAIL', 1, 175_000],
];

/** [ago, branchId, serviceId, count] — lượt hoàn tất (A6). Dịch vụ 1 Khám tổng quát, 2 Tiêm phòng, 3 Tắm & sấy, 4 Cắt tỉa lông. */
const SERVICES_DONE: readonly (readonly [ago: number, branchId: number, serviceId: number, count: number])[] = [
  [0, 1, 1, 6], [0, 1, 3, 4],
  [1, 1, 1, 9], [1, 1, 2, 5],
  [2, 1, 4, 2],
  [3, 1, 3, 7],
  [5, 1, 2, 6],
  [7, 1, 1, 8],
  [10, 1, 3, 5],
  [12, 1, 4, 3],
  [16, 1, 2, 4],
  [21, 1, 1, 10],
  [27, 1, 3, 6],
  [38, 1, 2, 5],
  [55, 1, 1, 12],
  [80, 1, 4, 4],
  [140, 1, 3, 9],
  [210, 1, 2, 7],
  [290, 1, 1, 11],
  [0, 2, 1, 3],
  [2, 2, 3, 2],
  [6, 2, 2, 4],
  [14, 2, 1, 5],
  [31, 2, 3, 3],
  [70, 2, 2, 2],
  [100, 3, 1, 4],
];

/** [ago, branchId, due, noShow, lateCancel] — lịch hẹn đến hạn trong ngày (A6). */
const APPOINTMENTS_DUE: readonly (readonly [ago: number, branchId: number, due: number, noShow: number, lateCancel: number])[] = [
  [0, 1, 14, 1, 0],
  [1, 1, 18, 2, 1],
  [2, 1, 12, 0, 1],
  [4, 1, 16, 1, 0],
  [6, 1, 15, 2, 2],
  [9, 1, 17, 1, 1],
  [13, 1, 20, 3, 1],
  [18, 1, 14, 0, 2],
  [24, 1, 16, 2, 0],
  [29, 1, 13, 1, 1],
  [41, 1, 18, 2, 1],
  [75, 1, 15, 1, 0],
  [130, 1, 19, 2, 2],
  [220, 1, 16, 1, 1],
  [300, 1, 17, 3, 1],
  [0, 2, 6, 0, 0],
  [3, 2, 8, 1, 0],
  [8, 2, 7, 0, 1],
  [15, 2, 9, 1, 1],
  [33, 2, 6, 0, 0],
  [90, 2, 7, 1, 0],
];

/** [ago (ngày tái chủng), branchId, vaccineTypeId, due, returned] — mũi có ngày tái chủng rơi vào ngày đó và số mũi tiêm lại đúng hạn (A6). */
const REVACCINATIONS: readonly (readonly [ago: number, branchId: number, vaccineTypeId: number, due: number, returned: number])[] = [
  [0, 1, 1, 3, 2],
  [1, 1, 2, 2, 1],
  [3, 1, 1, 4, 3],
  [5, 1, 4, 2, 2],
  [8, 1, 2, 3, 2],
  [11, 1, 1, 5, 3],
  [15, 1, 4, 3, 1],
  [20, 1, 2, 2, 2],
  [26, 1, 1, 4, 2],
  [34, 1, 4, 2, 1],
  [60, 1, 1, 5, 4],
  [100, 1, 2, 3, 2],
  [160, 1, 1, 6, 4],
  [240, 1, 4, 3, 3],
  [0, 2, 1, 2, 1],
  [4, 2, 1, 3, 2],
  [12, 2, 2, 2, 1],
  [30, 2, 1, 3, 2],
  [80, 2, 1, 2, 2],
];

// ─── Ngày ───

const dayNumber = (day: string) => Date.parse(`${day}T00:00:00Z`) / DAY_MS;
const agoDay = (ago: number) => vnDate(Date.now() - ago * DAY_MS);

/** `yyyy-MM-dd` là ngày có thật (LocalDate của BE từ chối 2026-02-30 dù Date.parse của JS tự cuộn sang tháng sau). */
const isDay = (v: string) => {
  const ms = /^\d{4}-\d{2}-\d{2}$/.test(v) ? Date.parse(`${v}T00:00:00Z`) : NaN;
  return !Number.isNaN(ms) && new Date(ms).toISOString().slice(0, 10) === v;
};

/** Như LocalDate.plusMonths (A2). */
function plusMonths(day: string, months: number): string {
  const [year, month, date] = day.split('-').map(Number);
  const index = year * 12 + (month - 1) + months;
  const targetYear = Math.floor(index / 12);
  const targetMonth = index % 12;
  const lastDay = new Date(Date.UTC(targetYear, targetMonth + 1, 0)).getUTCDate();
  return `${String(targetYear).padStart(4, '0')}-${String(targetMonth + 1).padStart(2, '0')}-${String(Math.min(date, lastDay)).padStart(2, '0')}`;
}

// ─── Tham số vào và phân quyền (A1) ───

interface Ask {
  /** null = toàn chuỗi. */
  branchId: number | null;
  from: string;
  to: string;
  withinDays: number;
}
type Kind = 'PERIOD' | 'STOCK';

const isInt = (min: number, max: number) => (v: string) => /^-?\d+$/.test(v) && Number(v) >= min && Number(v) <= max;
const VALID: Record<string, (v: string) => boolean> = {
  branchId: isInt(Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER),
  withinDays: isInt(-(2 ** 31), 2 ** 31 - 1),
  from: isDay,
  to: isDay,
};
const PARAMS: Record<Kind, string[]> = { PERIOD: ['branchId', 'from', 'to'], STOCK: ['branchId', 'withinDays'] };
const REQUIRED = new Set(['from', 'to']);

const malformed = (message: string) => fail('MALFORMED_REQUEST', message, 400);

/** Như Spring: tham số theo thứ tự khai báo, mỗi tham số kiểm sai kiểu rồi thiếu; ràng buộc `@Positive` / `@Min` sau cùng. */
function badParams(query: Record<string, string>, kind: Kind): MockReply | null {
  for (const name of PARAMS[kind]) {
    if (query[name] === undefined) {
      if (REQUIRED.has(name)) return malformed(`Thiếu tham số '${name}'`);
    } else if (!VALID[name](query[name])) {
      return malformed(`Tham số '${name}' sai kiểu dữ liệu`);
    }
  }
  if (kind === 'STOCK' && Number(query.withinDays) < 0) return fail('VALIDATION_FAILED', 'withinDays: Số ngày phải là số nguyên ≥ 0', 400);
  return notPositive(query, ['branchId']);
}

/** BR-BC-01: `from` > `to` (400), kỳ quá `report.max_period_months` (400). */
function badPeriod({ from, to }: Ask): MockReply | null {
  if (from > to) return ruleFail('BR-BC-01', 'Ngày bắt đầu không được sau ngày kết thúc');
  if (to > plusMonths(from, REPORT_MAX_PERIOD_MONTHS)) return ruleFail('BR-BC-01', `Kỳ báo cáo tối đa ${REPORT_MAX_PERIOD_MONTHS} tháng`);
  return null;
}

function guarded<T>(kind: Kind, build: (ask: Ask) => T): MockHandler {
  return ({ account, query }) => {
    const bad = badParams(query, kind);
    if (bad) return bad;
    const me = account!;
    if (!MANAGERS.includes(me.role)) return denied();
    const requested = query.branchId === undefined ? null : Number(query.branchId);
    // BRANCH_MANAGER không có chi nhánh (không xảy ra ở mock): -1 để không thấy gì, không bao giờ thành "toàn chuỗi".
    const own = me.branchId ?? branchIdOf(me.id) ?? -1;
    const manager = me.role === 'BRANCH_MANAGER';
    if (manager && requested !== null && requested !== own) {
      return fail('ACCESS_DENIED_SCOPE_MISMATCH', 'Chỉ xem được báo cáo của chi nhánh mình (BR-BC-01)', 403);
    }
    const ask: Ask = {
      branchId: manager ? own : requested,
      from: query.from ?? '',
      to: query.to ?? '',
      withinDays: query.withinDays === undefined ? DEFAULT_WITHIN_DAYS : Number(query.withinDays),
    };
    const rule = kind === 'PERIOD' ? badPeriod(ask) : null;
    return rule ?? ok(build(ask));
  };
}

// ─── Tiện ích tính ───

const inScope = (ask: Ask, branchId: number) => ask.branchId === null || ask.branchId === branchId;
const inPeriod = (ask: Ask, day: string) => ask.from <= day && day <= ask.to;
const sum = (xs: readonly number[]) => xs.reduce((a, b) => a + b, 0);
const branchName = (branchId: number) => branchBooking(branchId)?.branch.name ?? `Chi nhánh #${branchId}`;
const round = (x: number, digits: number) => Math.round(x * 10 ** digits) / 10 ** digits;
/** Tỷ lệ 0–1 (A7); mẫu số 0 → 0. */
const ratio = (numerator: number, denominator: number) => (denominator === 0 ? 0 : round(numerator / denominator, 4));

function groupBy<T>(items: readonly T[], key: (item: T) => string): T[][] {
  const groups = new Map<string, T[]>();
  for (const item of items) groups.set(key(item), [...(groups.get(key(item)) ?? []), item]);
  return [...groups.values()];
}

// ─── Báo cáo ───

/** #1 BR-BC-02: Order PAID có thời điểm thanh toán trong kỳ, ghi cho chi nhánh của Order, tách theo nguồn. */
function revenue(ask: Ask): RevenueReport {
  const live = allOrderRows().flatMap((o) =>
    o.status === 'PAID' && o.paidAt && inScope(ask, o.branchId) && inPeriod(ask, vnDate(Date.parse(o.paidAt)))
      ? [{ branchId: o.branchId, source: o.source, orderCount: 1, amount: orderTotal(o) }]
      : [],
  );
  const baseline = BASELINE_REVENUE.filter(([ago, branchId]) => inScope(ask, branchId) && inPeriod(ask, agoDay(ago))).map(
    ([, branchId, source, orderCount, amount]) => ({ branchId, source, orderCount, amount }),
  );
  const rows: RevenueRow[] = groupBy([...live, ...baseline], (e) => `${e.branchId}/${e.source}`)
    .map((g) => ({
      branchId: g[0].branchId,
      branchName: branchName(g[0].branchId),
      source: g[0].source,
      orderCount: sum(g.map((e) => e.orderCount)),
      amount: sum(g.map((e) => e.amount)),
    }))
    .sort((a, b) => a.branchId - b.branchId || SOURCE_ORDER.indexOf(a.source) - SOURCE_ORDER.indexOf(b.source));
  return { from: ask.from, to: ask.to, rows, totalAmount: sum(rows.map((r) => r.amount)) };
}

/** #2 BR-BC-02, BR-BH-05: chỉ Order hủy khi khách không thanh toán (UNPAID); hủy phiên trả thú (CHECKOUT_ABORTED) không tính (A5). */
function lostRevenue(ask: Ask): LostRevenueReport {
  const rows: LostRevenueRow[] = allOrderRows()
    .flatMap((o) =>
      o.status === 'CANCELLED' && o.cancelType === 'UNPAID' && o.cancelledAt && inScope(ask, o.branchId) && inPeriod(ask, vnDate(Date.parse(o.cancelledAt)))
        ? [{ orderId: o.orderId, orderCode: o.code, branchId: o.branchId, source: o.source, amount: orderTotal(o), reason: o.cancelReason ?? '', cancelledAt: o.cancelledAt }]
        : [],
    )
    .sort((a, b) => Date.parse(b.cancelledAt) - Date.parse(a.cancelledAt) || b.orderId - a.orderId);
  return { from: ask.from, to: ask.to, rows, count: rows.length, totalAmount: sum(rows.map((r) => r.amount)) };
}

/** #3: số lượt dịch vụ hoàn tất theo chi nhánh × dịch vụ (BR-BC-03 (2)). */
function completedServices(ask: Ask): CompletedServicesReport {
  const services = new Map(catalogServices().map((s) => [s.serviceId, s]));
  const done = SERVICES_DONE.filter(([ago, branchId]) => inScope(ask, branchId) && inPeriod(ask, agoDay(ago)));
  const rows: ServiceCountRow[] = groupBy(done, ([, branchId, serviceId]) => `${branchId}/${serviceId}`)
    .flatMap((g) => {
      const service = services.get(g[0][2]);
      return service && service.group !== 'BOARDING'
        ? [{ branchId: g[0][1], serviceGroup: service.group, serviceId: service.serviceId, serviceName: service.name, count: sum(g.map((e) => e[3])) }]
        : [];
    })
    .sort((a, b) => a.branchId - b.branchId || GROUP_ORDER.indexOf(a.serviceGroup) - GROUP_ORDER.indexOf(b.serviceGroup) || a.serviceId - b.serviceId);
  return { from: ask.from, to: ask.to, rows };
}

/** #4 BR-BC-03 (3): NO_SHOW và hủy muộn trên tổng lịch hẹn đến hạn trong kỳ, một dòng mỗi chi nhánh có dữ liệu mẫu (A6). */
function noShow(ask: Ask): NoShowReport {
  const branches = [...new Set(APPOINTMENTS_DUE.map(([, branchId]) => branchId))].filter((b) => inScope(ask, b)).sort((a, b) => a - b);
  const rows = branches.map((branchId) => {
    const mine = APPOINTMENTS_DUE.filter((r) => r[1] === branchId && inPeriod(ask, agoDay(r[0])));
    const dueCount = sum(mine.map((r) => r[2]));
    const noShowCount = sum(mine.map((r) => r[3]));
    const lateCancelCount = sum(mine.map((r) => r[4]));
    return { branchId, dueCount, noShowCount, lateCancelCount, noShowRate: ratio(noShowCount, dueCount), lateCancelRate: ratio(lateCancelCount, dueCount) };
  });
  return { from: ask.from, to: ask.to, rows };
}

/** #5 BR-BC-04: tỷ lệ quay lại tiêm đúng hạn, một dòng mỗi chi nhánh × loại vaccine có dữ liệu mẫu (A6). */
function revaccinationReturn(ask: Ask): RevaccinationReport {
  const names = new Map(catalogVaccineTypes().map((t) => [t.vaccineTypeId, t.name]));
  const series = groupBy(REVACCINATIONS, (r) => `${r[1]}/${r[2]}`)
    .map((g) => ({ branchId: g[0][1], vaccineTypeId: g[0][2] }))
    .filter((s) => inScope(ask, s.branchId))
    .sort((a, b) => a.branchId - b.branchId || a.vaccineTypeId - b.vaccineTypeId);
  const rows = series.map(({ branchId, vaccineTypeId }) => {
    const mine = REVACCINATIONS.filter((r) => r[1] === branchId && r[2] === vaccineTypeId && inPeriod(ask, agoDay(r[0])));
    const dueCount = sum(mine.map((r) => r[3]));
    const returnedCount = sum(mine.map((r) => r[4]));
    return { branchId, vaccineTypeId, vaccineTypeName: names.get(vaccineTypeId) ?? `Loại vaccine #${vaccineTypeId}`, dueCount, returnedCount, rate: ratio(returnedCount, dueCount) };
  });
  return { from: ask.from, to: ask.to, rows };
}

/** #6 BR-KO-07: tồn khả dụng dưới ngưỡng; lô còn tồn đã hết hạn hoặc hết hạn trong `withinDays` ngày (A8). */
function stockAlerts(ask: Ask): StockAlertReport {
  const today = vnDate();
  const names = new Map(catalogProducts().map((p) => [p.productId, p.name]));
  const nameOf = (productId: number) => names.get(productId) ?? `Sản phẩm #${productId}`;
  const items = stockSnapshot(today).filter((i) => inScope(ask, i.branchId));
  const lowStock = items
    .filter((i) => i.availableQuantity < i.minQuantity)
    .map((i) => ({ branchId: i.branchId, productId: i.productId, productName: nameOf(i.productId), availableQuantity: i.availableQuantity, minQuantity: i.minQuantity }))
    .sort((a, b) => a.branchId - b.branchId || a.productId - b.productId);
  const expiringLots: ExpiringLotRow[] = items
    .flatMap((i) =>
      i.lots.flatMap((l) =>
        l.quantity > 0 && l.expiryDate !== null && dayNumber(l.expiryDate) - dayNumber(today) <= ask.withinDays
          ? [{ branchId: i.branchId, productId: i.productId, productName: nameOf(i.productId), stockLotId: l.stockLotId, lotNumber: l.lotNumber, expiryDate: l.expiryDate, quantity: l.quantity, expired: l.expired }]
          : [],
      ),
    )
    .sort((a, b) => a.expiryDate.localeCompare(b.expiryDate) || a.branchId - b.branchId || a.stockLotId - b.stockLotId);
  return { lowStock, expiringLots };
}

/** #7 BR-BC-03 (6), BR-DG-03: số lượng và mức hài lòng trung bình theo chi nhánh (A9). */
function feedback(ask: Ask): FeedbackReport {
  const mine = feedbackRecords().filter(
    (f) => (f.branchId === null ? ask.branchId === null : inScope(ask, f.branchId)) && inPeriod(ask, vnDate(Date.parse(f.createdAt))),
  );
  const rows: FeedbackStatRow[] = groupBy(mine, (f) => String(f.branchId))
    .map((g) => {
      const branchId = g[0].branchId;
      const ratings = g.flatMap((f) => (f.rating === null ? [] : [f.rating]));
      return {
        branchId,
        branchName: branchId === null ? null : branchName(branchId),
        count: g.length,
        averageRating: ratings.length === 0 ? null : round(sum(ratings) / ratings.length, 2),
      };
    })
    .sort((a, b) => (a.branchId ?? Infinity) - (b.branchId ?? Infinity));
  return { from: ask.from, to: ask.to, rows };
}

export const reportRoutes: Record<string, MockHandler> = {
  'GET /api/reports/revenue': guarded('PERIOD', revenue),
  'GET /api/reports/lost-revenue': guarded('PERIOD', lostRevenue),
  'GET /api/reports/completed-services': guarded('PERIOD', completedServices),
  'GET /api/reports/no-show': guarded('PERIOD', noShow),
  'GET /api/reports/revaccination-return': guarded('PERIOD', revaccinationReturn),
  'GET /api/reports/stock-alerts': guarded('STOCK', stockAlerts),
  'GET /api/reports/feedback': guarded('PERIOD', feedback),
};
