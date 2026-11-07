import { useState } from 'react';
import { Archive, Eye, Pencil, Plus, RefreshCw, Search, Send } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { StatusBadge } from '../../../components/ui/modal-templates';
import { NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { Pager } from '../../../components/admin/Pager';
import { cn } from '../../../lib/utils';
import { aiApi } from '../../../shared/api/ai.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { useAiDocuments, useAiMutation } from '../../../shared/hooks/useAi';
import {
  DOCUMENT_SPECIES,
  DOCUMENT_STATUSES,
  DOCUMENT_TOPICS,
  type AiDocumentSummary,
  type DocumentSpecies,
  type DocumentStatus,
  type DocumentTopic,
} from '../../../shared/types/ai';
import { formatDateTimeVn } from '../articles/format';
import { TableState } from '../articles/TableState';
import { DocumentForm } from './DocumentForm';
import { INDEX_LABELS, INDEX_VARIANT, SOURCE_LABELS, SPECIES_LABELS, STATUS_LABELS, STATUS_VARIANT, TOPIC_LABELS } from './labels';

const PAGE_SIZE = 20;

/** UC93: tri thức cho chatbot. Chỉ MANUAL sửa / xuất bản / lưu trữ được; CATALOG_PROTOCOL do hệ thống dựng nên chỉ xem (BR-AI-13). */
export function DocumentsTab() {
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [status, setStatus] = useState('');
  const [topic, setTopic] = useState('');
  const [species, setSpecies] = useState('');
  const [page, setPage] = useState(0);
  const list = useAiDocuments({
    status: (status || undefined) as DocumentStatus | undefined,
    topic: (topic || undefined) as DocumentTopic | undefined,
    species: (species || undefined) as DocumentSpecies | undefined,
    q: search || undefined,
    page,
    size: PAGE_SIZE,
  });
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const publish = useAiMutation((d: AiDocumentSummary) => aiApi.publishDocument(d.documentId));
  const archive = useAiMutation((d: AiDocumentSummary) => aiApi.archiveDocument(d.documentId));
  const reindex = useAiMutation((d: AiDocumentSummary) => aiApi.reindexDocument(d.documentId));
  const busy = publish.isPending || archive.isPending || reindex.isPending;
  const data = list.data;
  const filter = (set: (v: string) => void) => (v: string) => {
    set(v);
    setPage(0);
  };

  const run = async (action: () => Promise<unknown>, success: string) => {
    setNotice(null);
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <div className="relative min-w-[220px] flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" />
            <Input aria-label="Tìm theo tiêu đề" placeholder="Tìm theo tiêu đề…" className="pl-10" maxLength={100} value={q} onChange={(e) => filter(setQ)(e.target.value)} />
          </div>
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'sm:w-44')} value={status} onChange={(e) => filter(setStatus)(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            {DOCUMENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc chủ đề" className={cn(selectClass, 'sm:w-44')} value={topic} onChange={(e) => filter(setTopic)(e.target.value)}>
            <option value="">Mọi chủ đề</option>
            {DOCUMENT_TOPICS.map((t) => (
              <option key={t} value={t}>
                {TOPIC_LABELS[t]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc loài" className={cn(selectClass, 'sm:w-36')} value={species} onChange={(e) => filter(setSpecies)(e.target.value)}>
            <option value="">Mọi loài</option>
            {DOCUMENT_SPECIES.map((s) => (
              <option key={s} value={s}>
                {SPECIES_LABELS[s]}
              </option>
            ))}
          </select>
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Tạo tài liệu
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table" aria-label="Tài liệu tri thức">
            <thead>
              <tr>
                <th className="px-3!">Tiêu đề</th>
                <th className="px-3!">Chủ đề · Loài</th>
                <th className="px-3!">Trạng thái</th>
                <th className="px-3!">Chỉ mục</th>
                <th className="px-3!">Cập nhật</th>
                <th className="px-3!" style={{ textAlign: 'right' }}>
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((d) => {
                  const manual = d.sourceType === 'MANUAL';
                  const viewOnly = !manual || d.status === 'ARCHIVED';
                  return (
                    <tr key={d.documentId}>
                      <td className="max-w-sm px-3!">
                        <p className="font-medium text-(--text-primary)">{d.title}</p>
                        <p className="text-xs text-(--text-tertiary)">
                          #{d.documentId} · {SOURCE_LABELS[d.sourceType]} · phiên bản {d.version}
                        </p>
                      </td>
                      <td className="px-3!">
                        {TOPIC_LABELS[d.topic]} · {SPECIES_LABELS[d.species]}
                      </td>
                      <td className="whitespace-nowrap px-3!">
                        <StatusBadge status={STATUS_LABELS[d.status]} variant={STATUS_VARIANT[d.status]} />
                      </td>
                      <td className="whitespace-nowrap px-3!">
                        <StatusBadge status={INDEX_LABELS[d.indexStatus]} variant={INDEX_VARIANT[d.indexStatus]} />
                      </td>
                      <td className="whitespace-nowrap px-3!">{formatDateTimeVn(d.updatedAt)}</td>
                      <td className="px-3!">
                        <div className="flex items-center justify-end gap-1">
                          <Button size="sm" variant="ghost" onClick={() => setEditing(d.documentId)} aria-label={`${viewOnly ? 'Xem' : 'Sửa'} ${d.title}`} title={viewOnly ? 'Xem' : 'Sửa'}>
                            {viewOnly ? <Eye className="h-4 w-4" /> : <Pencil className="h-4 w-4" />}
                          </Button>
                          {manual && (d.status === 'DRAFT' || d.status === 'ARCHIVED') && (
                            <Button size="sm" variant="outline" disabled={busy} aria-label={`Xuất bản ${d.title}`} onClick={() => run(() => publish.mutateAsync(d), `Đã xuất bản “${d.title}”. Chỉ mục sẽ được nạp trong giây lát.`)}>
                              <Send className="h-3.5 w-3.5" />
                              Xuất bản
                            </Button>
                          )}
                          {manual && (d.status === 'DRAFT' || d.status === 'PUBLISHED') && (
                            <Button size="sm" variant="outline" disabled={busy} aria-label={`Lưu trữ ${d.title}`} onClick={() => run(() => archive.mutateAsync(d), `Đã lưu trữ “${d.title}”. Chatbot ngừng dùng tài liệu này ngay.`)}>
                              <Archive className="h-3.5 w-3.5" />
                              Lưu trữ
                            </Button>
                          )}
                          {manual && d.status === 'PUBLISHED' && d.indexStatus === 'FAILED' && (
                            <Button size="sm" variant="outline" disabled={busy} aria-label={`Nạp lại chỉ mục ${d.title}`} onClick={() => run(() => reindex.mutateAsync(d), `Đã yêu cầu nạp lại chỉ mục “${d.title}”.`)}>
                              <RefreshCw className="h-3.5 w-3.5" />
                              Nạp lại
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              ) : (
                <TableState colSpan={6} loading={list.isLoading} error={list.isError ? plainMessage(toApiError(list.error)) : undefined} onRetry={() => list.refetch()}>
                  {status || topic || species || search ? 'Không có tài liệu nào khớp bộ lọc.' : 'Chưa có tài liệu nào.'}
                </TableState>
              )}
            </tbody>
          </table>
          {data && <Pager data={data} unit="tài liệu" onPage={setPage} />}
        </CardContent>
      </Card>

      {editing !== null && (
        <DocumentForm
          documentId={editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}
