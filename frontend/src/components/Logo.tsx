import { Link } from 'react-router-dom';
import { Sparkles, ShieldCheck } from 'lucide-react';

interface LogoProps {
  compact?: boolean;
  light?: boolean;
  to?: string;
}

export function Logo({ compact = false, light = false, to = '/dashboard' }: LogoProps) {
  return (
    <Link to={to} className="group inline-flex items-center gap-2.5" aria-label="VerityAI home">
      <span className="relative inline-flex h-9 w-9 items-center justify-center rounded-lg bg-primary text-primary-foreground transition-transform group-hover:scale-[1.03]">
        <Sparkles className="h-[18px] w-[18px]" strokeWidth={2} />
        <span className="absolute -bottom-1 -right-1 rounded-full border-2 border-card bg-emerald-400 p-[3px]">
          <ShieldCheck className="h-2 w-2 text-slate-950" />
        </span>
      </span>
      {!compact && (
        <span className={`font-[var(--font-display)] text-[17px] font-semibold tracking-[-0.02em] ${light ? 'text-white' : 'text-foreground'}`}>
          Verity<span className="text-primary">AI</span>
          <span className="mt-0.5 block text-[10px] font-medium tracking-normal text-muted-foreground">Research, verified.</span>
        </span>
      )}
    </Link>
  );
}
