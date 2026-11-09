import { Link } from 'react-router-dom';
import { Sparkles, Shield, Search, FileText, ArrowRight, CheckCircle, BookOpen, Quote, ShieldCheck } from 'lucide-react';
import { Logo } from '@/components/Logo';

const features = [
  {
    icon: FileText,
    title: 'Document Intelligence',
    description: 'Upload PDFs and research papers. VerityAI extracts, chunks, and indexes every page for precise retrieval.',
  },
  {
    icon: Search,
    title: 'Evidence-Based Answers',
    description: 'Every answer cites its sources with page numbers and excerpts. No hallucinations — only what your documents support.',
  },
  {
    icon: Shield,
    title: 'Private & Secure',
    description: 'Your documents never leave your infrastructure. Session-based auth, encrypted storage, and full audit trails.',
  },
  {
    icon: BookOpen,
    title: 'Research Workspace',
    description: 'Organize conversations by project. Revisit previous research, share context, and build on prior findings.',
  },
];

const trustPoints = [
  'Source citations with page-level precision',
  'Full-text search across all uploaded documents',
  'Configurable AI model via Groq API',
  'Open architecture — extend with vector search',
  'Self-hosted — your data, your control',
];

export function LandingPage() {
  return (
    <div className="min-h-screen bg-background flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-50 border-b border-border/70 bg-background/80 backdrop-blur-2xl">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-[76px] items-center justify-between">
            <Logo to="/" />
            <nav className="hidden md:flex items-center gap-6">
              <a
                href="#features"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                Features
              </a>
              <a
                href="#how-it-works"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                How it works
              </a>
              <a
                href="#security"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                Security
              </a>
              <Link
                to="/login"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                Sign in
              </Link>
              <Link
                to="/register"
                className="rounded-xl bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground shadow-lg shadow-indigo-950/25 transition hover:-translate-y-0.5 hover:bg-primary/90"
              >
                Get Started
              </Link>
            </nav>
          </div>
        </div>
      </header>

      {/* Hero */}
      <main id="main-content" tabIndex={-1} className="flex-1">
        <section className="relative isolate mx-auto w-full max-w-7xl overflow-hidden px-4 py-20 sm:px-6 lg:px-8 lg:py-28">
          <div className="pointer-events-none absolute inset-x-0 top-0 -z-10 mx-auto h-[520px] max-w-5xl rounded-full bg-indigo-500/[0.09] blur-[120px]" />
          <div className="subtle-grid pointer-events-none absolute inset-x-0 top-0 -z-10 h-[540px] opacity-40" />
          <div className="mx-auto max-w-4xl text-center">
            <div className="mb-7 inline-flex items-center gap-2 rounded-full border border-indigo-400/20 bg-indigo-400/10 px-4 py-2 text-xs font-semibold tracking-wide text-indigo-300 shadow-lg shadow-indigo-950/10">
              <Sparkles className="h-4 w-4" />
              <span>Answers you can verify</span>
            </div>
            <h1 className="mb-7 text-4xl font-extrabold leading-[1.08] tracking-[-0.055em] text-foreground sm:text-5xl lg:text-[4.35rem]">
              AI Research Assistant with{' '}
              <span className="bg-gradient-to-r from-indigo-300 via-violet-300 to-sky-300 bg-clip-text text-transparent">Evidence-Based Answers</span>
            </h1>
            <p className="mx-auto mb-10 max-w-2xl text-base leading-8 text-muted-foreground sm:text-lg">
              Upload PDFs, ask questions, and get answers grounded in your documents.
              Every claim cites its source — page numbers, excerpts, and confidence scores included.
            </p>
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
              <Link
                to="/register"
                className="w-full rounded-xl bg-primary px-7 py-3.5 text-sm font-semibold text-primary-foreground shadow-xl shadow-indigo-950/30 transition hover:-translate-y-0.5 hover:bg-primary/90 sm:w-auto"
              >
                Start Free — No Credit Card Required
              </Link>
              <Link
                to="/login"
                className="w-full rounded-xl border border-border bg-card/70 px-7 py-3.5 text-sm font-semibold text-foreground transition hover:border-primary/40 hover:bg-accent sm:w-auto"
              >
                Sign In
              </Link>
            </div>
          </div>

          <div className="mx-auto mt-20 max-w-5xl rounded-[26px] border border-white/10 bg-gradient-to-b from-slate-800/80 to-slate-950/90 p-2 shadow-[0_35px_100px_-35px_rgba(79,70,229,0.45)] sm:mt-24 sm:p-3">
            <div className="overflow-hidden rounded-[20px] border border-white/10 bg-[#0c1220] text-left">
              <div className="flex items-center justify-between border-b border-white/10 px-5 py-4">
                <div className="flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rose-400"/><span className="h-2 w-2 rounded-full bg-amber-400"/><span className="h-2 w-2 rounded-full bg-emerald-400"/><span className="ml-3 text-xs font-medium text-slate-400">Research workspace · Example preview</span></div>
                <span className="rounded-full border border-emerald-400/20 bg-emerald-400/10 px-2.5 py-1 text-[10px] font-semibold text-emerald-300">Evidence-first</span>
              </div>
              <div className="grid md:grid-cols-[1fr_260px]">
                <div className="p-5 sm:p-8">
                  <p className="mb-3 text-[10px] font-bold uppercase tracking-[0.18em] text-indigo-300">Research question</p>
                  <h3 className="mb-6 text-xl font-bold tracking-tight text-slate-100 sm:text-2xl">What are the key findings in my source material?</h3>
                  <div className="rounded-2xl border border-white/10 bg-white/[0.035] p-4 sm:p-5">
                    <div className="mb-3 flex items-center gap-2 text-xs font-semibold text-slate-300"><Sparkles className="h-3.5 w-3.5 text-indigo-300"/> VerityAI response</div>
                    <p className="text-sm leading-7 text-slate-300">A clear research answer is organized around the evidence retrieved from your documents. Supporting passages can be reviewed alongside the response, helping you trace each conclusion back to its source.</p>
                    <div className="mt-5 flex flex-wrap gap-2"><span className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-300/20 bg-indigo-400/10 px-2.5 py-1.5 text-[10px] font-semibold text-indigo-200"><Quote className="h-3 w-3"/> Source citation</span><span className="inline-flex items-center gap-1.5 rounded-lg border border-white/10 bg-white/5 px-2.5 py-1.5 text-[10px] font-medium text-slate-400"><FileText className="h-3 w-3"/> Retrieved context</span></div>
                  </div>
                </div>
                <aside className="border-t border-white/10 bg-white/[0.025] p-5 md:border-l md:border-t-0">
                  <p className="mb-4 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">Evidence panel</p>
                  <div className="space-y-3">
                    <div className="rounded-xl border border-white/10 bg-white/[0.04] p-3"><div className="mb-2 flex items-center justify-between"><span className="text-xs font-semibold text-slate-200">Source excerpt</span><FileText className="h-3.5 w-3.5 text-indigo-300"/></div><p className="text-[11px] leading-5 text-slate-400">Relevant passages appear here with document context and page references.</p><p className="mt-2 text-[10px] font-semibold text-indigo-300">Document · Page reference</p></div>
                    <div className="rounded-xl border border-emerald-400/15 bg-emerald-400/[0.06] p-3"><div className="flex items-center gap-2 text-xs font-semibold text-emerald-300"><ShieldCheck className="h-3.5 w-3.5"/> Traceable evidence</div><p className="mt-2 text-[11px] leading-5 text-slate-400">Review source material before relying on a conclusion.</p></div>
                  </div>
                </aside>
              </div>
            </div>
          </div>
        </section>

        {/* Features */}
        <section id="features" className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-20 lg:py-24">
          <div className="text-center max-w-2xl mx-auto mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold tracking-tight mb-4">
              Built for Serious Research
            </h2>
            <p className="text-muted-foreground text-lg">
              VerityAI combines retrieval-augmented generation with rigorous source attribution.
            </p>
          </div>
          <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6">
            {features.map((feature) => (
              <article
                key={feature.title}
                className="group rounded-2xl border border-border/80 bg-card/80 p-6 shadow-sm transition duration-300 hover:-translate-y-1 hover:border-primary/40 hover:shadow-xl hover:shadow-indigo-950/15"
              >
                <div className="mb-5 inline-flex h-12 w-12 items-center justify-center rounded-xl border border-primary/15 bg-primary/10 text-primary transition group-hover:bg-primary group-hover:text-primary-foreground">
                  <feature.icon className="h-6 w-6" />
                </div>
                <h3 className="text-xl font-semibold mb-2">{feature.title}</h3>
                <p className="text-muted-foreground">{feature.description}</p>
              </article>
            ))}
          </div>
        </section>

        {/* How it works */}
        <section id="how-it-works" className="mx-auto max-w-7xl rounded-[28px] border border-border/60 bg-card/45 px-4 py-20 sm:px-8 lg:px-12 lg:py-24">
          <div className="text-center max-w-2xl mx-auto mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold tracking-tight mb-4">
              How VerityAI Works
            </h2>
            <p className="text-muted-foreground text-lg">
              A transparent pipeline from document to answer.
            </p>
          </div>
          <div className="grid md:grid-cols-3 gap-8 max-w-4xl mx-auto">
            {[
              {
                step: '01',
                title: 'Upload Documents',
                description: 'Drag and drop PDFs. VerityAI extracts text, detects pages, and creates searchable chunks.',
                icon: FileText,
              },
              {
                step: '02',
                title: 'Ask Questions',
                description: 'Type your question in natural language. Select which documents to search.',
                icon: Search,
              },
              {
                step: '03',
                title: 'Get Verified Answers',
                description: 'Receive evidence-based responses with inline citations. Click any citation to see the source.',
                icon: CheckCircle,
              },
            ].map((item) => (
              <div key={item.step} className="relative">
                <div className="absolute -left-6 top-0 w-4 h-full bg-primary/20" />
                <div className="pl-6 pb-8 border-l-2 border-border last:border-0">
                  <div className="flex items-center gap-3 mb-4">
                    <span className="text-3xl font-bold text-primary/30">{item.step}</span>
                    <div className="inline-flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                      <item.icon className="h-5 w-5" />
                    </div>
                  </div>
                  <h3 className="text-xl font-semibold mb-2">{item.title}</h3>
                  <p className="text-muted-foreground">{item.description}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* Security */}
        <section id="security" className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-20 lg:py-24">
          <div className="text-center max-w-2xl mx-auto mb-16">
            <h2 className="text-3xl sm:text-4xl font-bold tracking-tight mb-4">
              Security & Privacy by Design
            </h2>
            <p className="text-muted-foreground text-lg">
              Your research data never leaves your control.
            </p>
          </div>
          <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6 max-w-4xl mx-auto">
            {trustPoints.map((point, i) => (
              <div key={i} className="flex items-start gap-3 p-4 rounded-lg border hover:border-primary/50 transition-colors">
                <CheckCircle className="h-5 w-5 text-primary flex-shrink-0 mt-0.5" />
                <span className="text-sm">{point}</span>
              </div>
            ))}
          </div>
        </section>

        {/* CTA */}
        <section className="mx-4 my-12 rounded-[28px] border border-indigo-300/20 bg-gradient-to-br from-indigo-600 via-indigo-600 to-violet-700 px-6 py-20 text-white shadow-2xl shadow-indigo-950/25 sm:mx-auto sm:max-w-7xl sm:px-12 lg:py-24">
          <div className="text-center max-w-2xl mx-auto">
            <h2 className="text-3xl sm:text-4xl font-bold tracking-tight mb-4">
              Ready to Research Smarter?
            </h2>
            <p className="text-primary-foreground/80 mb-8 text-lg">
              Join researchers, analysts, and students who trust VerityAI for evidence-based answers.
            </p>
            <Link
              to="/register"
              className="inline-flex items-center gap-2 rounded-lg bg-primary-foreground px-8 py-3 text-base font-medium text-primary hover:bg-primary-foreground/90 transition-colors"
            >
              Create Free Account
              <ArrowRight className="h-4 w-4" />
            </Link>
          </div>
        </section>
      </main>

      {/* Footer */}
      <footer className="mt-16 border-t border-border/70 bg-card/35 py-12">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="grid md:grid-cols-4 gap-8">
            <div>
              <Logo to="/" />
              <p className="mt-4 text-sm text-muted-foreground max-w-xs">
                VerityAI — Evidence-based AI research assistant.
                Answers you can verify.
              </p>
            </div>
            <nav>
              <h4 className="font-semibold mb-3">Product</h4>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li><a href="#features" className="hover:text-foreground">Features</a></li>
                <li><a href="#how-it-works" className="hover:text-foreground">How it works</a></li>
                <li><a href="#security" className="hover:text-foreground">Security</a></li>
              </ul>
            </nav>
            <nav>
              <h4 className="font-semibold mb-3">Get started</h4>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li><Link to="/register" className="hover:text-foreground">Create account</Link></li>
                <li><Link to="/login" className="hover:text-foreground">Sign in</Link></li>
                <li><Link to="/forgot-password" className="hover:text-foreground">Reset password</Link></li>
              </ul>
            </nav>
            <nav>
              <h4 className="font-semibold mb-3">Legal</h4>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li><Link to="/privacy" className="hover:text-foreground">Privacy</Link></li>
                <li><Link to="/terms" className="hover:text-foreground">Terms</Link></li>
                <li><Link to="/security" className="hover:text-foreground">Security</Link></li>
              </ul>
            </nav>
          </div>
          <div className="mt-12 pt-8 border-t text-center text-sm text-muted-foreground">
            <p>© {new Date().getFullYear()} VerityAI. All rights reserved.</p>
          </div>
        </div>
      </footer>
    </div>
  );
}