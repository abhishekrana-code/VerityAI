import { Link } from 'react-router-dom';
import { Sparkles, Shield, Search, FileText, ArrowRight, CheckCircle, BookOpen } from 'lucide-react';
import { Logo } from '@/components/Logo';
import { cn } from '@/lib/utils';

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
      <header className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 sticky top-0 z-50">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-16 items-center justify-between">
            <Logo />
            <nav className="hidden md:flex items-center gap-6">
              <Link
                href="#features"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                Features
              </Link>
              <Link
                href="#how-it-works"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                How it works
              </Link>
              <Link
                href="#security"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                Security
              </Link>
              <Link
                to="/login"
                className="text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              >
                Sign in
              </Link>
              <Link
                to="/register"
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
              >
                Get Started
              </Link>
            </nav>
          </div>
        </div>
      </header>

      {/* Hero */}
      <main className="flex-1">
        <section className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-20 lg:py-32">
          <div className="text-center max-w-3xl mx-auto">
            <div className="inline-flex items-center gap-2 rounded-full bg-primary/10 px-3 py-1 text-sm font-medium text-primary mb-6">
              <Sparkles className="h-4 w-4" />
              <span>Answers you can verify</span>
            </div>
            <h1 className="text-4xl sm:text-5xl lg:text-6xl font-bold tracking-tight text-foreground mb-6 text-balance">
              AI Research Assistant with{' '}
              <span className="text-primary">Evidence-Based Answers</span>
            </h1>
            <p className="text-lg sm:text-xl text-muted-foreground mb-10 max-w-2xl mx-auto text-balance">
              Upload PDFs, ask questions, and get answers grounded in your documents.
              Every claim cites its source — page numbers, excerpts, and confidence scores included.
            </p>
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
              <Link
                to="/register"
                className="w-full sm:w-auto rounded-lg bg-primary px-8 py-3 text-base font-medium text-primary-foreground hover:bg-primary/90 transition-colors shadow-sm"
              >
                Start Free — No Credit Card Required
              </Link>
              <Link
                to="/login"
                className="w-full sm:w-auto rounded-lg border border-input bg-background px-8 py-3 text-base font-medium text-foreground hover:bg-accent transition-colors"
              >
                Sign In
              </Link>
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
                className="rounded-xl border bg-card p-6 hover:border-primary/50 transition-colors"
              >
                <div className="inline-flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary mb-4">
                  <feature.icon className="h-6 w-6" />
                </div>
                <h3 className="text-xl font-semibold mb-2">{feature.title}</h3>
                <p className="text-muted-foreground">{feature.description}</p>
              </article>
            ))}
          </div>
        </section>

        {/* How it works */}
        <section id="how-it-works" className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-20 lg:py-24 bg-muted/30">
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
        <section className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-20 lg:py-24 bg-primary text-primary-foreground rounded-xl">
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
      <footer className="border-t bg-muted/30 py-12">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="grid md:grid-cols-4 gap-8">
            <div>
              <Logo />
              <p className="mt-4 text-sm text-muted-foreground max-w-xs">
                VerityAI — Evidence-based AI research assistant.
                Answers you can verify.
              </p>
            </div>
            <nav>
              <h4 className="font-semibold mb-3">Product</h4>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li><Link href="#features" className="hover:text-foreground">Features</Link></li>
                <li><Link href="#how-it-works" className="hover:text-foreground">How it works</Link></li>
                <li><Link href="#security" className="hover:text-foreground">Security</Link></li>
              </ul>
            </nav>
            <nav>
              <h4 className="font-semibold mb-3">Company</h4>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li><Link href="#" className="hover:text-foreground">About</Link></li>
                <li><Link href="#" className="hover:text-foreground">Blog</Link></li>
                <li><Link href="#" className="hover:text-foreground">Careers</Link></li>
              </ul>
            </nav>
            <nav>
              <h4 className="font-semibold mb-3">Legal</h4>
              <ul className="space-y-2 text-sm text-muted-foreground">
                <li><Link href="#" className="hover:text-foreground">Privacy</Link></li>
                <li><Link href="#" className="hover:text-foreground">Terms</Link></li>
                <li><Link href="#" className="hover:text-foreground">Security</Link></li>
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