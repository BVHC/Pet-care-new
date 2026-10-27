import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';

// Khách tại quầy của đặt chỗ lưu trú mẫu (Cốm #2 → khách 9002, Mực #3 → khách 9003) chưa có hồ sơ trong mock khách. Bàn Lễ tân vẫn phải
// thu được tiền trả thú của họ: `payable-orders` và chi tiết khách tra được theo id. Tra cứu này KHÔNG mở rộng danh sách / tìm kiếm khách
// và không đổi `findCustomer` (cổng chặn luật khác: đặt Order bán lẻ, hạn chế đặt online).
// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN).

let client: AxiosInstance;
beforeEach(() => {
  pinClock();
  client = makeClient();
});
afterEach(() => vi.useRealTimers());

const desk = () => authAs(client, ACCOUNTS.desk);

describe('khách tại quầy chỉ có tên trong đặt chỗ mẫu', () => {
  it('chi tiết khách 9002: có tên, SĐT, không thú cưng', async () => {
    const res = await client.get('/api/customers/9002', await desk());
    expect(res.data.data.customer).toMatchObject({ customerId: 9002, fullName: 'Đỗ Mai Phương', phone: '0933445566' });
    expect(res.data.data.pets).toEqual([]);
  });

  it('Order chờ thu: rỗng khi chưa trả thú, có Order lưu trú sau khi bắt đầu trả thú Cốm', async () => {
    expect((await client.get('/api/customers/9002/payable-orders', await desk())).data.data).toEqual([]);
    const started = await client.post('/api/boarding-bookings/2/start-checkout', undefined, await desk());
    const payable = (await client.get('/api/customers/9002/payable-orders', await desk())).data.data as { orderId: number; source: string; customerName: string }[];
    expect(payable).toHaveLength(1);
    expect(payable[0]).toMatchObject({ orderId: started.data.data.orderId, source: 'BOARDING', customerName: 'Đỗ Mai Phương' });
  });

  it('không có trong tìm kiếm khách (chỉ tra theo id)', async () => {
    const res = await client.get('/api/customers', { params: { phone: '0933445566' }, ...(await desk()) });
    expect(JSON.stringify(res.data.data)).not.toContain('Đỗ Mai Phương');
  });

  it('khách không tồn tại ở đâu cả vẫn 404; Order bán lẻ cho khách mẫu vẫn bị từ chối (findCustomer giữ nguyên)', async () => {
    expect((await failure(client.get('/api/customers/9999/payable-orders', await desk()))).status).toBe(404);
    expect((await failure(client.get('/api/customers/9999', await desk()))).status).toBe(404);
    expect((await failure(client.post('/api/orders', { customerId: 9002 }, await desk()))).status).toBe(404);
  });
});
