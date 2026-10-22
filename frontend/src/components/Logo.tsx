import { Sparkles } from 'lucide-react';

export function Logo() {
  return (
    <a href="/dashboard" className="flex items-center gap-2" aria-label="VerityAI Home">
      <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-primary-foreground">
        <Sparkles className="h-5 w-5" />
      </span>
      <span className="font-semibold text-lg tracking-tight">VerityAI</span>
    </a>
  );
}