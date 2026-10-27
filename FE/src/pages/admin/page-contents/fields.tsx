// Ô nhập và khối danh sách dùng chung cho 4 form nội dung trang. Mọi ô đọc / ghi nháp qua EditorContext theo đường dẫn chấm
// (cùng dạng khóa lỗi của validation.ts), nên form chỉ cần khai báo "đường dẫn + nhãn + giới hạn".
import { useEffect, useId, useRef, type ChangeEvent, type ComponentType, type ReactNode } from 'react';
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { cn } from '../../../lib/utils';
import { countOf, getAt, useEditor, type Draft } from './draft';

/** Khối có tiêu đề, đọc được như một nhóm (`role="group"`, không dùng fieldset vì dễ làm tràn ngang). `inner`: ô con trong một khối. */
export function Group({ title, hint, inner, children }: { title: string; hint?: string; inner?: boolean; children: ReactNode }) {
  const id = useId();
  const Heading = inner ? 'h3' : 'h2';
  return (
    <div
      role="group"
      aria-labelledby={id}
      className={cn('min-w-0', inner ? 'rounded-xl border border-(--border-color) bg-(--bg-secondary) p-4' : 'card p-4 sm:p-5')}
    >
      <Heading id={id} className={inner ? 'text-sm font-semibold text-(--text-primary)' : 'text-base font-semibold text-(--text-primary)'}>
        {title}
      </Heading>
      {hint && <p className="mt-0.5 text-sm text-(--text-secondary)">{hint}</p>}
      <div className="mt-3 space-y-4">{children}</div>
    </div>
  );
}

/**
 * Ô nhập một chuỗi. `rows` có thì là ô nhiều dòng. KHÔNG đặt `maxLength`: vượt giới hạn vẫn phải gõ / dán được để thấy lỗi theo trường.
 * Bộ đếm theo `trim().length` (cách schema đếm) và là một phần mô tả của ô, cùng lỗi và gợi ý.
 */
export function TextField({ path, label, max, rows, hint }: { path: string; label: string; max: number; rows?: number; hint?: string }) {
  const { draft, errors, busy, change } = useEditor();
  const id = useId();
  const value = String(getAt(draft, path) ?? '');
  const error = errors[path];
  const count = countOf(value);
  const props = {
    id,
    value,
    readOnly: busy,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': [error && `${id}-error`, `${id}-count`, hint && `${id}-hint`].filter(Boolean).join(' '),
    className: cn('input', error && 'border-red-400 focus:border-red-500'),
    onChange: (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const next = e.target.value;
      change(path, () => next);
    },
  };
  return (
    <div className="min-w-0">
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-(--text-primary)">
        {label}
      </label>
      {rows ? <textarea {...props} rows={rows} className={cn(props.className, 'resize-y leading-relaxed')} /> : <input type="text" {...props} />}
      <div className="mt-1 flex items-start justify-between gap-3 text-xs">
        {error && (
          <p id={`${id}-error`} className="font-medium text-red-600">
            {error}
          </p>
        )}
        <span id={`${id}-count`} className={cn('ml-auto shrink-0 tabular-nums', count > max ? 'font-medium text-red-600' : 'text-(--text-tertiary)')}>
          {count}/{max}
        </span>
      </div>
      {hint && (
        <p id={`${id}-hint`} className="mt-0.5 text-xs text-(--text-tertiary)">
          {hint}
        </p>
      )}
    </div>
  );
}

type Focus = { index: number; target: 'field' | 'item' | 'up' | 'down' };

function IconAction({
  act,
  label,
  icon: Icon,
  disabled,
  onClick,
}: {
  act: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <Button type="button" variant="ghost" size="sm" className="h-9 w-9 px-0" data-act={act} aria-label={label} title={label} disabled={disabled} onClick={onClick}>
      <Icon className="h-4 w-4" aria-hidden />
    </Button>
  );
}

/**
 * Danh sách mục thêm / xóa / chuyển lên-xuống (POLICY.sections, FAQ.items). `title` là danh từ viết hoa ("Mục", "Câu hỏi"), `children(i)`
 * vẽ các ô của mục thứ i. Con trỏ đi theo thao tác (mục mới → ô đầu, chuyển → nút vừa bấm, xóa → mục kế): chỉ đặt sau thao tác của
 * danh sách, nên gõ chữ không bao giờ kéo con trỏ đi.
 */
export function ListEditor({
  path,
  title,
  min,
  max,
  blank,
  children,
}: {
  path: string;
  title: string;
  min: number;
  max: number;
  blank: () => Draft;
  children: (index: number) => ReactNode;
}) {
  const { draft, busy, change } = useEditor();
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const focus = useRef<Focus | null>(null);
  const items = getAt(draft, path) as unknown[];
  const noun = title.toLowerCase();

  useEffect(() => {
    const want = focus.current;
    if (!want) return;
    focus.current = null;
    const item = root.current?.querySelector<HTMLElement>(`[data-item="${want.index}"]`);
    const pick = (selector: string) => item?.querySelector<HTMLElement>(selector);
    const el =
      want.target === 'item'
        ? item
        : want.target === 'field'
          ? pick('input, textarea')
          : (pick(`[data-act="${want.target}"]:not(:disabled)`) ?? pick('[data-act]:not(:disabled)')); // đã ở đầu / cuối thì sang nút còn dùng được
    el?.focus();
  });

  // Luôn sao chép danh sách (bản đã lưu có thể chính là PAGE_DEFAULTS, không được sửa tại chỗ).
  const edit = (next: Focus, update: (list: unknown[]) => unknown[]) => {
    focus.current = next;
    change(path, (list) => update(list as unknown[]));
  };
  const move = (i: number, by: -1 | 1) =>
    edit({ index: i + by, target: by < 0 ? 'up' : 'down' }, (list) => list.map((item, j) => (j === i ? list[i + by] : j === i + by ? list[i] : item)));

  return (
    <div ref={root} className="space-y-3">
      {items.map((_, i) => (
        <div
          key={i}
          role="group"
          aria-labelledby={`${id}-${i}`}
          data-item={i}
          tabIndex={-1}
          className="min-w-0 rounded-xl border border-(--border-color) bg-(--bg-secondary) p-4 outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)"
        >
          <div className="flex items-center gap-1">
            <h3 id={`${id}-${i}`} className="mr-auto text-sm font-semibold text-(--text-primary)">
              {title} {i + 1}
            </h3>
            <IconAction act="up" label={`Chuyển ${noun} ${i + 1} lên`} icon={ArrowUp} disabled={busy || i === 0} onClick={() => move(i, -1)} />
            <IconAction act="down" label={`Chuyển ${noun} ${i + 1} xuống`} icon={ArrowDown} disabled={busy || i === items.length - 1} onClick={() => move(i, 1)} />
            <IconAction
              act="remove"
              label={`Xóa ${noun} ${i + 1}`}
              icon={Trash2}
              disabled={busy || items.length <= min}
              onClick={() => edit({ index: Math.min(i, items.length - 2), target: 'item' }, (list) => list.filter((_, j) => j !== i))}
            />
          </div>
          <div className="mt-3 space-y-4">{children(i)}</div>
        </div>
      ))}
      <div className="flex flex-wrap items-center gap-3">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={busy || items.length >= max}
          onClick={() => edit({ index: items.length, target: 'field' }, (list) => [...list, blank()])}
        >
          <Plus className="h-4 w-4" aria-hidden />
          Thêm {noun}
        </Button>
        <span className="text-xs tabular-nums text-(--text-tertiary)">
          {items.length}/{max} {noun}
        </span>
      </div>
    </div>
  );
}
