/**
 * Tra cứu khách & thú cưng (UC23) ở quầy lễ tân: theo SĐT, họ tên, email hoặc tên thú.
 * Một SĐT có thể ra nhiều hồ sơ: mỗi hồ sơ hiện họ tên, email đã che và thú cưng để chọn đúng người (BR-KH-10).
 */
import { useState } from 'react';
import { Search, UserPlus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useDebounce } from '@/shared/hooks/useDebounce';
import { useCustomerLookup } from '@/domains/pets/hooks/useCustomerPet';
import { CHANNEL_LABEL, SPECIES_EMOJI } from '@/domains/pets/labels';
import type { CustomerSearchItem, CustomerSearchQuery } from '@/domains/pets/types';

type Mode = 'phone' | 'name' | 'email' | 'petName';

const MODES: { mode: Mode; label: string; placeholder: string }[] = [
  { mode: 'phone', label: 'SĐT', placeholder: '0905111222' },
  { mode: 'name', label: 'Họ tên', placeholder: 'Lê Hoàng Long' },
  { mode: 'email', label: 'Email', placeholder: 'khach@petcare.vn' },
  { mode: 'petName', label: 'Tên thú', placeholder: 'Mochi' },
];

/** SĐT tra chính xác nên chỉ gửi khi đủ 10 số. */
function queryOf(mode: Mode, term: string): CustomerSearchQuery {
  if (mode === 'phone') return { phone: /^0\d{9}$/.test(term) ? term : '' };
  return { [mode]: term };
}

export function CustomerSearchPanel({
  onOpenCustomer,
  onCreateCustomer,
  showTitle = true,
}: {
  onOpenCustomer: (customerId: number) => void;
  /** Chỉ lễ tân tạo hồ sơ (UC22); bỏ trống thì ẩn nút tạo (bác sĩ, nhân viên chăm sóc chỉ tra cứu). */
  onCreateCustomer?: () => void;
  /** Trang đã có h1 cùng tên thì bỏ tiêu đề của panel. */
  showTitle?: boolean;
}) {
  const [mode, setMode] = useState<Mode>('phone');
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term.trim(), 250);
  const lookup = useCustomerLookup(queryOf(mode, debounced));
  const active = MODES.find((m) => m.mode === mode)!;
  const waitingForPhone = mode === 'phone' && term.trim() !== '' && !/^0\d{9}$/.test(term.trim());
  const items = term.trim() ? (lookup.data?.content ?? []) : [];

  return (
    <section aria-label="Tra cứu khách hàng" className="flex h-full flex-col">
      <div className="space-y-3 border-b border-(--ws-line) p-4">
        {(showTitle || onCreateCustomer) && (
          <div className="flex items-center justify-between gap-2">
            {showTitle && <h2 className="font-semibold">Tra cứu khách & thú cưng</h2>}
            {onCreateCustomer && (
              <Button size="sm" onClick={onCreateCustomer}>
                <UserPlus className="h-4 w-4" aria-hidden /> Tạo hồ sơ mới
              </Button>
            )}
          </div>
        )}
        <div role="group" aria-label="Tra cứu theo" className="flex flex-wrap gap-1">
          {MODES.map((m) => (
            <button
              key={m.mode}
              type="button"
              aria-pressed={mode === m.mode}
              onClick={() => setMode(m.mode)}
              className={cn(
                'rounded-full px-3 py-1 text-xs font-medium',
                mode === m.mode ? 'bg-(--ws-accent) text-white' : 'bg-(--ws-raised) text-(--ws-ink-2) hover:text-(--ws-ink)',
              )}
            >
              {m.label}
            </button>
          ))}
        </div>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
          <input
            aria-label={`Tra cứu theo ${active.label}`}
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            placeholder={active.placeholder}
            inputMode={mode === 'phone' ? 'tel' : undefined}
            autoComplete="off"
            className="h-10 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) pl-9 pr-3 text-sm text-(--ws-ink) placeholder:text-(--ws-ink-3)"
          />
        </div>
        {waitingForPhone && <p className="text-xs text-(--ws-ink-3)">Nhập đủ 10 số điện thoại để tra cứu.</p>}
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {lookup.isError && <p role="alert" className="text-sm text-red-600">{plainMessage(toApiError(lookup.error))}</p>}
        {!term.trim() && <p className="py-10 text-center text-sm text-(--ws-ink-2)">Nhập SĐT, họ tên, email hoặc tên thú cưng để tra cứu.</p>}
        {term.trim() && lookup.isSuccess && !lookup.isFetching && items.length === 0 && !waitingForPhone && (
          <div className="py-10 text-center text-sm text-(--ws-ink-2)">
            <p>Không tìm thấy hồ sơ phù hợp.</p>
            {onCreateCustomer && (
              <Button className="mt-3" size="sm" variant="outline" onClick={onCreateCustomer}>
                <UserPlus className="h-4 w-4" aria-hidden /> Tạo hồ sơ tại quầy
              </Button>
            )}
          </div>
        )}
        {items.length > 0 && (
          <>
            <p className="mb-2 text-xs text-(--ws-ink-3)">{lookup.data?.totalElements} hồ sơ</p>
            <ul className="space-y-2">
              {items.map((item) => (
                <li key={item.customerId}>
                  <CustomerRow item={item} onOpen={() => onOpenCustomer(item.customerId)} />
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}

function CustomerRow({ item, onOpen }: { item: CustomerSearchItem; onOpen: () => void }) {
  return (
    <div className="rounded-lg border border-(--ws-line) p-3 hover:border-(--ws-accent)">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-semibold">{item.fullName}</p>
          <p className="text-xs text-(--ws-ink-2)">
            {item.phone ?? 'Chưa có SĐT'} · {item.maskedEmail ?? 'Chưa có email'} · {CHANNEL_LABEL[item.createdChannel]}
            {item.hasAccount && ' · có tài khoản'}
          </p>
        </div>
        {/* Cùng SĐT có thể trùng cả họ tên: tên nút kèm email đã che để phân biệt (BR-KH-10). */}
        <Button size="sm" variant="outline" onClick={onOpen} aria-label={`Mở hồ sơ ${item.fullName}, ${item.maskedEmail ?? 'chưa có email'}`}>
          Mở hồ sơ
        </Button>
      </div>
      {item.pets.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-1" aria-label={`Thú cưng của ${item.fullName}`}>
          {item.pets.map((pet) => (
            <li
              key={pet.petId}
              className={cn('rounded-full px-2 py-0.5 text-xs', pet.deceased ? 'bg-(--ws-raised) text-(--ws-ink-3)' : 'bg-amber-50 text-amber-800')}
            >
              {SPECIES_EMOJI[pet.species]} {pet.name}
              {pet.deceased && ' (đã mất)'}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
