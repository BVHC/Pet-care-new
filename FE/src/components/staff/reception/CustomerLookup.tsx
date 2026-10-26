// Chọn hồ sơ khách tại quầy (UC23 tra cứu, cùng hook với RetailOrderPanel): SĐT đủ 10 số tra chính xác, có @ là email, còn lại là họ tên.
// Chữ mặc định là của màn thu tiền; màn khác (tiếp nhận khách, T36) đổi chữ bằng `copy`.
import { useState } from 'react';
import { Search } from 'lucide-react';
import { useCustomerLookup } from '../../../domains/pets/hooks/useCustomerPet';
import type { CustomerSearchQuery } from '../../../domains/pets/types';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { StatePanel } from '../ui';

const PHONE = /^0\d{9}$/;

function lookupOf(term: string): CustomerSearchQuery {
  if (/^\d+$/.test(term)) return { phone: PHONE.test(term) ? term : '' };
  return term.includes('@') ? { email: term } : { name: term };
}

const THU_TIEN_COPY = {
  label: 'Tìm khách cần thu tiền',
  placeholder: 'Tìm khách cần thu: SĐT, họ tên hoặc email',
  emptyTitle: 'Chọn khách để thu tiền',
  emptyBody: 'Tìm khách theo SĐT hoặc họ tên, hoặc bấm Thu tiền ở tab Chờ thu.',
  listLabel: 'Hồ sơ khách cần thu',
};

export function CustomerLookup({ onPick, copy }: { onPick: (customerId: number) => void; copy?: Partial<typeof THU_TIEN_COPY> }) {
  const text = { ...THU_TIEN_COPY, ...copy };
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term.trim(), 250);
  const lookup = useCustomerLookup(lookupOf(debounced));
  const trimmed = term.trim();
  const waitingForPhone = /^\d+$/.test(trimmed) && !PHONE.test(trimmed);
  const items = trimmed ? (lookup.data?.content ?? []) : [];

  return (
    <div className="flex flex-col gap-3 p-3">
      <label className="relative block">
        <span className="sr-only">{text.label}</span>
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
        <input
          type="search"
          value={term}
          onChange={(e) => setTerm(e.target.value)}
          placeholder={text.placeholder}
          autoComplete="off"
          className="h-11 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-raised) pl-9 pr-3 text-sm text-(--ws-ink) placeholder:text-(--ws-ink-3) focus:border-(--ws-accent) focus:bg-(--ws-surface)"
        />
      </label>
      {waitingForPhone && <p className="text-xs text-(--ws-ink-3)">Nhập đủ 10 số điện thoại để tra cứu.</p>}
      {!trimmed ? (
        <StatePanel title={text.emptyTitle} body={text.emptyBody} />
      ) : lookup.isError ? (
        <p role="alert" className="text-sm text-(--ws-urgent-ink)">
          Không tra cứu được khách. Thử lại sau ít phút.
        </p>
      ) : items.length === 0 && lookup.isSuccess && !lookup.isFetching && !waitingForPhone ? (
        <p className="text-sm text-(--ws-ink-2)">Không tìm thấy hồ sơ phù hợp.</p>
      ) : (
        <ul aria-label={text.listLabel} className="flex flex-col gap-2">
          {items.map((c) => (
            <li key={c.customerId}>
              {/* Cùng SĐT có thể nhiều hồ sơ (BR-KH-10): hiện thêm email đã che để chọn đúng người. */}
              <button
                type="button"
                onClick={() => onPick(c.customerId)}
                className="flex w-full flex-col items-start rounded-lg border border-(--ws-line) px-3 py-2 text-left hover:border-(--ws-accent)"
              >
                <span className="text-sm font-semibold">{c.fullName}</span>
                <span className="ws-num text-xs text-(--ws-ink-2)">
                  {c.phone ?? 'Chưa có SĐT'} · {c.maskedEmail ?? 'chưa có email'}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
