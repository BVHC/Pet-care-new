// Phạm vi mock của chế độ hỗn hợp (VITE_API_MOCK_ONLY): đường dẫn nào vẫn trả bằng mock khi BE thật đã chạy.
// Mỗi mục: `[METHOD ]/duong/dan`; khớp theo tiền tố ở ranh giới đoạn; `*` thay đúng một đoạn. Ví dụ:
//   /api/public/branches, GET /api/pets/*/health-record

export interface MockRule {
  method?: string;
  segments: string[];
}

const segmentsOf = (path: string) => path.split('/').filter(Boolean);

export function parseMockOnly(raw: string | undefined): MockRule[] {
  const rules: MockRule[] = [];
  for (const entry of (raw ?? '').split(',')) {
    const parts = entry.trim().split(/\s+/);
    const path = parts.pop();
    if (!path || !path.startsWith('/') || parts.length > 1) continue;
    rules.push(parts.length ? { method: parts[0].toUpperCase(), segments: segmentsOf(path) } : { segments: segmentsOf(path) });
  }
  return rules;
}

export function isMockedRequest(method: string, pathname: string, rules: MockRule[]): boolean {
  const path = segmentsOf(pathname);
  return rules.some(
    (rule) =>
      (!rule.method || rule.method === method.toUpperCase()) &&
      rule.segments.length <= path.length &&
      rule.segments.every((s, i) => s === '*' || s === path[i]),
  );
}

/**
 * T67: `payment` chưa có controller (T65). Chỉ khi ĐẶT ĐƠN đi BE thật mà `pay` còn mock thì cổng thanh toán đóng: `pay` mock tra theo id của kho mock,
 * không biết đơn thật (404, hoặc trùng id một đơn mock của khách mock có cùng account id). Toàn mock giữ cổng giả lập của T57; toàn thật gọi `pay` thật.
 * Khi T65 xong và luật `pay` gỡ khỏi VITE_API_MOCK_ONLY thì điều kiện tự hết, không phải sửa mã.
 */
export function paymentClosedForRealOrders(apiMock: boolean, rules: MockRule[]): boolean {
  return !apiMock && !isMockedRequest('POST', '/api/me/online-orders', rules) && isMockedRequest('POST', '/api/me/online-orders/0/pay', rules);
}

/**
 * T68: BE chưa có `cancel-failed` (shop-v1 #19, task 138). Lễ tân thấy đơn THẬT, còn `cancel-failed` mock tra theo id của kho mock: trùng id thì hủy nhầm đơn mock
 * mà báo thành công, không trùng thì 404 / 409 sai. Nên chỉ khi danh sách đơn đi BE thật mà `cancel-failed` còn mock thì nút "Hủy đơn" khóa. Toàn mock giữ T57,
 * toàn thật gọi `cancel-failed` thật. Khi task 138 xong và luật gỡ khỏi VITE_API_MOCK_ONLY thì điều kiện tự hết, không phải sửa mã.
 */
export function cancelFailedClosedForRealOrders(apiMock: boolean, rules: MockRule[]): boolean {
  return !apiMock && !isMockedRequest('GET', '/api/online-orders', rules) && isMockedRequest('POST', '/api/online-orders/0/cancel-failed', rules);
}
