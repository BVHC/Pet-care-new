# ADR-0123: FE nối refresh token (D055) — làm mới theo thao tác của người dùng, poll không bao giờ làm mới

- **Trạng thái:** Proposed (FE-1 / Hiến làm; đã kiểm với BE thật 7/11, xem mục 6)
- **Ngày:** 2026-11-07
- **Liên quan:** ADR-0091 (bổ sung, không thay: access token 10 phút + refresh token xoay vòng; ADR này là phía FE của D055), ADR-0005 (path public bỏ bearer), ADR-0003 (hạn phiên là trần tuyệt đối). Nợ: D055, D024.
- **Triển khai:** `FE/src/shared/stores/session.store.ts` (`refreshSession`, `needsRefresh`), `shared/api/axios.ts` (cờ `poll` / `POLL`, interceptor), `shared/hooks/useAccessValid.ts`, `shared/api/auth.api.ts` (`refresh`), `shared/api/mock/identity.mock.ts` (#39, `mockSessionConfig`); test `session.refresh.test.ts`, `session.refresh.tabs.test.ts`, `pollHooks.test.tsx`, `useAccessValid.test.tsx`, `identity.refresh.mock.test.ts`.

## Bối cảnh

BE cấp access token sống 10 phút (`session.access_ttl_minutes`). FE trước đây coi mọi 401 là hết phiên và không gọi `POST /auth/refresh`, nên với BE thật mọi người bị đăng xuất sau 10 phút. ADR-0091 đặt năm quy tắc cho FE (refresh chỉ khi người dùng thao tác; một lượt tại một thời điểm giữa các tab; thử lại một lần khi 401 do token vừa đổi; 401 từ refresh kết thúc phiên; cập nhật axios / store / mock). ADR này ghi các lựa chọn FE mà ADR-0091 không nêu.

## Quyết định

1. **Làm mới khi còn dưới 5 phút**, bởi (a) request không phải poll, (b) cú bấm chuột hoặc phím thật (`pointerdown` / `keydown`, capture). Không timer, không poll.
2. **Poll được đánh dấu** bằng `poll: true` trong cấu hình axios (hằng `POLL`, truyền qua tham số `config` của hàm API: `visitApi.listQueue(query, POLL)`). Poll không bao giờ làm mới (làm vậy tái tạo D024); nó chỉ đợi lượt làm mới đang chạy.
3. **Poll dừng khi access token hết hạn** (`useAccessValid` đưa vào `enabled` của `useVisitQueue`, `useMyQueue`, `useArrivals`, `usePendingSalesOrders`, `useUnreadCount`, `useStayCareLogs`), chạy lại khi token mới về.
4. **401 của poll với cùng token không kết thúc phiên và không làm mới.** Lệch đồng hồ máy / BE sẽ đăng xuất người đang rảnh đúng lúc hết hạn thay vì chỉ tạm dừng poll. Poll chỉ được thử lại một lần khi token trong storage đã đổi (tab này hoặc tab khác vừa xoay). Giá phải trả: phiên bị BE hủy thì poll báo lỗi cho tới khi người dùng thao tác hoặc token hết hạn.
5. **Cú bấm chuột / phím cũng làm mới** vì trang chỉ toàn poll (hàng đợi lễ tân) sau khi để rảnh quá hạn không phát request nào của người dùng, người dùng bấm vào sẽ chỉ gặp 401. Người rảnh không phát sự kiện nên không tái tạo D024.
6. **Khóa liên tab bằng Web Locks**, trong khóa đọc lại storage (tab khác có thể đã xoay token; refresh token cũ dùng lại hủy cả phiên). Không có `navigator.locks` thì chỉ còn single-flight trong tab (`shortcut:` trong code). **Không** dùng BroadcastChannel dự phòng: nó chỉ gửi tin, không loại trừ lẫn nhau.
7. **Path public** (`/api/auth/(register|login|refresh|password)`, `/api/public/`) không làm mới và không thử lại; `/auth/refresh` gửi không kèm bearer. Hệ quả: đăng nhập sai khi đang có phiên không còn xóa phiên.
8. **Lưu trữ:** `refreshToken` và `accessTokenExpiresAt` nằm trong localStorage `petcare-session` cùng access token (ADR-0091 loại cookie `httpOnly`). Cùng rủi ro XSS nhưng sống tới hết phiên (12 giờ). Phiên mở trước V27 không có hai trường này: không làm mới, 401 kết thúc phiên như trước.

## Phương án bị loại

- Đánh dấu poll bằng header: cần BE chấp nhận header lạ, lộ ra CORS.
- Phát hiện poll bằng cờ toàn cục đặt quanh lời gọi: interceptor của axios chạy bất đồng bộ nên cờ đã tắt trước khi đọc.
- Làm mới theo hoạt động gần nhất (poll được làm mới nếu có thao tác từ lần trước): không đúng chữ của ADR-0091 ("không bao giờ từ request poll").

## Kiểm thử

Test viết trước (đỏ đúng lý do rồi xanh). Đột biến, mỗi cái làm test đỏ: poll được phép làm mới; bỏ khóa liên tab; bỏ đọc lại storage trong khóa; poll 401 kết thúc phiên; bỏ listener; hook bỏ cổng `useAccessValid`. Hai tab mô phỏng bằng hai bản module cùng localStorage và `navigator.locks` giả (jsdom không có).

Mock `identity.mock.ts`: #39 đúng schema, xoay token, dùng lại token cũ hủy phiên, thông điệp 401 chung chép từ BE. Token trần `mock-token-<id>` vẫn hợp lệ, không hết hạn. `mockSessionConfig` mặc định 10 phút / 12 giờ khi dev, không hết hạn khi test (nhiều test dịch `Date` hàng giờ sau khi đăng nhập); test refresh tự đặt.

## 6. Kiểm với BE thật và phần còn mở

- **Đã kiểm 7/11/2026** (script một lần, không lưu trong repo nên không chạy lại được; Chrome thật, BE `petcare-api` cổng 8081, FE cổng 3010 với `VITE_API_MOCK=false`): 11/11 đạt. Nạp trang khi hạn access trong storage còn 3 phút: đúng 1 lượt `/auth/refresh`, không 401. Access token hỏng: 401, một lượt refresh, request được thử lại thành công. Hai tab nạp lại đồng thời (Web Locks thật): đúng 1 lượt refresh, cả hai còn phiên. Dùng lại refresh token cũ: 401 "Refresh token không hợp lệ hoặc đã hết hạn" và token mới của cùng phiên cũng chết.
- **Chưa kiểm:** hết hạn thật phía BE (TTL ngắn thật). Phép thử giả hạn bằng cách sửa localStorage và không đổi `session.access_ttl_minutes` của BE dùng chung.
- Sau khi FE này vào `dev`, máy demo có thể đặt lại `session.access_ttl_minutes` về 10 (đang để 720 để né lỗi này).
- `CLAUDE.md` mục BE (dòng "D055 (FE must wire refresh)") và ADR-0091 ("D055 (mở)") cập nhật khi commit D055 vào `dev`.
