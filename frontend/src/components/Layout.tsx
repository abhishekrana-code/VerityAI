import { Outlet, NavLink, Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth, useLogout } from '@/hooks/useAuth';
import { ThemeToggle } from './ThemeToggle';
import { Logo } from './Logo';
import {
  LayoutDashboard, FolderOpen, MessageSquare, Settings, LogOut,
  ChevronLeft, ChevronRight, Menu, X, Plus, Search, ShieldCheck,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useEffect, useState } from 'react';

// Four destinations only: the core Upload → Ask → Check loop. Search stays in
// the header, Settings in the account menu, and the retired pages redirect to
// the Library.
const navigation = [
  { name: 'Home', href: '/dashboard', icon: LayoutDashboard, end: true },
  { name: 'Library', href: '/dashboard/documents', icon: FolderOpen },
  { name: 'Ask', href: '/dashboard/workspace', icon: MessageSquare },
  { name: 'Checks', href: '/dashboard/verification', icon: ShieldCheck },
];

const pageNames: Record<string, string> = {
  '/dashboard': 'Home',
  '/dashboard/search': 'Search',
  '/dashboard/documents': 'Library',
  '/dashboard/workspace': 'Ask',
  '/dashboard/verification': 'Checks',
  '/dashboard/settings': 'Settings',
};

export function Layout() {
  const { data: auth } = useAuth();
  const logout = useLogout();
  const navigate = useNavigate();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  // ⌘K / Ctrl+K opens the dedicated search page.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        navigate('/dashboard/search');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [navigate]);

  const handleLogout = async () => {
    await logout.mutateAsync();
    navigate('/login');
  };

  const pageTitle = location.pathname.startsWith('/dashboard/workspace/')
    ? 'Research session'
    : location.pathname.startsWith('/dashboard/documents/')
      ? 'Document details'
      : pageNames[location.pathname] || 'VerityAI';
  const displayName = auth?.user?.displayName || 'Researcher';
  const initials = displayName.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase();

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {mobileMenuOpen && (
        <button className="fixed inset-0 z-40 bg-slate-950/70 backdrop-blur-sm lg:hidden" onClick={() => setMobileMenuOpen(false)} aria-label="Close navigation overlay" />
      )}

      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-50 flex w-[268px] flex-col border-r border-border/80 bg-card/95 backdrop-blur-xl transition-all duration-300 lg:sticky lg:top-0 lg:h-screen',
          sidebarOpen ? 'lg:w-[268px]' : 'lg:w-[84px]',
          mobileMenuOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
        aria-label="Main navigation"
      >
        <div className={cn('flex h-[76px] shrink-0 items-center border-b border-border/70 px-5', !sidebarOpen && 'lg:justify-center lg:px-3')}>
          <Logo compact={!sidebarOpen} />
          <button className="ml-auto rounded-lg p-2 text-muted-foreground hover:bg-accent hover:text-foreground lg:hidden" onClick={() => setMobileMenuOpen(false)} aria-label="Close menu"><X className="h-4 w-4" /></button>
        </div>

        <div className={cn('px-4 pt-5', !sidebarOpen && 'lg:px-3')}>
          <button
            onClick={() => navigate('/dashboard/workspace')}
            className={cn('flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground transition hover:bg-primary/90', !sidebarOpen && 'lg:px-0')}
            title="Start new research"
          >
            <Plus className="h-4 w-4 shrink-0" />
            <span className={!sidebarOpen ? 'lg:hidden' : ''}>New research</span>
          </button>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-6" aria-label="Primary">
          <p className={cn('eyebrow mb-3 px-3', !sidebarOpen && 'lg:hidden')}>Workspace</p>
          {navigation.map((item) => (
            <NavLink
              key={item.name}
              to={item.href}
              end={item.end}
              title={!sidebarOpen ? item.name : undefined}
              className={({ isActive }) => cn(
                'group flex items-center gap-3 rounded-lg px-3 py-2.5 text-[13px] font-medium transition-all',
                isActive
                  ? 'bg-accent text-accent-foreground'
                  : 'text-muted-foreground hover:bg-accent/80 hover:text-foreground',
                !sidebarOpen && 'lg:justify-center lg:px-0'
              )}
              onClick={() => setMobileMenuOpen(false)}
            >
              <item.icon className="h-[18px] w-[18px] shrink-0" aria-hidden="true" />
              <span className={!sidebarOpen ? 'lg:hidden' : ''}>{item.name}</span>
              {item.name === 'Ask' && sidebarOpen && <span className="ml-auto rounded-md bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">AI</span>}
            </NavLink>
          ))}
        </nav>

        <div className="border-t border-border/70 p-3">
          <div className={cn('flex items-center gap-3 rounded-lg border border-border/70 p-2.5', !sidebarOpen && 'lg:justify-center lg:p-2')}>
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-accent text-[11px] font-semibold text-accent-foreground">{initials}</div>
            <div className={cn('min-w-0 flex-1', !sidebarOpen && 'lg:hidden')}>
              <p className="truncate text-xs font-semibold text-foreground">{displayName}</p>
              <p className="truncate text-[10px] text-muted-foreground">{auth?.user?.email}</p>
            </div>
            <Link to="/dashboard/settings" onClick={() => setMobileMenuOpen(false)} className={cn('rounded-md p-2 text-muted-foreground transition hover:bg-muted hover:text-foreground', !sidebarOpen && 'lg:hidden')} title="Settings" aria-label="Settings">
              <Settings className="h-4 w-4" />
            </Link>
            <button onClick={handleLogout} disabled={logout.isPending} className={cn('rounded-md p-2 text-muted-foreground transition hover:bg-muted hover:text-destructive disabled:opacity-50', !sidebarOpen && 'lg:hidden')} title="Sign out" aria-label="Sign out">
              <LogOut className="h-4 w-4" />
            </button>
          </div>
          {!sidebarOpen && (
            <div className="mt-2 hidden justify-center gap-1 lg:flex">
              <Link to="/dashboard/settings" className="rounded-lg p-2 text-muted-foreground hover:bg-accent hover:text-foreground" title="Settings" aria-label="Settings"><Settings className="h-4 w-4" /></Link>
              <button onClick={handleLogout} disabled={logout.isPending} className="rounded-lg p-2 text-muted-foreground hover:bg-accent hover:text-rose-700 dark:hover:text-rose-300" aria-label="Sign out"><LogOut className="h-4 w-4" /></button>
            </div>
          )}
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-[76px] items-center gap-3 border-b border-border/70 bg-background/75 px-4 backdrop-blur-xl sm:px-6 lg:px-8">
          <button className="rounded-xl p-2 text-muted-foreground hover:bg-accent hover:text-foreground lg:hidden" onClick={() => setMobileMenuOpen(true)} aria-label="Open menu"><Menu className="h-5 w-5" /></button>
          <button className="hidden rounded-xl p-2 text-muted-foreground hover:bg-accent hover:text-foreground lg:inline-flex" onClick={() => setSidebarOpen(!sidebarOpen)} aria-label={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'} title={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}>
            {sidebarOpen ? <ChevronLeft className="h-[18px] w-[18px]" /> : <ChevronRight className="h-[18px] w-[18px]" />}
          </button>
          <div className="min-w-0">
            <p className="text-sm font-semibold tracking-tight">{pageTitle}</p>
            <p className="hidden text-[11px] text-muted-foreground sm:block">Your evidence-first research environment</p>
          </div>
          <div className="ml-auto flex items-center gap-2">
            <button
              onClick={() => navigate('/dashboard/search')}
              className="hidden items-center gap-2 rounded-full border border-border bg-card px-4 py-2 text-xs text-muted-foreground transition hover:border-primary/30 hover:text-foreground xl:flex"
              aria-label="Search your documents"
            >
              <Search className="h-3.5 w-3.5" />
              <span>Find anything</span>
              <kbd className="ml-4 rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-[10px]">⌘ K</kbd>
            </button>
            <ThemeToggle />
            <span className="hidden h-8 w-px bg-border sm:block" />
            <div className="hidden h-8 w-8 items-center justify-center rounded-md bg-accent text-[11px] font-semibold text-accent-foreground sm:flex">{initials}</div>
          </div>
        </header>

        <main id="main-content" tabIndex={-1} className="w-full flex-1 overflow-y-auto px-4 py-7 sm:px-6 lg:px-9 lg:py-9">
          <div className="mx-auto w-full max-w-[1440px]"><Outlet /></div>
        </main>
        <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-border/60 px-5 py-3 text-[10px] text-muted-foreground sm:px-8">
          <span>© {new Date().getFullYear()} VerityAI · Cited answers from your PDFs.</span>
          <span className="inline-flex items-center gap-1.5"><span className="h-1.5 w-1.5 rounded-full bg-emerald-400" /> Research workspace ready</span>
        </footer>
      </div>
    </div>
  );
}
