package com.petcare.module.visit.dto;

/**
 * Lựa chọn của VET khi kê đơn thiếu tồn khả dụng (visit-v1 {@code AddPrescriptionRequest.shortageResolution}, A5;
 * BR-KB-03; docs/adr/0077). Chỉ có hiệu lực khi tồn lúc ghi vẫn thiếu: tồn đã đủ thì bị bỏ qua (A26). Giá trị lạ hoặc
 * chữ thường là 400 {@code MALFORMED_REQUEST}.
 */
public enum ShortageResolution {
    /** Giảm số lượng về bằng tồn khả dụng; tồn bằng 0 thì bị từ chối (A27). */
    REDUCE_TO_AVAILABLE,
    /** Giữ nguyên số lượng, dòng là mua ngoài: vẫn in trên đơn, không sinh dòng Order. */
    EXTERNAL_PURCHASE
}
