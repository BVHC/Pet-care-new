import { Checkbox } from '../../../components/admin/form-ui';
import { formatDate } from '../../../lib/utils';
import type { ScheduleImpact } from '../../../shared/types/branch';

/**
 * Lịch hẹn / đặt chỗ lưu trú BOOKED bị ảnh hưởng khi đổi giờ hoặc thêm ngày nghỉ (BR-CN-04, BR-LH-10).
 * Quản lý xem trước rồi mới chọn hủy hàng loạt; khách không có email thì lễ tân gọi điện (06 Q4).
 */
export function ImpactList({
  impact,
  cancel,
  onCancelChange,
}: {
  impact: ScheduleImpact;
  cancel: boolean;
  onCancelChange: (cancel: boolean) => void;
}) {
  const { appointments, boardingBookings } = impact;
  return (
    <div className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm">
      <p className="font-medium text-amber-900">
        {`${appointments.length} lịch hẹn và ${boardingBookings.length} đặt chỗ lưu trú sẽ không còn hợp lệ.`}
      </p>
      {appointments.length > 0 && (
        <table className="w-full text-left">
          <caption className="mb-1 text-left text-xs font-semibold text-amber-900">Lịch hẹn</caption>
          <thead className="text-xs text-amber-800">
            <tr>
              <th>Mã</th>
              <th>Ngày</th>
              <th>Giờ</th>
              <th>Thú cưng</th>
              <th>Khách hàng</th>
              <th>SĐT</th>
            </tr>
          </thead>
          <tbody>
            {appointments.map((a) => (
              <tr key={a.appointmentId}>
                <td className="font-mono text-xs">{a.code}</td>
                <td>{formatDate(a.slotDate)}</td>
                <td>{a.slotStart}</td>
                <td>{a.petName}</td>
                <td>{a.customerName}</td>
                <td>{a.customerPhone ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {boardingBookings.length > 0 && (
        <table className="w-full text-left">
          <caption className="mb-1 text-left text-xs font-semibold text-amber-900">Đặt chỗ lưu trú</caption>
          <thead className="text-xs text-amber-800">
            <tr>
              <th>Mã</th>
              <th>Nhận</th>
              <th>Trả</th>
              <th>Thú cưng</th>
              <th>Khách hàng</th>
              <th>SĐT</th>
            </tr>
          </thead>
          <tbody>
            {boardingBookings.map((b) => (
              <tr key={b.bookingId}>
                <td className="font-mono text-xs">{b.code}</td>
                <td>{formatDate(b.checkInDate)}</td>
                <td>{formatDate(b.checkOutDate)}</td>
                <td>{b.petName}</td>
                <td>{b.customerName}</td>
                <td>{b.customerPhone ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Checkbox
        label="Hủy hàng loạt các lịch trên và thông báo cho khách (BR-LH-10)"
        checked={cancel}
        onChange={onCancelChange}
      />
      <p className="text-xs text-amber-800">Lịch bị hủy theo cách này không tính hủy muộn và không tính vào số lần đổi lịch của khách.</p>
    </div>
  );
}
