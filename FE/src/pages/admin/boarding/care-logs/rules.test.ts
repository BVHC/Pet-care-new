import { describe, expect, it } from 'vitest';
import type { CareLog } from '@/shared/types/boarding';
import {
  ADDENDUM_MAX,
  addendumError,
  canEditEntry,
  careLogMode,
  draftOf,
  emptyDraft,
  groupByDate,
  minutesLeft,
  photoError,
  toCreateRequest,
  toUpdateRequest,
  validateDraft,
  type CareLogDraft,
} from './rules';

const log = (over: Partial<CareLog> = {}): CareLog => ({
  careLogId: 1,
  bookingId: 2,
  logDate: '2026-10-22',
  eating: null,
  drinking: null,
  hygiene: null,
  activity: null,
  note: null,
  photoUrls: [],
  isAbnormal: false,
  recordedBy: 6,
  recordedByName: 'Thu Hà',
  createdAt: '2026-10-23T02:00:00.000Z',
  editableUntil: '2026-10-23T03:00:00.000Z',
  addenda: [],
  ...over,
});

const draft = (over: Partial<CareLogDraft> = {}): CareLogDraft => ({ ...emptyDraft('2026-10-23'), eating: 'Ăn hết', ...over });
const CREATE = { creating: true, today: '2026-10-23' } as const;
const EDIT = { creating: false, today: '2026-10-23' } as const;

describe('careLogMode — nhật ký theo trạng thái đặt chỗ', () => {
  it.each([
    ['CHECKED_IN', 'inStay'],
    ['OVERDUE', 'inStay'],
    ['CHECKED_OUT', 'finished'],
    ['BOOKED', 'none'],
    ['CANCELLED', 'none'],
    ['NO_SHOW', 'none'],
  ] as const)('%s → %s', (status, mode) => expect(careLogMode(status)).toBe(mode));
});

describe('groupByDate', () => {
  it('ngày mới → cũ, cùng ngày thì id lớn trước, giữ các mục của từng ngày', () => {
    const groups = groupByDate([
      log({ careLogId: 1, logDate: '2026-10-21' }),
      log({ careLogId: 5, logDate: '2026-10-23' }),
      log({ careLogId: 3, logDate: '2026-10-22' }),
      log({ careLogId: 6, logDate: '2026-10-23' }),
    ]);
    expect(groups.map((g) => g.date)).toEqual(['2026-10-23', '2026-10-22', '2026-10-21']);
    expect(groups[0].logs.map((l) => l.careLogId)).toEqual([6, 5]);
  });

  it('rỗng → rỗng', () => expect(groupByDate([])).toEqual([]));
});

describe('minutesLeft / canEditEntry (BR-LT-11)', () => {
  const entry = log(); // hết hạn 03:00Z
  const at = (iso: string) => Date.parse(iso);

  it('làm tròn lên: còn 1 giây vẫn là 1 phút; đúng mốc là 0', () => {
    expect(minutesLeft(entry, at('2026-10-23T02:59:59.000Z'))).toBe(1);
    expect(minutesLeft(entry, at('2026-10-23T02:00:00.000Z'))).toBe(60);
    expect(minutesLeft(entry, at('2026-10-23T03:00:00.000Z'))).toBe(0);
    expect(minutesLeft(entry, at('2026-10-23T03:00:01.000Z'))).toBe(0);
  });

  it('sửa được khi: đang ở trong chuồng, chính mình ghi, còn hạn', () => {
    const now = at('2026-10-23T02:59:59.000Z');
    expect(canEditEntry(entry, { accountId: 6, inStay: true, now })).toBe(true);
    expect(canEditEntry(entry, { accountId: 5, inStay: true, now })).toBe(false);
    expect(canEditEntry(entry, { accountId: null, inStay: true, now })).toBe(false);
    expect(canEditEntry(entry, { accountId: 6, inStay: false, now })).toBe(false);
    expect(canEditEntry(entry, { accountId: 6, inStay: true, now: at('2026-10-23T03:00:00.000Z') })).toBe(false);
  });
});

describe('photoError', () => {
  it.each([
    ['https://img.example.com/a.jpg', undefined],
    ['  https://img.example.com/a.jpg  ', undefined],
    ['http://img.example.com/a.jpg', 'Ảnh phải là đường dẫn https (bắt đầu bằng https://)'],
    ['ftp://x/y.jpg', 'Ảnh phải là đường dẫn https (bắt đầu bằng https://)'],
    ['img.example.com/a.jpg', 'Ảnh phải là đường dẫn https (bắt đầu bằng https://)'],
    ['https://img.example.com/a b.jpg', 'Ảnh phải là đường dẫn https (bắt đầu bằng https://)'],
    ['https://', 'Ảnh phải là đường dẫn https (bắt đầu bằng https://)'],
    ['', undefined],
    ['   ', undefined],
  ])('%j', (url, error) => expect(photoError(url)).toBe(error));
});

describe('validateDraft', () => {
  it('hợp lệ → không lỗi', () => expect(validateDraft(draft(), CREATE)).toEqual({}));

  it('ngày nhật ký: bắt buộc khi tạo, không ở tương lai, ngày hôm nay và quá khứ qua; sửa thì bỏ qua', () => {
    expect(validateDraft(draft({ logDate: '' }), CREATE).logDate).toBe('Vui lòng chọn ngày nhật ký');
    expect(validateDraft(draft({ logDate: '2026-10-24' }), CREATE).logDate).toBe('Ngày nhật ký không được ở tương lai');
    expect(validateDraft(draft({ logDate: '2026-10-23' }), CREATE).logDate).toBeUndefined();
    expect(validateDraft(draft({ logDate: '2026-10-01' }), CREATE).logDate).toBeUndefined();
    expect(validateDraft(draft({ logDate: '2099-01-01' }), EDIT).logDate).toBeUndefined();
  });

  it('mỗi ô ngắn tối đa 300 ký tự (đếm sau khi cắt khoảng trắng đầu / cuối)', () => {
    for (const field of ['eating', 'drinking', 'hygiene', 'activity'] as const) {
      expect(validateDraft(draft({ [field]: 'a'.repeat(300) }), CREATE)[field]).toBeUndefined();
      expect(validateDraft(draft({ [field]: ' ' + 'a'.repeat(300) + ' ' }), CREATE)[field]).toBeUndefined();
      expect(validateDraft(draft({ [field]: 'a'.repeat(301) }), CREATE)[field]).toBe('Tối đa 300 ký tự');
    }
  });

  it('ghi chú không giới hạn ở UI (hợp đồng không nêu)', () => expect(validateDraft(draft({ note: 'n'.repeat(5000) }), CREATE)).toEqual({}));

  it('ảnh: tối đa 5 (ô trống không tính), mỗi ảnh phải https', () => {
    const five = Array.from({ length: 5 }, (_, i) => `https://i.example.com/${i}.jpg`);
    expect(validateDraft(draft({ photoUrls: five }), CREATE).photoUrls).toBeUndefined();
    expect(validateDraft(draft({ photoUrls: [...five, ''] }), CREATE).photoUrls).toBeUndefined();
    expect(validateDraft(draft({ photoUrls: [...five, 'https://i.example.com/6.jpg'] }), CREATE).photoUrls).toBe('Tối đa 5 ảnh');
    expect(validateDraft(draft({ photoUrls: ['http://i.example.com/a.jpg'] }), CREATE).photoUrls).toBe('Ảnh phải là đường dẫn https (bắt đầu bằng https://)');
  });

  it('mục hoàn toàn trống bị chặn (ASSUMPTION: BE / mock không chặn); chỉ ảnh, chỉ ghi chú, chỉ một ô vẫn qua; khoảng trắng không phải nội dung', () => {
    const blank = draft({ eating: '' });
    expect(validateDraft(blank, CREATE).form).toBe('Nhật ký trống: nhập ít nhất một nội dung hoặc một ảnh');
    expect(validateDraft(draft({ eating: '   ', note: '  ', photoUrls: ['  '] }), CREATE).form).toBeDefined();
    expect(validateDraft(draft({ eating: '', photoUrls: ['https://i.example.com/a.jpg'] }), CREATE).form).toBeUndefined();
    expect(validateDraft(draft({ eating: '', note: 'Ngoan' }), CREATE).form).toBeUndefined();
    expect(validateDraft(draft({ eating: '', activity: 'Chạy chơi' }), CREATE).form).toBeUndefined();
    expect(validateDraft(blank, EDIT).form).toBeDefined();
  });
});

describe('toCreateRequest / toUpdateRequest', () => {
  it('tạo: chỉ gửi ô có chữ (đã cắt khoảng trắng), ảnh bỏ ô trống, luôn có photoUrls và isAbnormal', () => {
    const body = toCreateRequest(
      draft({ logDate: '2026-10-22', eating: ' Ăn hết ', drinking: '', hygiene: '   ', note: ' Ngoan ', photoUrls: [' https://i.example.com/a.jpg ', ''], isAbnormal: true }),
    );
    expect(body).toStrictEqual({ logDate: '2026-10-22', eating: 'Ăn hết', note: 'Ngoan', photoUrls: ['https://i.example.com/a.jpg'], isAbnormal: true });
    expect(toCreateRequest(draft()).photoUrls).toEqual([]);
    expect(toCreateRequest(draft()).isAbnormal).toBe(false);
  });

  it('sửa: gửi đủ năm ô chữ (chuỗi trống = xóa) và ảnh; KHÔNG có isAbnormal / logDate', () => {
    const body = toUpdateRequest(draft({ eating: ' Ăn ít ', drinking: '', note: '', photoUrls: ['https://i.example.com/a.jpg'], isAbnormal: true, logDate: '2026-10-01' }));
    expect(body).toStrictEqual({ eating: 'Ăn ít', drinking: '', hygiene: '', activity: '', note: '', photoUrls: ['https://i.example.com/a.jpg'] });
    expect(Object.keys(body)).not.toContain('isAbnormal');
    expect(Object.keys(body)).not.toContain('logDate');
  });

  it('draftOf: nạp từ nhật ký đã lưu (null → chuỗi trống)', () => {
    expect(draftOf(log({ eating: 'Ăn', photoUrls: ['https://i.example.com/a.jpg'], isAbnormal: true }))).toEqual({
      logDate: '2026-10-22',
      eating: 'Ăn',
      drinking: '',
      hygiene: '',
      activity: '',
      note: '',
      photoUrls: ['https://i.example.com/a.jpg'],
      isAbnormal: true,
    });
  });
});

describe('addendumError', () => {
  it('1–2000 ký tự sau khi cắt khoảng trắng', () => {
    expect(addendumError('')).toBe('Vui lòng nhập nội dung bổ sung');
    expect(addendumError('   ')).toBe('Vui lòng nhập nội dung bổ sung');
    expect(addendumError('a')).toBeUndefined();
    expect(addendumError('a'.repeat(ADDENDUM_MAX))).toBeUndefined();
    expect(addendumError('a'.repeat(ADDENDUM_MAX + 1))).toBe('Nội dung tối đa 2000 ký tự');
  });
});
