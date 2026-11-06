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
      <span className="relative inline-flex h-9 w-9 items-center justify-center overflow-hidden rounded-xl border border-indigo-300/20 bg-gradient-to-br from-indigo-400 via-indigo-500 to-violet-600 text-white shadow-lg shadow-indigo-950/30 transition-transform group-hover:scale-[1.04]">
        <Sparkles className="h-[19px] w-[19px]" strokeWidth={2.2} />
        <span className="absolute -bottom-1 -right-1 rounded-full border-2 border-indigo-500 bg-emerald-400 p-[3px]">
          <ShieldCheck className="h-2 w-2 text-slate-950" />
        </span>
      </span>
      {!compact && (
        <span className={`font-[var(--font-display)] text-[17px] font-extrabold tracking-[-0.045em] ${light ? 'text-white' : 'text-foreground'}`}>
          Verity<span className="text-indigo-400">AI</span>
          <span className="ml-1.5 block text-[9px] font-semibold uppercase tracking-[0.19em] text-muted-foreground">Research, verified.</span>
        </span>
      )}
    </Link>
  );
}
