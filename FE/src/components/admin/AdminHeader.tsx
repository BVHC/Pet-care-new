import { Bell, Settings, LogOut, Sun, Moon } from 'lucide-react';
import { Button } from '../ui/button';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import { useNavigate } from 'react-router-dom';
import { ROLE_LABELS } from '../../shared/types/admin';
import { useState, useEffect } from 'react';

export function AdminHeader() {
  const { user, logout } = useAdminSession();
  const navigate = useNavigate();
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('theme');
    if (stored === 'dark' || (!stored && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
      setIsDark(true);
    }
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/admin/login');
  };

  const toggleTheme = () => {
    setIsDark(!isDark);
    document.documentElement.classList.toggle('dark');
    localStorage.setItem('theme', !isDark ? 'dark' : 'light');
  };

  return (
    <header className="admin-header h-16 px-6 flex items-center justify-between">
      <div className="flex items-center gap-4">
        <h2 className="text-base font-medium text-[var(--text-primary)]">
          Xin chào, <span className="font-semibold">{user?.name}</span>
        </h2>
        {user?.role && (
          <span className="px-2.5 py-1 bg-blue-50 text-blue-600 text-xs font-medium rounded-full">
            {ROLE_LABELS[user.role]}
          </span>
        )}
      </div>

      <div className="flex items-center gap-2">
        {/* Theme Toggle */}
        <button
          onClick={toggleTheme}
          className="theme-toggle"
          title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {isDark ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
        </button>

        {/* Notifications */}
        <Button variant="ghost" size="icon" className="relative">
          <Bell className="h-5 w-5 text-[var(--text-secondary)]" />
          <span className="absolute top-1 right-1 w-2 h-2 bg-red-500 rounded-full"></span>
        </Button>

        {/* Settings */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon">
              <Settings className="h-5 w-5 text-[var(--text-secondary)]" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="dropdown-content w-56">
            <DropdownMenuLabel className="p-3">
              <div>
                <p className="font-semibold text-[var(--text-primary)]">{user?.name}</p>
                <p className="text-xs text-[var(--text-secondary)]">{user?.email}</p>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator className="border-[var(--border-color)]" />
            <DropdownMenuItem className="dropdown-item">
              <Settings className="h-4 w-4" />
              Cài đặt
            </DropdownMenuItem>
            <DropdownMenuItem onClick={toggleTheme} className="dropdown-item">
              {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
              {isDark ? 'Chế độ sáng' : 'Chế độ tối'}
            </DropdownMenuItem>
            <DropdownMenuSeparator className="border-[var(--border-color)]" />
            <DropdownMenuItem onClick={handleLogout} className="dropdown-item text-red-500">
              <LogOut className="h-4 w-4" />
              Đăng xuất
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
