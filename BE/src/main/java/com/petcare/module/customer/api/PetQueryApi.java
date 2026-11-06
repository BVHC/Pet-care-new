package com.petcare.module.customer.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Owner: customer (KH) · BE-2. */
public interface PetQueryApi {

    /** {@code currentWeightKg} = bản ghi cân nặng mới nhất, null nếu chưa cân (BR-KH-04). */
    record PetSummary(Long petId, Long customerId, String name, Species species, LocalDate birthDate,
                      LocalDate deceasedOn, BigDecimal currentWeightKg) {

        public boolean deceased() {
            return deceasedOn != null;
        }
    }

    /** Đọc không khóa. */
    Optional<PetSummary> findPet(Long petId);

    /**
     * Khóa chia sẻ dòng {@code pets} ({@code SELECT … FOR SHARE}) rồi đọc, thêm 2026-10-16 (T17, docs/adr/0041).
     * {@code MANDATORY}: chạy trong transaction của bên gọi. <b>Nghĩa vụ:</b> tiếp nhận (Visit#1), đặt / đổi lịch
     * (Lịch hẹn#1, #2), đặt chỗ lưu trú (Đặt chỗ#1) và nhận thú (Đặt chỗ#3) phải gọi method này <b>trước khi chèn dòng
     * trỏ tới thú</b> rồi kiểm lại {@code deceased} và chủ dưới khóa. Khóa FK {@code FOR KEY SHARE} của câu INSERT không
     * xung đột với {@code FOR NO KEY UPDATE} mà customer giữ khi đánh dấu đã mất / chuyển chủ, nên nếu không khóa ở đây,
     * một Visit mở có thể chen vào sau khi customer đã kiểm {@code hasOpenVisit}. {@code FOR SHARE} xung đột với
     * {@code FOR NO KEY UPDATE}: bên đến sau chờ bên đến trước commit và thấy dữ liệu đã commit (READ COMMITTED).
     */
    Optional<PetSummary> findPetForShare(Long petId);

    /**
     * Nhiều thú một lần (appointment #3 ghép tên cho một trang lịch hẹn, docs/adr/0044). Thêm 15/10 (06 §6).
     * Nghĩa vụ: id không có thì bỏ qua, không trả null, không phân trang, mỗi id xuất hiện tối đa một lần, thứ tự tùy ý;
     * đọc mới mỗi lần gọi (không cache); bên gọi truyền tối đa 100 id; tập rỗng → danh sách rỗng.
     */
    List<PetSummary> findPets(Collection<Long> petIds);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 15): placeholder dạng default — ném UnsupportedOperationException
    // tới khi PetQueryService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /** Hồ sơ thú cho gợi ý và chatbot (07 I-1). Tên thú không có ở đây: tra bằng {@link #findPets}. */
    record PetProfile(Long petId, Long customerId, Species species, String breed, LocalDate birthDate,
                      BigDecimal currentWeightKg, LocalDate deceasedOn) {

        public boolean deceased() {
            return deceasedOn != null;
        }
    }

    /** Thú còn sống của khách, để chatbot hỏi "thú nào" (07 I-1). */
    record LivingPet(Long petId, String name) {}

    /**
     * I-1 (07 §11, UC91, UC92): hồ sơ của thú, kể cả thú đã mất ({@code deceasedOn} khác null; bên gọi tự xét, BR-AI-11).
     * Đọc không khóa. Bản thật trước 30/11 (T77).
     */
    default Optional<PetProfile> findPetProfile(Long petId) {
        throw placeholder("findPetProfile", "T77");
    }

    /** I-1 (07 §11): thú chưa mất ({@code deceased_on IS NULL}) của khách, theo tên rồi id. Bản thật trước 30/11 (T77). */
    default List<LivingPet> findLivingPetsOfCustomer(Long customerId) {
        throw placeholder("findLivingPetsOfCustomer", "T77");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("PetQueryApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
