'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  api,
  type Document,
  type SearchResponse,
  type SearchHit,
  type SearchStrategy,
} from '@/lib/api';
import { errorToast } from '@/lib/errors';
import { cn } from '@/lib/utils';
import { PdfPreviewModal, type PdfPreviewTarget } from '@/components/PdfPreviewModal';
import { PageEmpty, PageError, PageLoading } from '@/components/ui/PageStates';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';
import {
  ArrowUpRight, FileText, Filter, Loader2, Search, Sparkles, X,
} from 'lucide-react';

const STRATEGY_LABELS: Record<SearchStrategy, { label: string; className: string }> = {
  hybrid: { label: 'Hybrid', className: 'bg-indigo-500/15 text-indigo-300 border-indigo-400/25' },
  semantic: { label: 'Semantic', className: 'bg-violet-500/15 text-violet-300 border-violet-400/25' },
  keyword: { label: 'Keyword', className: 'bg-emerald-500/15 text-emerald-300 border-emerald-400/25' },
};

export function SearchPage() {
  const { toast } = useToast();
  const [query, setQuery] = useState('');
  const [selectedDocs, setSelectedDocs] = useState<string[]>([]);
  const [scopeOpen, setScopeOpen] = useState(false);
  const [preview, setPreview] = useState<{ doc: PdfPreviewTarget; page: number } | null>(null);

  const { data: documents } = useQuery({
    queryKey: ['documents', 'ready', 'search-page'],
    queryFn: () => api.get<{ content: Document[] }>('/documents?size=100&status=READY'),
    select: (data) => data.content,
  });

  const search = useMutation({
    mutationFn: (q: string) =>
      api.post<SearchResponse>('/search', {
        query: q,
        documentIds: selectedDocs.length > 0 ? selectedDocs : undefined,
        limit: 20,
      }),
    onError: (error: Error) => toast(errorToast(error, 'Search failed')),
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || search.isPending) return;
    search.mutate(trimmed);
  };

  const toggleDocument = (id: string) => {
    setSelectedDocs((current) =>
      current.includes(id) ? current.filter((value) => value !== id) : [...current, id]);
  };

  const result = search.data;
  const strategy = result ? STRATEGY_LABELS[result.strategy] : null;

  return (
    <div className="space-y-6">
      <section className="relative isolate overflow-hidden rounded-[26px] border border-indigo-400/15 bg-gradient-to-br from-indigo-500/[0.14] via-card to-violet-500/[0.08] p-6 sm:p-8">
        <div className="pointer-events-none absolute -right-16 -top-24 -z-10 h-64 w-64 rounded-full bg-indigo-500/15 blur-[90px]" />
        <p className="eyebrow">Evidence search</p>
        <h1 className="mt-2 text-2xl font-extrabold tracking-tight sm:text-3xl">Search your library</h1>
        <p className="mt-2 max-w-2xl text-sm leading-7 text-muted-foreground">
          Full-text and semantic retrieval over your own documents. Every hit shows the
          exact passage, page and match type — no synthesized answers.
        </p>

        <form onSubmit={submit} className="mt-6 space-y-3" role="search">
          <div className="flex flex-col gap-2 sm:flex-row">
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search for a concept, name, figure or phrase…"
                aria-label="Search query"
                autoFocus
                className="h-12 w-full rounded-xl border border-input bg-background/80 pl-10 pr-10 text-sm shadow-inner outline-none transition focus:border-ring focus:ring-2 focus:ring-ring/30"
              />
              {query && (
                <button
                  type="button"
                  onClick={() => setQuery('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
                  aria-label="Clear search"
                >
                  <X className="h-4 w-4" />
                </button>
              )}
            </div>
            <button
              type="submit"
              disabled={!query.trim() || search.isPending}
              className="inline-flex h-12 items-center justify-center gap-2 rounded-xl bg-primary px-5 text-sm font-semibold text-primary-foreground shadow-lg shadow-indigo-950/20 transition hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {search.isPending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Sparkles className="h-4 w-4" aria-hidden="true" />}
              Search
            </button>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setScopeOpen((open) => !open)}
              className="inline-flex items-center gap-2 rounded-lg border border-border/80 bg-card/60 px-3 py-2 text-xs font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground"
              aria-expanded={scopeOpen}
            >
              <Filter className="h-3.5 w-3.5" aria-hidden="true" />
              {selectedDocs.length === 0 ? 'All documents' : `${selectedDocs.length} document${selectedDocs.length > 1 ? 's' : ''} selected`}
            </button>
            {selectedDocs.length > 0 && (
              <button
                type="button"
                onClick={() => setSelectedDocs([])}
                className="text-xs font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              >
                Clear scope
              </button>
            )}
            <span className="text-[11px] text-muted-foreground">
              Hybrid retrieval fuses keyword and vector matches.
            </span>
          </div>

          {scopeOpen && (
            <div className="max-h-56 overflow-y-auto rounded-xl border border-border/80 bg-card/80 p-2">
              {!documents ? (
                <p className="p-3 text-xs text-muted-foreground">Loading documents…</p>
              ) : documents.length === 0 ? (
                <p className="p-3 text-xs text-muted-foreground">No processed documents yet. Upload and process a PDF first.</p>
              ) : (
                <ul className="space-y-1">
                  {documents.map((document) => (
                    <li key={document.id}>
                      <label className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-accent/70">
                        <input
                          type="checkbox"
                          checked={selectedDocs.includes(document.id)}
                          onChange={() => toggleDocument(document.id)}
                          className="rounded border-input"
                        />
                        <FileText className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                        <span className="min-w-0 flex-1 truncate">{document.originalFilename}</span>
                        {document.pageCount != null && (
                          <span className="text-[11px] text-muted-foreground">{document.pageCount} pages</span>
                        )}
                      </label>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </form>
      </section>

      {search.isPending && <PageLoading label="Searching your documents…" />}

      {search.isError && !search.isPending && (
        <PageError
          title="Search failed"
          message={search.error instanceof Error ? search.error.message : undefined}
          onRetry={() => query.trim() && search.mutate(query.trim())}
        />
      )}

      {!search.isPending && !search.isError && result && (
        <section aria-live="polite" className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-sm font-bold">
                {result.count} result{result.count === 1 ? '' : 's'} for “{result.query}”
              </h2>
              {strategy && (
                <span className={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-semibold', strategy.className)}>
                  {strategy.label}
                </span>
              )}
              {result.model && (
                <span className="rounded-full border border-border/80 bg-muted/50 px-2.5 py-0.5 text-[11px] text-muted-foreground">
                  {result.model}
                </span>
              )}
            </div>
            <p className="text-[11px] text-muted-foreground">
              Similarity is a retrieval score, not a correctness judgement.
            </p>
          </div>

          {result.results.length === 0 ? (
            <PageEmpty
              icon={Search}
              title="No matching passages"
              description="Try a broader phrase, a different spelling, or remove the document scope. If the library has no embedded chunks yet, keyword search still runs."
            />
          ) : (
            <ul className="space-y-3">
              {result.results.map((hit) => (
                <SearchResultCard
                  key={hit.chunkId}
                  hit={hit}
                  query={result.query}
                  onOpen={() => setPreview({
                    doc: { id: hit.documentId, originalFilename: hit.documentName },
                    page: hit.pageNumber ?? 1,
                  })}
                />
              ))}
            </ul>
          )}
        </section>
      )}

      {!search.isPending && !search.isError && !result && (
        <PageEmpty
          icon={Search}
          title="Start with a question or a phrase"
          description="Search runs over the extracted text of your processed PDFs. Results link back to the exact page so you can verify the source yourself."
        />
      )}

      {preview && (
        <PdfPreviewModal doc={preview.doc} initialPage={preview.page} onClose={() => setPreview(null)} />
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function SearchResultCard({ hit, query, onOpen }: { hit: SearchHit; query: string; onOpen: () => void }) {
  return (
    <li className="rounded-2xl border border-border/80 bg-card/70 p-5 shadow-sm transition hover:border-primary/35 hover:bg-accent/30">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center gap-1.5 text-sm font-semibold">
              <FileText className="h-4 w-4 text-indigo-300" aria-hidden="true" />
              {hit.documentName}
            </span>
            {hit.pageNumber != null && (
              <span className="rounded-md bg-muted px-2 py-0.5 text-[11px] text-muted-foreground">
                page {hit.pageNumber}
              </span>
            )}
            {hit.keywordMatch && (
              <span className="rounded-full border border-emerald-400/25 bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold text-emerald-300">
                keyword
              </span>
            )}
            {hit.semanticMatch && (
              <span className="rounded-full border border-violet-400/25 bg-violet-500/10 px-2 py-0.5 text-[10px] font-semibold text-violet-300">
                semantic
              </span>
            )}
          </div>
          <p className="mt-3 text-sm leading-7 text-foreground/90">
            <Highlighted text={hit.excerpt || hit.content} query={query} />
          </p>
        </div>
        <button
          onClick={onOpen}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-border bg-background/70 px-3 py-2 text-xs font-semibold text-muted-foreground transition hover:bg-accent hover:text-foreground"
        >
          Open source
          <ArrowUpRight className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-4 border-t border-border/60 pt-3 text-[11px] text-muted-foreground">
        <span>relevance {hit.rank.toFixed(3)}</span>
        {hit.similarity != null && <span>cosine similarity {hit.similarity.toFixed(3)}</span>}
        {hit.chunkIndex != null && <span>chunk {hit.chunkIndex + 1}</span>}
      </div>
    </li>
  );
}

function Highlighted({ text, query }: { text: string; query: string }) {
  const parts = useMemo(() => {
    const terms = query
      .split(/\s+/)
      .map((term) => term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
      .filter((term) => term.length >= 2)
      .slice(0, 4);
    if (terms.length === 0) return [{ text, match: false }];
    const pattern = new RegExp(`(${terms.join('|')})`, 'gi');
    // String.split with one capture group alternates: text, match, text, …
    return text.split(pattern).map((part, index) => ({
      text: part,
      match: index % 2 === 1 && part.length > 0,
    }));
  }, [text, query]);

  return (
    <>
      {parts.map((part, index) =>
        part.match ? (
          <mark key={index} className="rounded bg-indigo-400/20 px-0.5 text-foreground">{part.text}</mark>
        ) : (
          <span key={index}>{part.text}</span>
        ))}
    </>
  );
}
