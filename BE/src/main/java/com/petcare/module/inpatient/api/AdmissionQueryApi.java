package com.petcare.module.inpatient.api;

/**
 * Câu hỏi "còn ca nội trú {@code ADMITTED} không" cho module khác (docs/06 §6b hàng 10; docs/09 §C; docs/adr/0149 mục 13):
 * <ul>
 * <li>theo thú — boarding chặn kết thúc lưu trú (trả thú {@code RETURNED} và thú mất {@code PET_DECEASED}), 409
 * {@code BR-LT-17};</li>
 * <li>theo lượt — visit chặn hoàn tất lượt (Visit#5), 409 (BR-LT-14).</li>
 * </ul>
 * Đọc <b>không khóa</b>, READ COMMITTED, trong transaction của bên gọi ({@code MANDATORY}). Bên gọi giữ khóa dòng của đối
 * tượng nó sắp kết thúc (đặt chỗ / lượt) trước khi hỏi. Chống race với một lần nhập viện chạy đồng thời là việc của bên nhập
 * viện (N3, docs/adr/0149 mục 15).
 */
public interface AdmissionQueryApi {

    /** Thú có ca nội trú {@code ADMITTED} (tối đa một, {@code ux_admissions_pet_admitted}). */
    boolean hasActiveAdmission(Long petId);

    /** Lượt có ca nội trú {@code ADMITTED} gắn vào ({@code admissions.visit_id}). */
    boolean hasActiveAdmissionForVisit(Long visitId);
}
