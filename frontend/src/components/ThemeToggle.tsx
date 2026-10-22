'use client';

import * as React from 'react';
import { Moon, Sun, Monitor } from 'lucide-react';
import { useTheme } from './ThemeProvider';
import { cn } from '@/lib/utils';

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  return (
    <div className="flex items-center gap-1 rounded-lg bg-muted p-1">
      {(['light', 'dark', 'system'] as const).map((t) => (
        <button
          key={t}
          onClick={() => setTheme(t)}
          className={cn(
            'flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
            theme === t
              ? 'bg-background text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          )}
          title={t === 'system' ? 'System' : t.charAt(0).toUpperCase() + t.slice(1)}
        >
          {t === 'light' && <Sun className="h-4 w-4" />}
          {t === 'dark' && <Moon className="h-4 w-4" />}
          {t === 'system' && <Monitor className="h-4 w-4" />}
          <span className="hidden sm:inline">{t}</span>
        </button>
      ))}
    </div>
  );
}