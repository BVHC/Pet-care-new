# ADR-0121: CI chạy trên `dev`, integration test chỉ ở `main` hoặc chạy tay (trả D060)

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-07
- **Liên quan:** ADR-0110 (Testcontainers `pgvector/pgvector:pg17`). CLAUDE.md *Gotchas* (conflict marker đã bị commit sáu lần; `mvn clean verify` bị kill vì thiếu RAM). Nợ: D060 (trả, phương án A), D055 (không liên quan).
- **Triển khai:** `.github/workflows/ci.yml`, `FE/tsconfig.json` (`exclude`). Không code BE, không migration.

Người dùng duyệt phương án A của D060 (sửa tối thiểu) ngày 2026-11-07 và chốt hai việc ngoài A bên dưới.

## Vấn đề

`ci.yml` chưa từng chạy được: trigger `main, develop` trong khi nhánh làm việc là `dev` (PR vào `dev` không chạy gì); bước `mvn flyway:migrate` gọi plugin không có trong `BE/pom.xml`. Hệ quả: không có kiểm tự động trên PR, nên lỗi biên dịch và conflict marker bị commit chỉ lộ khi có người chạy tay. (File nợ D060 ghi thêm "Postgres không phải pgvector": sai, `services.postgres.image` đã là `pgvector/pgvector:pg17`; service đó không được dùng vì IT dùng Testcontainers.)

## Quyết định

1. **Trigger `main, dev`** cho `push` và `pull_request`, thêm `workflow_dispatch`. `deploy-staging` đổi sang `refs/heads/dev` (vẫn chỉ là `echo`).
2. **`backend-ci`:** bước đầu `git grep -nE '^(<<<<<<<|>>>>>>>) '` (thấy là fail), rồi `mvn clean compile -DskipTests`, rồi `mvn test` (unit; `PetcareApplicationTests` cần Docker, runner `ubuntu-latest` có sẵn). Bỏ `mvn flyway:migrate`, bỏ `-Dflyway.*` (Spring không đọc property này; migration chạy khi test khởi động ứng dụng) và bỏ hai khối `services: postgres` không ai dùng.
3. **`integration-tests` (`mvn verify` toàn bộ, ≈196 lớp IT) chỉ chạy khi push vào `main` hoặc `workflow_dispatch`**, kèm `timeout-minutes: 180`. PR vào `dev` thì job bị bỏ qua. Lý do: máy dev bị kill vì thiếu RAM khi chạy hết, thời gian và RAM trên runner chưa đo; một PR treo hàng giờ hoặc bị OOM tệ hơn không có kiểm IT trên PR.
4. **Loại 3 file test khỏi `tsc`:** `FE/src/test/seed-dev.dry-run.test.ts`, `seed-dev-phase2.test.ts`, `seed-dev-phase2.fake-be.ts` import `FE/scripts/seed-dev/*`, thư mục bị gitignore, nên `tsc --noEmit` báo 63 lỗi trên checkout sạch và job FE đỏ ngay khi trigger đúng. Vitest vẫn chạy các test này khi có `FE/scripts/`.
5. **Không đổi:** `npm run lint || true` (chỉ bỏ sau khi FE sạch lint), `docker.yml` (chỉ trigger `main`), lệnh triển khai (placeholder).

## Hệ quả

- PR vào `dev` có: marker, compile, unit test, `tsc`, build FE. **Lỗi chỉ IT mới thấy sẽ lộ muộn**, lúc push vào `main` hoặc khi ai đó chạy tay; vì vậy vẫn phải chạy IT theo nhóm cục bộ trước khi merge.
- `deploy-staging` không chạy trên PR hay push `dev` (nó `needs` `integration-tests` đang bị bỏ qua); chấp nhận vì chưa có lệnh triển khai.
- Nếu repo bật branch protection theo tên check, tên check không đổi nhưng `integration-tests` sẽ "skipped" trên PR.
- **Chưa chứng minh bằng một lần chạy thật trên GitHub Actions**: đã kiểm cục bộ cú pháp YAML, lệnh bước marker (có marker thì fail, sạch thì pass), `mvn clean compile`, `mvn test` (5689 unit test xanh), `tsc --noEmit` (exit 0), `npm run lint` (exit 0, nên `|| true` đã gỡ được), `npm run build` (exit 0). Thời gian từng job trên runner chưa biết.

## Hướng mở rộng (chưa làm)

- **B:** chạy IT theo nhóm module trên CI (`-Dit.test='com/petcare/module/<m>/**/*IT'`) khi đã đo thời gian / RAM runner.
- **C:** bỏ `integration-tests` hẳn, chỉ để kiểm nhanh.
- Bỏ `|| true` ở lint khi `npm run lint` sạch.
