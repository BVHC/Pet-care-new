import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import { track } from '../../shared/utils/telemetry';
import { StatePanel, WsButton } from './ui';

interface Props {
  /** Changing it (e.g. switching workspace) clears a caught error. */
  resetKey: string;
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/** One crashing workspace must not take the other workspaces (or the session) down with it. */
export class WorkspaceErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    track('workspace_error', { message: error.message, stack: info.componentStack?.slice(0, 500) });
  }

  componentDidUpdate(prev: Props) {
    if (prev.resetKey !== this.props.resetKey && this.state.error) this.setState({ error: null });
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="flex h-full items-center justify-center p-6">
        <StatePanel
          tone="error"
          icon={AlertTriangle}
          title="Khu vực này vừa gặp lỗi"
          body="Dữ liệu đã lưu vẫn còn nguyên. Tải lại khu vực để làm tiếp, hoặc chuyển sang bàn làm việc khác."
          action={
            <WsButton variant="primary" onClick={() => this.setState({ error: null })}>
              Tải lại khu vực
            </WsButton>
          }
        />
      </div>
    );
  }
}
