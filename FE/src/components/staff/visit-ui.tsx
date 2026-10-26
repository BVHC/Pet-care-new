// Phần hiển thị dùng chung cho lượt tiếp nhận của BE (T36): mã lượt, nhãn ưu tiên và nguồn, chấm trực tuyến.
// Ticket (ui.tsx) nhận số thứ tự; BE không có số này, nhân viên đọc mã `TN-yyMM-nnnnn` khi gọi.
import { AlertTriangle } from 'lucide-react';
import { cn } from '../../lib/utils';
import { visitSourceOf, type Visit } from '../../shared/types/visit';

export function VisitCode({ code, urgent }: { code: string; urgent?: boolean }) {
  return (
    <span className="ws-chip ws-num" data-tone={urgent ? 'urgent' : undefined}>
      {code}
    </span>
  );
}

/**
 * Hạng cấp cứu và nguồn của lượt (BR-TN-03, 04). Có lịch hẹn mà vẫn xếp `WALK_IN` là khách trễ quá 15 phút, nên nguồn lấy từ `appointmentId`, không từ hạng.
 */
export function VisitBadges({ visit }: { visit: Pick<Visit, 'priorityClass' | 'appointmentId'> }) {
  const fromAppointment = visitSourceOf(visit) === 'APPOINTMENT';
  const late = fromAppointment && visit.priorityClass === 'WALK_IN';
  return (
    <>
      {visit.priorityClass === 'EMERGENCY' && (
        <span className="ws-chip" data-tone="urgent">
          <AlertTriangle className="h-3 w-3" aria-hidden />
          Cấp cứu
        </span>
      )}
      {fromAppointment ? (
        <span className="ws-chip" data-tone="accent" title={late ? 'Trễ quá 15 phút nên xếp như khách vãng lai (BR-TN-03)' : undefined}>
          {late ? 'Lịch hẹn trễ' : 'Lịch hẹn'}
        </span>
      ) : (
        <span className="ws-chip">Vãng lai</span>
      )}
    </>
  );
}

/** Trạng thái hiện diện chỉ là gợi ý (BR-TN-06). */
export function OnlineDot({ online }: { online: boolean }) {
  return (
    <span
      role="img"
      aria-label={online ? 'Trực tuyến' : 'Ngoại tuyến'}
      className={cn('inline-block h-2 w-2 shrink-0 rounded-full', online ? 'bg-(--ws-ready-ink)' : 'bg-(--ws-ink-3)')}
    />
  );
}
