import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Search, Crown, Users, Star, TrendingUp } from 'lucide-react';
import { cn } from '../../lib/utils';

type MemberTier = 'BRONZE' | 'SILVER' | 'GOLD' | 'PLATINUM';

interface Member {
  id: string; name: string; phone: string; email: string;
  tier: MemberTier; joinDate: string; points: number; totalSpent: number; status: 'ACTIVE' | 'EXPIRED';
}

const MOCK_MEMBERS: Member[] = [
  { id: '1', name: 'Nguyễn Văn A', phone: '0901234567', email: 'nva@email.com', tier: 'GOLD', joinDate: '2025-01-15', points: 1500, totalSpent: 15000000, status: 'ACTIVE' },
  { id: '2', name: 'Trần Thị B', phone: '0912345678', email: 'ttb@email.com', tier: 'PLATINUM', joinDate: '2024-06-01', points: 5000, totalSpent: 50000000, status: 'ACTIVE' },
  { id: '3', name: 'Lê Văn C', phone: '0923456789', email: 'lvc@email.com', tier: 'SILVER', joinDate: '2025-08-20', points: 500, totalSpent: 5000000, status: 'ACTIVE' },
];

function TierBadge({ tier }: { tier: MemberTier }) {
  const config: Record<MemberTier, { label: string; className: string }> = {
    BRONZE: { label: 'Bronze', className: 'bg-orange-100 text-orange-700 border border-orange-300' },
    SILVER: { label: 'Silver', className: 'bg-gray-100 text-gray-700 border border-gray-300' },
    GOLD: { label: 'Gold', className: 'bg-yellow-100 text-yellow-700 border border-yellow-300' },
    PLATINUM: { label: 'Platinum', className: 'bg-purple-100 text-purple-700 border border-purple-300' },
  };
  const { label, className } = config[tier];
  return <Badge className={cn('font-medium', className)}><Crown className="h-3 w-3 mr-1" />{label}</Badge>;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', minimumFractionDigits: 0 }).format(amount);
}

export function AdminMembershipPage() {
  const [members] = useState(MOCK_MEMBERS);
  const [searchQuery, setSearchQuery] = useState('');

  const filteredMembers = members.filter(m =>
    m.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    m.phone.includes(searchQuery)
  );

  const vipCount = members.filter(m => m.tier === 'GOLD' || m.tier === 'PLATINUM').length;
  const totalPoints = members.reduce((sum, m) => sum + m.points, 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-[var(--text-primary)]">Thành viên & Gói dịch vụ</h1>
        <p className="text-[var(--text-secondary)]">Quản lý thành viên và các gói membership</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-yellow-100">
              <Crown className="h-6 w-6 text-yellow-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{vipCount}</p>
              <p className="text-sm text-[var(--text-secondary)]">VIP (Gold+)</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-green-100">
              <Users className="h-6 w-6 text-green-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{members.filter(m => m.status === 'ACTIVE').length}</p>
              <p className="text-sm text-[var(--text-secondary)]">Đang hoạt động</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-blue-100">
              <Star className="h-6 w-6 text-blue-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-[var(--text-primary)]">{totalPoints.toLocaleString()}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng điểm</p>
            </div>
          </CardContent>
        </Card>
        <Card className="card-kpi">
          <CardContent className="p-5 flex items-center gap-4">
            <div className="p-3 rounded-xl bg-purple-100">
              <TrendingUp className="h-6 w-6 text-purple-600" />
            </div>
            <div>
              <p className="text-2xl font-semibold text-green-600">{formatCurrency(members.reduce((sum, m) => sum + m.totalSpent, 0))}</p>
              <p className="text-sm text-[var(--text-secondary)]">Tổng chi tiêu</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Search */}
      <Card className="card">
        <CardContent className="p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-[var(--text-tertiary)]" />
            <Input
              placeholder="Tìm thành viên..."
              className="pl-10 input"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      {/* Table */}
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Thành viên</th>
                <th>Hạng</th>
                <th>Điểm tích lũy</th>
                <th>Tổng chi tiêu</th>
                <th>Ngày tham gia</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {filteredMembers.map(member => (
                <tr key={member.id}>
                  <td>
                    <p className="font-medium text-[var(--text-primary)]">{member.name}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">{member.phone}</p>
                  </td>
                  <td><TierBadge tier={member.tier} /></td>
                  <td className="font-medium text-[var(--text-primary)]">{member.points.toLocaleString()} điểm</td>
                  <td className="text-[var(--text-secondary)]">{formatCurrency(member.totalSpent)}</td>
                  <td className="text-[var(--text-secondary)]">{member.joinDate}</td>
                  <td>
                    <Badge className={member.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}>
                      {member.status === 'ACTIVE' ? 'Hoạt động' : 'Hết hạn'}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
