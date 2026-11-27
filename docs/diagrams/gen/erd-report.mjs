// ERD rút gọn cho báo cáo: 42 trong 88 bảng, chia 5 hình (A–E) + một hình tổng quan (ALL).
// Chọn bảng từ 30 use case của Bảng 3.2 của BAO CAO (không phải toàn bộ 85 use case): xem BAO CAO.md mục 3.1.2.
// Quy tắc vẽ (template.html, tab "ERD báo cáo"): không vẽ khóa ngoại tới bảng ngoài 42 bảng này; khóa ngoại tới accounts
// chỉ vẽ ở hình có accounts (B); khóa ngoại sang bảng của hình khác vẽ tới khối nét đứt "ngoài hình".
export const ERD_FIGS = [
  { id: 'A', cols: 3, title: 'A. Luồng chính: lịch hẹn, tiếp nhận, khám, Order, thu tiền', tables: 'branches customers pets services products vaccine_types vaccination_protocols appointments visits medical_records medical_record_addenda prescription_items vaccinations orders order_lines payments stock_lots' },
  { id: 'B', cols: 3, title: 'B. Tài khoản, phiên đăng nhập, nhân sự, chăm sóc khách', tables: 'accounts sessions refresh_tokens staff_profiles system_configs shift_definitions shift_assignments shift_requests care_tasks' },
  { id: 'C', cols: 3, title: 'C. Lưu trú', tables: 'kennel_types kennels boarding_bookings boarding_check_ins care_logs' },
  { id: 'D', cols: 3, title: 'D. Mua online, thanh toán, ca thu ngân, kho', tables: 'online_orders stock_reservations payment_transactions cashier_shifts inventory_items stock_receipts stock_movements stock_transfers' },
  { id: 'E', cols: 3, title: 'E. Trí tuệ nhân tạo (chatbot)', tables: 'ai_documents ai_chunks ai_chat_logs' },
].map(f => ({ ...f, tables: f.tables.split(' ') }));

export const ERD_KEEP = ERD_FIGS.flatMap(f => f.tables);
export const ERD_ALL = { id: 'ALL', cols: 7, title: 'Tổng quan 42 bảng (một hình)', tables: ERD_KEEP };

export function checkErd(schemaTableNames, total = 42) {
  const names = new Set(schemaTableNames), dup = ERD_KEEP.filter((t, i) => ERD_KEEP.indexOf(t) !== i), miss = ERD_KEEP.filter(t => !names.has(t));
  if (dup.length || miss.length || ERD_KEEP.length !== total) throw new Error(`ERD báo cáo: trùng ${dup} | không có trong schema ${miss} | tổng ${ERD_KEEP.length} (cần ${total})`);
}
