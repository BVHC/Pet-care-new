# ADR-0156: Tải ảnh sản phẩm lên Cloudinary từ FE (unsigned preset), BE không đổi

- **Trạng thái:** Proposed (làm theo yêu cầu của Hiến ngày 2026-11-27; module catalog là của BE-2 nhưng BE, DB và contract không đổi)
- **Ngày:** 2026-11-27
- **Liên quan:** ADR-0151 (gallery; mục "Không làm: tải tệp lên"), catalog-v1 Q1 ("Ảnh sản phẩm tải lên bằng cơ chế nào?", đang TBD), `FE/src/pages/admin/catalog/ProductsTab.tsx`

## Bối cảnh

Form tạo / sửa sản phẩm (UC29) chỉ nhận URL dán tay. Đo trên code ngày 2026-11-27: form **không có ô ảnh bìa** (`draftOf` và thân gửi đi không có `imageUrl`), nên sản phẩm tạo từ giao diện luôn có `imageUrl = null`; thẻ cửa hàng, giỏ hàng và gợi ý AI đều đọc `imageUrl` nên không có ảnh. Ảnh phụ (`galleryUrls`) chỉ nhập được bằng cách dán URL. BE chưa có kho tệp, không có multipart, không có SDK Cloudinary (ADR-0151 mục 6: `imageUrl` / `galleryUrls` là chuỗi tự do ≤ 500 ký tự, không kiểm giao thức).

## Quyết định

1. **FE tải thẳng lên Cloudinary bằng unsigned preset** rồi lưu `secure_url` (https, khoảng 100–150 ký tự, nằm trong giới hạn 500) vào `imageUrl` / `galleryUrls` như một URL bình thường. `POST /products` và `PATCH /products/{id}` không đổi: BE, DB, OpenAPI (`OpenApiContractParityIT` vẫn 248) không đổi.
2. **Dùng `fetch` riêng, không dùng `apiClient`** (`FE/src/shared/utils/cloudinary.ts`). `apiClient` gắn JWT của người dùng vào mọi request, ép `Content-Type` JSON, đặt timeout 10 giây và đi qua lớp mock; không thứ nào hợp với một request tới bên thứ ba. Test ghim `headers` không được đặt.
3. **Cấu hình bằng hai biến FE** `VITE_CLOUDINARY_CLOUD_NAME` và `VITE_CLOUDINARY_UPLOAD_PRESET` (`FE/.env`, hướng dẫn ở `FE/.env.example`). Cả hai nằm trong bundle nên **không phải bí mật**; **API secret không bao giờ vào FE** (không đặt trong biến `VITE_*`, vì `FE/Dockerfile` copy `.env` vào bản build). Thiếu một trong hai: hai nút tải lên bị tắt kèm dòng chữ hiện sẵn "Chưa cấu hình Cloudinary (xem FE/.env.example)." (không dùng `title`: nút tắt có `pointer-events-none` nên tooltip không hiện), dán URL tay vẫn dùng như trước.
4. **Kiểm ở FE trước khi gửi:** tệp phải là `image/*`, tối đa 5 MB. Mọi lỗi (chưa cấu hình, sai loại, quá lớn, Cloudinary từ chối, mất mạng, phản hồi không có `secure_url`) ném `Error` có câu tiếng Việt để hiện thẳng. Preset (tạo trên Cloudinary, Signing mode = Unsigned) nên đặt thư mục `petcare/products` và định dạng cho phép jpg / png / webp.
5. **Form sản phẩm:** thêm ô "Ảnh bìa" (xem trước, dán URL hoặc nút "Tải ảnh bìa lên"); mục ảnh phụ có thêm nút "Tải ảnh phụ lên" chọn nhiều tệp, thêm vào cuối danh sách theo thứ tự chọn, nhận tối đa số chỗ còn trống (tệp thừa bị bỏ và báo). Tệp lỗi giữa chừng thì dừng, giữ các ảnh đã tải xong. **PATCH:** `imageUrl` chưa đổi thì không gửi (vắng = giữ nguyên), xóa trắng ô thì gửi `""` (xóa); tạo mới để trống thì bỏ trường. Đang tải dở mà bấm Lưu thì chặn và nhắc đợi, để ảnh không bị mất âm thầm.
6. **Component dùng lại được:** `FE/src/components/admin/ImageUploadButton.tsx` không biết gì về sản phẩm.

## Hệ quả và giới hạn đã biết

- **Không xác thực người tải lên.** Ai có cloud name và tên preset (đều đọc được trong bundle FE) đều tải được vào tài khoản Cloudinary, chỉ bị giới hạn bởi cấu hình preset (thư mục, định dạng) và hạn mức gói. Chấp nhận cho đồ án. Cần chặt hơn thì chuyển sang signed upload: ADR mới, một endpoint BE chỉ SUPER_MANAGER ký tham số bằng API secret, cập nhật `docs/api/generator` và `OpenApiContractParityIT`; phần FE chỉ đổi cách lấy tham số.
- **Ảnh mồ côi.** Hủy form, thay ảnh bìa hoặc xóa ảnh phụ không xóa tệp trên Cloudinary (xóa cần chữ ký, tức signed API). Tệp chỉ chiếm dung lượng gói.
- **BE vẫn nhận URL bất kỳ** (ADR-0151 mục 6 giữ nguyên): không có chuyện BE kiểm URL thuộc Cloudinary.
- **Mock.** Với `VITE_API_MOCK=true` nút vẫn tải ảnh thật lên Cloudinary (cần mạng và đã cấu hình) rồi lưu URL vào dữ liệu mock. Test thay lớp mạng: `cloudinary.test.ts` stub `fetch`, `AdminCatalogPage.upload.test.tsx` thay module bằng `vi.mock`; form, mock BE và luật PATCH chạy thật.
- **Contract:** catalog-v1 Q1 vẫn ghi TBD. Cập nhật thành "đã trả lời bằng ADR-0156" ở lần sinh lại contract kế tiếp (`docs/api/generator/m_catalog.py` → `generate.py`; máy soạn ADR này không có Python để chạy generator nên không sửa tay file sinh ra).
- **Test mới:** `cloudinary.test.ts` (12 ca), `AdminCatalogPage.upload.test.tsx` (13 ca). Hai đột biến (làm cùng lúc trên file thật rồi khôi phục, không phải bản sao), mỗi đột biến làm đúng một ca đỏ: bỏ chốt "`imageUrl` chưa đổi thì không gửi"; bỏ chốt chặn Lưu khi còn tải dở. **Chưa chạy thử với Cloudinary thật**: mọi test chỉ thay lớp mạng bằng stub. 25 ca `AdminCatalogPage.test.tsx` và 14 ca `AdminCatalogPage.gallery.test.tsx` có sẵn vẫn xanh.

## Không làm

Signed upload; xóa ảnh trên Cloudinary; nén hoặc biến đổi ảnh ở FE; biến đổi URL khi hiển thị (`f_auto`, `q_auto`, đổi cỡ); kéo thả; ảnh đại diện tài khoản và nhân viên, ảnh nhật ký chăm sóc, ảnh bìa bài viết (đang cùng kiểu dán URL, dùng lại `ImageUploadButton` khi cần).
