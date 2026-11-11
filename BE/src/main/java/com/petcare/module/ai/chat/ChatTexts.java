package com.petcare.module.ai.chat;

/**
 * Câu mẫu cố định của chatbot, không do LLM sinh. Nguyên văn đặc tả: {@link #OUT_OF_SCOPE} (BR-AI-07),
 * {@link #MEDICAL_RECORD} và {@link #NEED_LOGIN} (BR-AI-28), {@link #EMERGENCY} và {@link #DISCLAIMER} (07 §10). Đặc tả
 * không nêu nguyên văn {@link #MEDICAL_REFUSAL} (BR-AI-02), {@link #UNAVAILABLE} (BR-AI-10) và {@link #EMERGENCY_NO_BRANCH}
 * (BR-AI-03, danh sách rỗng): lấy theo mock FE của T57 để giao diện và máy chủ cùng một chữ (ADR-0131).
 * <p>
 * Câu của UC91 (ADR-0132): {@link #NEED_PET_CHOICE} nguyên văn 07 §10; {@link #NO_APPOINTMENT} nguyên văn BR-AI-27 thêm dấu
 * chấm; {@link #NO_PET} đặc tả chỉ nói "câu mẫu hướng dẫn thêm thú" nên chữ do ADR-0132 chọn.
 */
public final class ChatTexts {

    public static final String OUT_OF_SCOPE =
            "Mình chưa có thông tin cho câu hỏi này. Bạn có thể đặt lịch để bác sĩ tư vấn trực tiếp.";
    public static final String MEDICAL_REFUSAL = "Mình không thể chẩn đoán bệnh, kê đơn hay nêu liều dùng thuốc. "
            + "Bạn vui lòng đặt lịch khám để bác sĩ thú y tư vấn trực tiếp.";
    public static final String MEDICAL_RECORD =
            "Mình không xem được hồ sơ bệnh án. Bạn xem ở Hồ sơ sức khỏe của thú (UC25) hoặc hỏi bác sĩ.";
    public static final String UNAVAILABLE =
            "Trợ lý đang bận, bạn vui lòng thử lại sau ít phút hoặc đặt lịch để được tư vấn trực tiếp.";
    public static final String EMERGENCY = "Đây có thể là tình huống khẩn cấp. Hãy đưa thú tới ngay chi nhánh nhận "
            + "cấp cứu dưới đây hoặc gọi trước khi đến.";
    public static final String EMERGENCY_NO_BRANCH =
            "Đây có thể là tình huống khẩn cấp. Hãy gọi ngay cho chi nhánh Pet Care gần nhất để được hướng dẫn.";
    public static final String NEED_LOGIN = "Vui lòng đăng nhập để xem lịch hẹn / lịch tiêm của bạn.";
    public static final String DISCLAIMER = "Thông tin tham khảo, không thay thế khám thú y.";
    public static final String NEED_PET_CHOICE = "Bạn muốn xem lịch tiêm của bé nào?";
    public static final String NO_APPOINTMENT = "Bạn chưa có lịch hẹn sắp tới.";
    public static final String NO_PET = "Bạn chưa có thú cưng nào trong hồ sơ. Hãy thêm thú cưng để xem lịch tiêm.";

    private ChatTexts() {
    }
}
