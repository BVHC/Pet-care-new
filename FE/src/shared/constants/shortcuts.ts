import type { Workspace } from './workspaces';

export interface ShortcutDoc {
  keys: string[];
  action: string;
  where: Workspace | 'all';
}

/** Single source for the in-app shortcut sheet and PLAN-AUDIT-DETAIL.md. */
export const SHORTCUTS: ShortcutDoc[] = [
  { keys: ['Alt', '1–4'], action: 'Chuyển bàn làm việc theo thứ tự trên thanh bên', where: 'all' },
  { keys: ['?'], action: 'Mở bảng phím tắt', where: 'all' },
  { keys: ['Esc'], action: 'Đóng hộp thoại, xóa ô tìm kiếm', where: 'all' },
  { keys: ['F1'], action: 'Tìm sản phẩm (hoặc Ctrl K)', where: 'reception' },
  { keys: ['Enter'], action: 'Trong ô tìm: thêm sản phẩm đầu tiên vào phiếu thu', where: 'reception' },
  { keys: ['↑', '↓', '←', '→'], action: 'Đi qua các ô sản phẩm', where: 'reception' },
  { keys: ['N'], action: 'Tiếp nhận khách vãng lai', where: 'reception' },
  { keys: ['Ctrl', 'Enter'], action: 'Thu tiền phiếu đang mở', where: 'reception' },
  { keys: ['↑', '↓'], action: 'Đi qua hàng chờ khám', where: 'doctor' },
  { keys: ['Ctrl', 'S'], action: 'Lưu bệnh án ngay', where: 'doctor' },
  { keys: ['Ctrl', 'Enter'], action: 'Hoàn tất lượt, chuyển thu ngân', where: 'doctor' },
  { keys: ['Tab', 'Enter'], action: 'Chọn thẻ rồi bấm Bắt đầu hoặc Xong', where: 'grooming' },
];
