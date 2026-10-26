import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSession } from '../stores/session.store';
import { apiClient } from './axios';
import { installMockApi } from './mock/mock-api';
import { MOCK_PASSWORD } from './mock/identity.mock';
import { visitApi } from './visit.api';

// visitApi gọi qua apiClient + mock thật (visit.mock.ts): kiểm đường dẫn, phương thức, tham số và việc bóc `data.data`.
// Giờ cố định thứ Sáu 23/10/2026 10:40 (giờ Việt Nam); dữ liệu mẫu xem đầu visit.mock.test.ts.
beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-23T10:40:00+07:00'));
  useSession.getState().clear();
});
afterEach(() => vi.useRealTimers());

const login = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });
const ids = (visits: { visitId: number }[]) => visits.map((v) => v.visitId);

describe('visitApi', () => {
  it('listQueue: mảng Visit theo BR-TN-04; tham số lọc gửi dạng query', async () => {
    await login('reception@store1.vn');
    expect(ids(await visitApi.listQueue())).toEqual([2101, 2002, 2104, 2102, 2103]);
    expect(ids(await visitApi.listQueue({ status: 'WAITING', serviceGroup: 'GROOMING' }))).toEqual([2103]);
    expect(ids(await visitApi.listQueue({ needsReassign: true }))).toEqual([2104]);
    expect(ids(await visitApi.listQueue({ date: '2026-10-10' }))).toEqual([2001]);
  });

  it('getVisit lấy chi tiết; getMyQueue chỉ lượt gán cho người gọi', async () => {
    await login('doctor@store1.vn');
    expect((await visitApi.getVisit(2101)).petName).toBe('Miu');
    expect(ids(await visitApi.getMyQueue())).toEqual([2002, 2102]);
  });

  it('checkIn walk-in / cấp cứu / lịch hẹn → Visit', async () => {
    await login('reception@store1.vn');
    const walkIn = await visitApi.checkIn({ petId: 3, serviceId: 1 });
    expect(walkIn).toMatchObject({ status: 'WAITING', priorityClass: 'WALK_IN', appointmentId: null });
    const urgent = await visitApi.checkIn({ petId: 2, serviceId: 3, isEmergency: true });
    expect(urgent.priorityClass).toBe('EMERGENCY');
    const booked = await visitApi.checkIn({ petId: 1, appointmentId: 1 });
    expect(booked).toMatchObject({ appointmentId: 1, priorityClass: 'APPOINTMENT' });
  });

  it('assign trả { visit, assigneeOffline }; move trả cả hàng đợi; markEmergency và cancel trả Visit', async () => {
    await login('reception@store1.vn');
    const assigned = await visitApi.assign(2101, { assigneeId: 5 });
    expect(assigned).toMatchObject({ assigneeOffline: false, visit: { visitId: 2101, assigneeId: 5 } });
    expect(ids(await visitApi.move(2103, { placeBeforeVisitId: 2102 }))).toEqual([2101, 2002, 2104, 2103, 2102]);
    expect((await visitApi.markEmergency(2102)).priorityClass).toBe('EMERGENCY');
    expect(await visitApi.cancel(2103, { reason: 'Khách bận' })).toMatchObject({ status: 'CANCELLED', cancelReason: 'Khách bận' });
    expect((await visitApi.cancel(2101)).cancelReason).toBeNull();
  });

  it('call và reassign', async () => {
    await login('doctor@store1.vn');
    expect(await visitApi.call(2102)).toMatchObject({ status: 'IN_PROGRESS', assigneeId: 5 });
    await login('manager@store1.vn');
    expect(await visitApi.reassign(2104, { assigneeId: 6, reason: 'Người phụ trách đã nghỉ' })).toMatchObject({ assigneeId: 6, needsReassign: false });
  });

  it('listArrivals: lịch BOOKED hôm nay trong cửa sổ check-in (appointment-v1 #3 checkInReady), bỏ phân trang mặc định 20', async () => {
    await login('reception@store1.vn');
    const page = await visitApi.listArrivals();
    expect(page.content.map((a) => a.appointmentId)).toEqual([1]);
    expect(page.size).toBe(100);
    await visitApi.checkIn({ petId: 1, appointmentId: 1 });
    expect((await visitApi.listArrivals()).content).toEqual([]);
  });
});
