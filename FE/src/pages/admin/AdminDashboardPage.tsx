import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { cn } from '../../lib/utils';
import {
  Calendar, DollarSign, Clock, AlertTriangle,
  ShoppingBag, Users
} from 'lucide-react';

export function AdminDashboardPage() {
  const { user } = useAdminSession();

  const stats = [
    { label: 'Lịch hẹn hôm nay', value: '24', icon: Calendar, color: 'blue', change: '+5%' },
    { label: 'Doanh thu hôm nay', value: '12.5M', icon: DollarSign, color: 'green', change: '+12%' },
    { label: 'Khách đang chờ', value: '8', icon: Clock, color: 'amber', change: '' },
    { label: 'Yêu cầu hoàn tiền', value: '2', icon: AlertTriangle, color: 'red', change: '' },
  ];

  const services = [
    { label: 'Khám bệnh', value: 8, color: 'bg-blue-500' },
    { label: 'Tiêm chủng', value: 6, color: 'bg-green-500' },
    { label: 'Grooming', value: 5, color: 'bg-purple-500' },
    { label: 'Tái khám', value: 5, color: 'bg-orange-500' },
  ];

  const activities = [
    { time: '15:30', action: 'Hoàn tất khám', detail: 'Mèo Whiskas - Dr. Minh', type: 'success' },
    { time: '15:15', action: 'Check-in lịch hẹn', detail: 'Chó Alaska - Trần Thị B', type: 'info' },
    { time: '14:45', action: 'Tạo hóa đơn', detail: 'INV-2026-012 - 850,000đ', type: 'success' },
    { time: '14:30', action: 'Bắt đầu grooming', detail: 'Chó Poodle - Nguyễn G1', type: 'warning' },
    { time: '14:00', action: 'Yêu cầu hoàn tiền', detail: 'REF-2026-003 - 200,000đ', type: 'error' },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Bảng điều khiển</h1>
        <p className="text-(--text-secondary)">Xin chào {user?.name}, chào mừng đến với Pet Care Admin</p>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {stats.map((stat) => (
          <Card key={stat.label} className="card-kpi">
            <CardContent className="p-5">
              <div className="flex items-center gap-4">
                <div className={cn(
                  'w-12 h-12 rounded-xl flex items-center justify-center',
                  stat.color === 'blue' && 'bg-blue-100',
                  stat.color === 'green' && 'bg-green-100',
                  stat.color === 'amber' && 'bg-amber-100',
                  stat.color === 'red' && 'bg-red-100'
                )}>
                  <stat.icon className={cn(
                    'h-6 w-6',
                    stat.color === 'blue' && 'text-blue-600',
                    stat.color === 'green' && 'text-green-600',
                    stat.color === 'amber' && 'text-amber-600',
                    stat.color === 'red' && 'text-red-600'
                  )} />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-2xl font-semibold text-(--text-primary)">{stat.value}</p>
                  <p className="text-sm text-(--text-secondary)">{stat.label}</p>
                </div>
                {stat.change && (
                  <span className="text-xs font-medium text-green-600 bg-green-50 px-2 py-1 rounded-full">
                    {stat.change}
                  </span>
                )}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Service Distribution */}
        <Card className="card">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-semibold">Dịch vụ hôm nay</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {services.map((service) => (
                <div key={service.label}>
                  <div className="flex justify-between text-sm mb-1.5">
                    <span className="text-(--text-primary)">{service.label}</span>
                    <span className="font-medium text-(--text-secondary)">{service.value} lịch</span>
                  </div>
                  <div className="h-2 bg-(--bg-tertiary) rounded-full overflow-hidden">
                    <div
                      className={cn('h-full rounded-full', service.color)}
                      style={{ width: `${(service.value / 24) * 100}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Recent Activity */}
        <Card className="card">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-semibold">Hoạt động gần đây</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {activities.map((item, idx) => (
                <div key={idx} className="flex items-center gap-3 py-2">
                  <div className={cn(
                    'w-2 h-2 rounded-full shrink-0',
                    item.type === 'success' && 'bg-green-500',
                    item.type === 'info' && 'bg-blue-500',
                    item.type === 'warning' && 'bg-amber-500',
                    item.type === 'error' && 'bg-red-500'
                  )} />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-(--text-primary)">{item.action}</p>
                    <p className="text-xs text-(--text-tertiary) truncate">{item.detail}</p>
                  </div>
                  <span className="text-xs text-(--text-tertiary) shrink-0">{item.time}</span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Quick Actions */}
      <Card className="card">
        <CardHeader className="pb-3">
          <CardTitle className="text-base font-semibold">Thao tác nhanh</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <QuickAction icon={ShoppingBag} label="Quầy POS" href="/admin/pos" color="bg-green-500" />
            <QuickAction icon={Calendar} label="Thêm lịch hẹn" href="/admin/appointments" color="bg-blue-500" />
            <QuickAction icon={DollarSign} label="Tạo hóa đơn" href="/admin/invoices" color="bg-purple-500" />
            <QuickAction icon={Users} label="Quản lý khách hàng" href="/admin/membership" color="bg-amber-500" />
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

function QuickAction({ icon: Icon, label, href, color }: { icon: React.ComponentType<{ className?: string }>; label: string; href: string; color: string }) {
  return (
    <a
      href={href}
      className="flex flex-col items-center justify-center p-4 bg-(--bg-secondary) rounded-xl hover:bg-(--bg-tertiary) transition-colors"
    >
      <div className={cn('p-3 rounded-lg mb-2', color)}>
        <Icon className="h-5 w-5 text-white" />
      </div>
      <span className="text-sm font-medium text-(--text-primary)">{label}</span>
    </a>
  );
}
