import { Link } from 'react-router-dom';
import { ArrowLeft, ShieldCheck } from 'lucide-react';
import { Logo } from '@/components/Logo';

const LAST_UPDATED = 'November 9, 2026';

function LegalLayout({
  title,
  summary,
  children,
}: {
  title: string;
  summary: string;
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b border-border/70 bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex h-[76px] max-w-4xl items-center justify-between px-4 sm:px-6">
          <Logo to="/" />
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm font-medium text-muted-foreground transition hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
            Back to home
          </Link>
        </div>
      </header>

      <main id="main-content" className="mx-auto max-w-4xl px-4 py-14 sm:px-6 lg:py-20">
        <p className="eyebrow mb-3">Legal</p>
        <h1 className="page-heading">{title}</h1>
        <p className="mt-4 max-w-2xl text-base leading-7 text-muted-foreground">{summary}</p>
        <p className="mt-3 text-xs text-muted-foreground">Last updated: {LAST_UPDATED}</p>

        <div className="mt-10 space-y-8 text-sm leading-7 text-muted-foreground">{children}</div>

        <nav className="mt-14 flex flex-wrap gap-x-6 gap-y-2 border-t border-border/70 pt-6 text-sm">
          <Link to="/privacy" className="font-medium text-foreground hover:text-primary">Privacy Policy</Link>
          <Link to="/terms" className="font-medium text-foreground hover:text-primary">Terms of Service</Link>
          <Link to="/security" className="font-medium text-foreground hover:text-primary">Security</Link>
          <Link to="/" className="font-medium text-foreground hover:text-primary">Home</Link>
        </nav>
      </main>
    </div>
  );
}

function Section({ heading, children }: { heading: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="mb-2 text-lg font-semibold text-foreground">{heading}</h2>
      {children}
    </section>
  );
}

export function PrivacyPage() {
  return (
    <LegalLayout
      title="Privacy Policy"
      summary="This policy explains what VerityAI stores, why we store it, and the choices you have. VerityAI is self-hosted software: the operator of your deployment is the data controller."
    >
      <Section heading="Information we process">
        <ul className="list-disc space-y-1 pl-5">
          <li>Account details: email address, display name, password hash, role, and sign-in timestamps.</li>
          <li>Research content: documents you upload, extracted text and page chunks, conversation messages, citations, summaries, knowledge-graph entries, and verification results.</li>
          <li>Technical data: session identifiers, request IDs, and rate-limit counters needed to operate and protect the service.</li>
        </ul>
      </Section>

      <Section heading="How we use it">
        <p>
          We use this information solely to provide the research workspace: authenticating you, processing and indexing your
          documents, answering your questions with cited evidence, and protecting the service from abuse. We do not sell
          personal data, and we do not use your documents to train third-party models.
        </p>
      </Section>

      <Section heading="Third-party processing">
        <p>
          VerityAI can be configured with a language-model provider (for example Groq) and an embedding model. When enabled,
          the minimum text needed to answer a question or build an index is sent to that provider under the operator&apos;s
          account. Operators who require fully local processing can disable external AI providers in the deployment
          configuration.
        </p>
      </Section>

      <Section heading="Retention">
        <p>
          Your content remains until you delete it or the operator removes your account. Deleting a document removes its
          extracted text, chunks, summaries, graph entries, and contradictions. Password-reset tokens are stored only as
          hashes and expire after a short, configurable window. Session records expire automatically.
        </p>
      </Section>

      <Section heading="Your rights">
        <p>
          Depending on where you live, you may have the right to access, correct, export, or delete your personal data, and
          to object to certain processing. Contact the operator of your VerityAI deployment to exercise these rights. You can
          update your display name and password from Settings at any time.
        </p>
      </Section>

      <Section heading="Security">
        <p>
          Passwords are hashed with bcrypt. Sessions are server-side and rotated on sign-in. Uploads are validated before
          storage. See the <Link to="/security" className="font-medium text-primary hover:underline">Security page</Link> for
          more detail.
        </p>
      </Section>

      <Section heading="Contact">
        <p>
          Privacy questions should be directed to the operator of the deployment you use. If you self-host VerityAI, you are
          responsible for publishing your own contact details and lawful basis.
        </p>
      </Section>
    </LegalLayout>
  );
}

export function TermsPage() {
  return (
    <LegalLayout
      title="Terms of Service"
      summary="These terms govern your use of VerityAI. By creating an account or using the service, you agree to them. If you do not agree, do not use the service."
    >
      <Section heading="The service">
        <p>
          VerityAI is an evidence-based research assistant that retrieves passages from documents you provide and generates
          answers with citations. AI output can be incomplete or wrong. You are responsible for reviewing the cited evidence
          before relying on any answer.
        </p>
      </Section>

      <Section heading="Accounts">
        <p>
          You must provide accurate account information and keep your credentials secure. One active session is permitted per
          account; signing in elsewhere ends the earlier session. Do not share your account or attempt to access another
          user&apos;s documents.
        </p>
      </Section>

      <Section heading="Acceptable use">
        <ul className="list-disc space-y-1 pl-5">
          <li>Upload only content you have the right to use and process.</li>
          <li>Do not use the service to violate law, infringe rights, or distribute malware.</li>
          <li>Do not attempt to bypass authentication, rate limits, or tenant isolation.</li>
          <li>Do not probe, scan, or overload the service without written authorization.</li>
        </ul>
      </Section>

      <Section heading="Your content">
        <p>
          You retain ownership of the documents and messages you submit. You grant the operator a limited licence to store and
          process that content to operate the service. The operator may remove content that violates these terms or the law.
        </p>
      </Section>

      <Section heading="Availability and liability">
        <p>
          The service is provided on an &ldquo;as is&rdquo; and &ldquo;as available&rdquo; basis. To the maximum extent
          permitted by law, the operator is not liable for indirect or consequential loss, or for decisions made in reliance
          on AI-generated output. Nothing in these terms excludes liability that cannot lawfully be excluded.
        </p>
      </Section>

      <Section heading="Changes">
        <p>
          The operator may update these terms. Material changes will be announced in the application or by email. Continued
          use after an update means you accept the revised terms.
        </p>
      </Section>
    </LegalLayout>
  );
}

export function SecurityPage() {
  return (
    <LegalLayout
      title="Security"
      summary="VerityAI is designed so that research stays private and every answer can be traced back to its evidence. This page describes the controls in the application."
    >
      <div className="flex items-start gap-3 rounded-2xl border border-emerald-400/20 bg-emerald-400/[0.06] p-5">
        <ShieldCheck className="mt-0.5 h-5 w-5 flex-shrink-0 text-emerald-400" aria-hidden="true" />
        <p className="text-sm text-foreground">
          No system is perfectly secure. If you discover a vulnerability, report it privately to the operator of your
          deployment rather than disclosing it publicly.
        </p>
      </div>

      <Section heading="Authentication and sessions">
        <ul className="list-disc space-y-1 pl-5">
          <li>Passwords are hashed with bcrypt; plaintext passwords are never stored.</li>
          <li>Sessions are stored server-side in the database, not in the cookie. The cookie holds only an opaque identifier.</li>
          <li>Session identifiers are rotated on sign-in to prevent session fixation.</li>
          <li>Signing in invalidates other active sessions for the same account.</li>
          <li>Password resets use single-use, hashed, expiring tokens and invalidate existing sessions.</li>
        </ul>
      </Section>

      <Section heading="Request protection">
        <ul className="list-disc space-y-1 pl-5">
          <li>State-changing requests require a CSRF token.</li>
          <li>Authentication, registration, uploads, AI calls, and password resets are rate limited.</li>
          <li>Registration and password-reset responses do not reveal whether an email address exists.</li>
          <li>Proxy headers are trusted only from configured loopback or private proxies.</li>
        </ul>
      </Section>

      <Section heading="Document handling">
        <ul className="list-disc space-y-1 pl-5">
          <li>Uploads must be genuine PDFs: the file signature is checked before the file is stored.</li>
          <li>Documents, chunks, summaries, and graph data are scoped to their owner on every query.</li>
          <li>Processing runs on a bounded background pool; overload is rejected rather than silently dropped.</li>
          <li>Deleting a document removes its derived data as well as the original file.</li>
        </ul>
      </Section>

      <Section heading="Data isolation">
        <p>
          Every API query is scoped by the authenticated user. Cross-account access to documents, conversations, citations,
          verification results, and contradictions is rejected. Operators should still isolate the database, object storage,
          and backups at the infrastructure level.
        </p>
      </Section>

      <Section heading="Responsible disclosure">
        <p>
          Include the affected version, a minimal reproduction, and the impact in your report. Do not access data that is not
          yours, and give the operator reasonable time to fix the issue before public disclosure.
        </p>
      </Section>
    </LegalLayout>
  );
}
