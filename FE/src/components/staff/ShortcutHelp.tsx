import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../ui/dialog';
import { SHORTCUTS, type ShortcutDoc } from '../../shared/constants/shortcuts';
import { WORKSPACE_LABELS, type Workspace } from '../../shared/constants/workspaces';
import { Kbd } from './ui';

const GROUPS: { where: ShortcutDoc['where']; title: string }[] = [
  { where: 'all', title: 'Mọi bàn làm việc' },
  { where: 'reception', title: WORKSPACE_LABELS.reception },
  { where: 'doctor', title: WORKSPACE_LABELS.doctor },
  { where: 'grooming', title: WORKSPACE_LABELS.grooming },
];

export function ShortcutHelp({
  open,
  onOpenChange,
  workspace,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workspace: Workspace | null;
}) {
  // The workspace you are in comes right after the global keys.
  const groups = [...GROUPS].sort((a, b) => Number(b.where === workspace) - Number(a.where === workspace));
  const ordered = [groups.find((g) => g.where === 'all')!, ...groups.filter((g) => g.where !== 'all')];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="ws-portal max-h-[85dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-lg">
        <DialogTitle className="text-lg font-bold">Phím tắt</DialogTitle>
        <DialogDescription className="text-sm text-(--ws-ink-2)">
          Phím đơn như N hay ? không chạy khi bạn đang gõ trong ô nhập liệu.
        </DialogDescription>
        <div className="flex flex-col gap-5">
          {ordered.map((group) => (
            <section key={group.where}>
              <h3 className="mb-2 text-[13px] font-semibold text-(--ws-ink-2)">{group.title}</h3>
              <dl className="divide-y divide-(--ws-line) rounded-lg border border-(--ws-line)">
                {SHORTCUTS.filter((s) => s.where === group.where).map((s) => (
                  <div key={s.action} className="flex items-center justify-between gap-4 px-3 py-2">
                    <dt className="text-sm">{s.action}</dt>
                    <dd className="flex shrink-0 gap-1">
                      {s.keys.map((k) => (
                        <Kbd key={k}>{k}</Kbd>
                      ))}
                    </dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}
