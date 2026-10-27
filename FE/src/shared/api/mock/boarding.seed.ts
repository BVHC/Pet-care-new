// Dữ liệu mẫu và hình dạng state của mock lưu trú (boarding.mock.ts). Ngày tính tương đối với lúc nạp lần đầu (T = hôm nay giờ Việt Nam).
//
// Chi nhánh 1 — loại chuồng #5 "Chuồng chó nhỏ" (chó ≤ 10 kg, 200.000 đ/đêm, bật tại CN1); loại #6 "Chuồng mèo" CHƯA bật tại CN1.
// Chuồng: #1 C-01 OCCUPIED (Cốm), #2 C-02 OCCUPIED (Mực), #3 C-03 AVAILABLE (đã có lịch sử lưu trú → xóa bị 409), #4 C-04 MAINTENANCE (xóa được).
// Sức chứa loại #5 = 3. Đặt chỗ (id tăng dần; thú / khách "mẫu" không có hồ sơ trong mock thú / khách, nên giữ tên đã lưu ngay trong đặt chỗ):
//   #1 Bơ     BOOKED      thứ Sáu kế → Chủ nhật  (đặt chỗ giả cũ của branch.mock, giữ mã / ngày / tên thú / khách)
//   #2 Cốm    CHECKED_IN  T−2 → T+8  chuồng C-01  chưa có nhật ký hôm nay; nhật ký T−1 bất thường kèm 1 bản bổ sung của VET
//   #3 Mực    CHECKED_IN  T−1 → T+9  chuồng C-02  đã có nhật ký hôm nay (ghi 30 phút trước, còn sửa được)
//   #4 Bim    BOOKED      thứ Hai kế → thứ Tư (Bim #2 của khach@petcare.vn: thiếu mũi 5 bệnh → BR-LT-05 từ chối nhận cho tới khi ghi mũi)
//   #5 Bim    CHECKED_OUT T−20 → T−17 chuồng C-03 (trả thú), 3 nhật ký bình thường
//   #6 Bim    CANCELLED   T+9 → T+11  khách hủy sớm (không hủy muộn): đặt lại cùng ngày được
//   #7 Nâu    CHECKED_OUT T−12 → T−10 do thú mất · #8 Tép CANCELLED do phòng khám · #9 Khoai NO_SHOW · #10 Đậu CANCELLED hủy muộn (lễ tân)
//   #11 Gạo   CHECKED_OUT T−9 → T−6
// Ràng buộc của test cũ: chỉ đặt chỗ BOOKED mới hiện ở branch.mock (hủy hàng loạt), nên đặt chỗ BOOKED KHÔNG chạm Chủ nhật, thứ Ba kế, hôm nay và ngày nghỉ T+20
// (branch.mock.test.ts chạy giờ thật); đặt chỗ đang lưu trú không bị ràng buộc đó. Không có đặt chỗ nào của khách #1 vi phạm (hủy muộn / NO_SHOW) để không đổi hạn chế đặt online
// mà test lịch hẹn đang dựa vào. Không sinh thông báo từ dữ liệu mẫu (khach@petcare.vn có đúng 26 thông báo, test chuông thông báo ghim số đó).
import { localIsoDate, nextWeekday } from '../../utils/branch-schedule';
import { shiftDate } from '../../utils/boarding';
import { vnDate } from '../../utils/vn-time';
import type {
  BookingCancelSource,
  BookingChannel,
  BookingEndReason,
  BookingStatus,
  KennelStatus,
} from '../../types/boarding';

export interface KennelRow {
  kennelId: number;
  branchId: number;
  kennelTypeId: number;
  code: string;
  note: string | null;
  status: KennelStatus;
}

/** Tình trạng lúc nhận (Đặt chỗ#3, BR-LT-08); không nằm trong `Booking` của hợp đồng nhưng BR-TB-05 cần SĐT khẩn. */
export interface CheckInDetails {
  weightKg: number;
  healthCondition: string;
  belongings: string | null;
  dietInstructions: string | null;
  emergencyPhone: string;
}

export interface BookingRow {
  bookingId: number;
  code: string;
  customerId: number;
  petId: number;
  branchId: number;
  kennelTypeId: number;
  kennelId: number | null;
  checkInDate: string;
  checkOutDate: string;
  actualCheckInAt: string | null;
  actualCheckOutAt: string | null;
  nightlyPrice: number;
  status: BookingStatus;
  endReason: BookingEndReason | null;
  channel: BookingChannel;
  lateCancel: boolean;
  cancelSource: BookingCancelSource | null;
  cancelReason: string | null;
  overdueSince: string | null;
  /** Lúc đặt chỗ rời BOOKED vì hủy / NO_SHOW: thời điểm vi phạm của khách (BR-LH-09, ADR-0065 D3). */
  endedAt: string | null;
  createdBy: number;
  /** Tên đã lưu lúc đặt: dùng khi thú / khách / loại chuồng không còn trong mock tương ứng (đặt chỗ mẫu). */
  petName: string;
  customerName: string;
  customerPhone: string | null;
  checkIn: CheckInDetails | null;
  /** Diễn biến khi kết thúc do thú mất (BR-LT-12). */
  endNote: string | null;
}

export interface CareLogRow {
  careLogId: number;
  bookingId: number;
  logDate: string;
  eating: string | null;
  drinking: string | null;
  hygiene: string | null;
  activity: string | null;
  note: string | null;
  photoUrls: string[];
  isAbnormal: boolean;
  recordedBy: number;
  createdAt: string;
}

export interface AddendumRow {
  addendumId: number;
  careLogId: number;
  content: string;
  createdBy: number;
  createdAt: string;
}

export interface State {
  kennels: KennelRow[];
  bookings: BookingRow[];
  careLogs: CareLogRow[];
  addenda: AddendumRow[];
  /** `document_counters` của mã LT-yyMM-nnnnn, theo kỳ yyMM (gap-free). */
  counters: Record<string, number>;
}

const BRANCH = 1;
const KENNEL_TYPE = 5;
const NIGHTLY = 200_000;
const DESK = 4;
const VET = 5;
const CARETAKER = 6;
const CUSTOMER_ACCOUNT = 7;
export const SEED_CODE = (period: string, n: number) => `LT-${period}-${String(n).padStart(5, '0')}`;

const instantOf = (date: string, time: string) => Date.parse(`${date}T${time}:00+07:00`);
const toIso = (ms: number) => new Date(ms).toISOString();

/** Người ngoài mock thú / khách: giữ tên ngay trong đặt chỗ. */
const GUESTS = {
  bo: { id: 9001, pet: 'Bơ', customer: 'Phạm Gia Bảo', phone: '0987654321' },
  com: { id: 9002, pet: 'Cốm', customer: 'Đỗ Mai Phương', phone: '0933445566' },
  muc: { id: 9003, pet: 'Mực', customer: 'Trịnh Quang Huy', phone: '0944556677' },
  nau: { id: 9004, pet: 'Nâu', customer: 'Vũ Thanh Hà', phone: '0966778899' },
  tep: { id: 9005, pet: 'Tép', customer: 'Ngô Bảo Châu', phone: '0912000111' },
  khoai: { id: 9006, pet: 'Khoai', customer: 'Lý Văn Sơn', phone: '0977111222' },
  dau: { id: 9007, pet: 'Đậu', customer: 'Hồ Ngọc Anh', phone: '0988222333' },
  gao: { id: 9008, pet: 'Gạo', customer: 'Phạm Minh Tuấn', phone: '0905333444' },
} as const;

export function seedState(now: number): State {
  const today = vnDate(now);
  // Ngày neo của đặt chỗ BOOKED: sau cả ngày giờ VN lẫn ngày giờ máy (branch.mock.test.ts tính "hôm nay" theo giờ máy).
  const anchor = [today, localIsoDate(new Date(now))].sort().pop()!;
  const d = (n: number) => shiftDate(today, n);
  const at = (date: string, time: string) => toIso(instantOf(date, time));
  const friday = nextWeekday(anchor, 5);
  const monday = nextWeekday(anchor, 1);
  const counters: Record<string, number> = {};
  const bookings: BookingRow[] = [];

  /** `createdOn` chỉ để đánh mã theo kỳ yyMM như BE (mã cấp lúc đặt); `r.code` có sẵn thì giữ nguyên và không dùng bộ đếm. */
  const add = (
    createdOn: string,
    r: Pick<BookingRow, 'petId' | 'customerId' | 'petName' | 'customerName' | 'customerPhone' | 'checkInDate' | 'checkOutDate' | 'status' | 'channel'> & Partial<BookingRow>,
  ) => {
    const period = createdOn.slice(2, 4) + createdOn.slice(5, 7);
    if (!r.code) counters[period] = (counters[period] ?? 0) + 1;
    bookings.push({
      bookingId: bookings.length + 1,
      code: r.code ?? SEED_CODE(period, counters[period]),
      branchId: BRANCH,
      kennelTypeId: KENNEL_TYPE,
      kennelId: null,
      actualCheckInAt: null,
      actualCheckOutAt: null,
      nightlyPrice: NIGHTLY,
      endReason: null,
      lateCancel: false,
      cancelSource: null,
      cancelReason: null,
      overdueSince: null,
      endedAt: null,
      createdBy: r.channel === 'ONLINE' ? CUSTOMER_ACCOUNT : DESK,
      checkIn: null,
      endNote: null,
      ...r,
    });
  };
  const guest = (g: (typeof GUESTS)[keyof typeof GUESTS]) => ({ petId: g.id, customerId: g.id, petName: g.pet, customerName: g.customer, customerPhone: g.phone });
  const bim = { petId: 2, customerId: 1, petName: 'Bim', customerName: 'Nguyễn Thu Trang', customerPhone: '0912345678' };
  const intake = (g: (typeof GUESTS)[keyof typeof GUESTS], weightKg: number, healthCondition: string): CheckInDetails => ({
    weightKg,
    healthCondition,
    belongings: 'Đệm nằm, 1 gói pate',
    dietInstructions: null,
    emergencyPhone: g.phone,
  });

  // #1 Bơ — đặt chỗ giả cũ của branch.mock (mã dạng cũ LT-yymmdd-0001).
  add(d(-3), {
    ...guest(GUESTS.bo), code: `LT-${friday.slice(2).replace(/-/g, '')}-0001`, checkInDate: friday, checkOutDate: shiftDate(friday, 2), status: 'BOOKED', channel: 'COUNTER',
  });
  // #2, #3 đang lưu trú
  add(d(-3), {
    ...guest(GUESTS.com), checkInDate: d(-2), checkOutDate: d(8), status: 'CHECKED_IN', channel: 'ONLINE', kennelId: 1,
    actualCheckInAt: at(d(-2), '09:20'), checkIn: intake(GUESTS.com, 8.4, 'Khỏe, hơi nhút nhát'),
  });
  add(d(-2), {
    ...guest(GUESTS.muc), checkInDate: d(-1), checkOutDate: d(9), status: 'CHECKED_IN', channel: 'COUNTER', kennelId: 2,
    actualCheckInAt: at(d(-1), '10:05'), checkIn: intake(GUESTS.muc, 7.1, 'Khỏe mạnh, ăn tốt'),
  });
  // #4 Bim sắp nhận · #5 đã trả · #6 đã hủy sớm
  add(d(-2), { ...bim, checkInDate: monday, checkOutDate: shiftDate(monday, 2), status: 'BOOKED', channel: 'ONLINE' });
  add(d(-25), {
    ...bim, checkInDate: d(-20), checkOutDate: d(-17), status: 'CHECKED_OUT', channel: 'ONLINE', kennelId: 3, endReason: 'RETURNED',
    actualCheckInAt: at(d(-20), '09:30'), actualCheckOutAt: at(d(-17), '10:15'),
    checkIn: { weightKg: 6.5, healthCondition: 'Khỏe mạnh', belongings: 'Đệm nằm', dietInstructions: null, emergencyPhone: '0987000111' },
  });
  add(d(-10), {
    ...bim, checkInDate: d(9), checkOutDate: d(11), status: 'CANCELLED', channel: 'ONLINE', cancelSource: 'CUSTOMER', cancelReason: 'Đổi kế hoạch công tác',
    endedAt: at(d(-3), '14:10'),
  });
  // #7–#11 lịch sử của khách mẫu
  add(d(-17), {
    ...guest(GUESTS.nau), checkInDate: d(-12), checkOutDate: d(-10), status: 'CHECKED_OUT', channel: 'COUNTER', kennelId: 3, endReason: 'PET_DECEASED',
    actualCheckInAt: at(d(-12), '08:45'), actualCheckOutAt: at(d(-11), '16:30'), checkIn: intake(GUESTS.nau, 9.2, 'Lớn tuổi, đang dùng thuốc tim'),
    endNote: 'Thú lên cơn khó thở lúc 16h, đã gọi chủ; mất trước khi kịp đưa đi cấp cứu',
  });
  add(d(-8), {
    ...guest(GUESTS.tep), checkInDate: d(3), checkOutDate: d(5), status: 'CANCELLED', channel: 'ONLINE', cancelSource: 'CLINIC', lateCancel: false,
    cancelReason: 'Chi nhánh nghỉ vào ngày nhận hoặc ngày trả của đặt chỗ', endedAt: at(d(-1), '10:00'),
  });
  add(d(-11), {
    ...guest(GUESTS.khoai), checkInDate: d(-6), checkOutDate: d(-4), status: 'NO_SHOW', channel: 'ONLINE', endedAt: at(d(-6), '19:00'),
  });
  add(d(-9), {
    ...guest(GUESTS.dau), checkInDate: d(-2), checkOutDate: d(-1), status: 'CANCELLED', channel: 'COUNTER', cancelSource: 'STAFF', lateCancel: true,
    cancelReason: 'Khách gọi báo bận đột xuất', endedAt: at(d(-3), '16:00'),
  });
  add(d(-14), {
    ...guest(GUESTS.gao), checkInDate: d(-9), checkOutDate: d(-6), status: 'CHECKED_OUT', channel: 'ONLINE', kennelId: 1, endReason: 'RETURNED',
    actualCheckInAt: at(d(-9), '09:00'), actualCheckOutAt: at(d(-6), '11:40'), checkIn: intake(GUESTS.gao, 5.8, 'Khỏe mạnh'),
  });

  const log = (
    careLogId: number,
    bookingId: number,
    logDate: string,
    recordedBy: number,
    createdAt: string,
    fields: Partial<CareLogRow> = {},
  ): CareLogRow => ({
    careLogId, bookingId, logDate, eating: null, drinking: null, hygiene: null, activity: null, note: null, photoUrls: [], isAbnormal: false, recordedBy, createdAt, ...fields,
  });
  const startOfToday = instantOf(today, '00:00');
  return {
    kennels: [
      { kennelId: 1, branchId: BRANCH, kennelTypeId: KENNEL_TYPE, code: 'C-01', note: null, status: 'OCCUPIED' },
      { kennelId: 2, branchId: BRANCH, kennelTypeId: KENNEL_TYPE, code: 'C-02', note: null, status: 'OCCUPIED' },
      { kennelId: 3, branchId: BRANCH, kennelTypeId: KENNEL_TYPE, code: 'C-03', note: null, status: 'AVAILABLE' },
      { kennelId: 4, branchId: BRANCH, kennelTypeId: KENNEL_TYPE, code: 'C-04', note: 'Hỏng khóa cửa, chờ thợ thay', status: 'MAINTENANCE' },
    ],
    bookings,
    careLogs: [
      log(1, 2, d(-2), CARETAKER, at(d(-2), '17:30'), {
        eating: 'Ăn hết 2 bữa', drinking: 'Uống đủ nước', hygiene: 'Đi vệ sinh bình thường', activity: 'Chạy chơi ở sân 30 phút', note: 'Bé hơi nhút nhát lúc mới vào chuồng',
        photoUrls: ['https://images.unsplash.com/photo-1587300003388-59208cc962cb?w=800', 'https://images.unsplash.com/photo-1543466835-00a7907e9de1?w=800'],
      }),
      log(2, 2, d(-1), VET, at(d(-1), '19:40'), { eating: 'Bỏ ăn bữa tối', drinking: 'Uống ít', hygiene: 'Nôn một lần', note: 'Bỏ ăn bữa tối, nôn một lần. Đã báo lễ tân và chủ thú.', isAbnormal: true }),
      log(3, 3, d(-1), CARETAKER, at(d(-1), '17:00'), { eating: 'Ăn tốt', drinking: 'Uống đủ nước', hygiene: 'Bình thường', activity: 'Dạo sân 20 phút' }),
      log(4, 3, today, CARETAKER, toIso(Math.max(now - 30 * 60_000, startOfToday)), { eating: 'Ăn hết khẩu phần sáng', drinking: 'Uống đủ nước', activity: 'Chơi với bóng' }),
      log(5, 5, d(-20), CARETAKER, at(d(-20), '17:00'), { eating: 'Ăn ít, làm quen chuồng', activity: 'Nằm yên quan sát' }),
      log(6, 5, d(-19), CARETAKER, at(d(-19), '17:00'), { eating: 'Ăn hết', drinking: 'Uống đủ nước', activity: 'Chạy chơi ở sân', photoUrls: ['https://images.unsplash.com/photo-1552053831-71594a27632d?w=800'] }),
      log(7, 5, d(-18), CARETAKER, at(d(-18), '17:00'), { eating: 'Ăn tốt', hygiene: 'Đã tắm nhẹ trước khi về', note: 'Bé ngoan, sẵn sàng về nhà ngày mai' }),
    ],
    addenda: [
      { addendumId: 1, careLogId: 2, content: 'Đã khám nhanh: bụng mềm, cho uống men tiêu hóa, theo dõi thêm.', createdBy: VET, createdAt: at(d(-1), '20:10') },
    ],
    counters,
  };
}
