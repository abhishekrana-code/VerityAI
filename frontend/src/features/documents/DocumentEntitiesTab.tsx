'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  api,
  type KnowledgeConnectionsResponse,
  type KnowledgeEntityIndexResponse,
  type KnowledgeEntityType,
} from '@/lib/api';
import { pluralize } from '@/lib/utils';
import { ArrowUpRight, FileText, Loader2, Search, X } from 'lucide-react';

const TYPE_LABELS: Record<KnowledgeEntityType, string> = {
  PERSON: 'Person',
  ORGANIZATION: 'Organization',
  LOCATION: 'Location',
  PRODUCT: 'Product',
  EVENT: 'Event',
  DATE: 'Date',
  METRIC: 'Number',
  ACRONYM: 'Acronym',
  CONCEPT: 'Topic',
  OTHER: 'Other',
};

/**
 * The Entities tab replaces the graph canvas with a list you can read and
 * click: what this document mentions, where it first appears, and which other
 * documents mention the same names.
 */
export function DocumentEntitiesTab({
  documentId,
  onOpenPage,
}: {
  documentId: string;
  onOpenPage: (page: number) => void;
}) {
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');

  const entitiesQuery = useQuery({
    queryKey: ['knowledge-graph', 'document-entities', documentId, query],
    queryFn: () => {
      const params = new URLSearchParams({ documentId, limit: '60' });
      if (query) params.set('q', query);
      return api.get<KnowledgeEntityIndexResponse>(`/knowledge-graph/entities?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
  });

  const connectionsQuery = useQuery({
    queryKey: ['knowledge-graph', 'document-connections', documentId],
    queryFn: () => api.get<KnowledgeConnectionsResponse>('/knowledge-graph/connections?limit=50'),
  });

  const entities = entitiesQuery.data?.entities ?? [];
  const related = (connectionsQuery.data?.connections ?? [])
    .filter((connection) => connection.documentAId === documentId || connection.documentBId === documentId)
    .slice(0, 4);

  return (
    <div className="space-y-5">
      <section className="rounded-xl border p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3 className="font-semibold">What this document mentions</h3>
            <p className="mt-1 max-w-xl text-xs leading-5 text-muted-foreground">
              Extracted from the text, most-mentioned first. Open a page to read the passage an
              entity came from.
            </p>
          </div>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setQuery(queryInput.trim());
            }}
            className="flex items-center gap-2"
          >
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <input
                type="search"
                value={queryInput}
                onChange={(event) => setQueryInput(event.target.value)}
                placeholder="Find an entity…"
                aria-label="Find an entity in this document"
                className="h-9 w-48 rounded-lg border border-border/70 bg-background/70 pl-8 pr-7 text-xs outline-none focus:border-ring focus:ring-2 focus:ring-ring/25"
              />
              {queryInput && (
                <button
                  type="button"
                  onClick={() => {
                    setQueryInput('');
                    setQuery('');
                  }}
                  className="absolute right-1 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                  aria-label="Clear entity search"
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
          </form>
        </div>

        {entitiesQuery.isLoading ? (
          <p className="mt-4 flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading entities…
          </p>
        ) : entitiesQuery.isError ? (
          <p className="mt-4 text-sm text-destructive">
            Could not load entities
            {entitiesQuery.error instanceof Error ? `: ${entitiesQuery.error.message}` : '.'}
          </p>
        ) : entities.length === 0 ? (
          <p className="mt-4 text-sm text-muted-foreground">
            {query
              ? `No entity matches “${query}”.`
              : 'No entities found yet. They are extracted automatically once processing finishes.'}
          </p>
        ) : (
          <ul className="mt-4 divide-y divide-border/50">
            {entities.map((entity) => {
              const page = entity.documents[0]?.firstPage ?? null;
              return (
                <li key={entity.normalizedName} className="flex items-start gap-3 py-2.5">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="truncate text-sm font-medium">{entity.name}</span>
                      <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
                        {TYPE_LABELS[entity.type] ?? entity.type}
                      </span>
                      <span className="text-[11px] text-muted-foreground">
                        {pluralize(entity.mentionCount, 'mention')}
                        {page != null ? ` · first on page ${page}` : ''}
                      </span>
                    </div>
                    {entity.evidenceExcerpt && (
                      <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                        “{entity.evidenceExcerpt}”
                      </p>
                    )}
                  </div>
                  {page != null && (
                    <button
                      type="button"
                      onClick={() => onOpenPage(page)}
                      className="shrink-0 rounded-md border border-border px-2 py-1 text-[11px] font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                    >
                      Open p.{page}
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {entitiesQuery.data?.truncated && (
          <p className="mt-2 text-[11px] text-muted-foreground">
            Showing the {entitiesQuery.data.returnedEntities} most-mentioned entities.
          </p>
        )}
      </section>

      <section className="rounded-xl border p-5">
        <h3 className="font-semibold">Documents that share entities</h3>
        <p className="mt-1 max-w-xl text-xs leading-5 text-muted-foreground">
          Other documents in your library that mention the same names, strongest first. Weight
          counts how specific the shared names are, so a rare name can outrank a larger raw count;
          years and two-letter codes are ignored.
        </p>
        {connectionsQuery.isLoading ? (
          <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Comparing documents…
          </p>
        ) : related.length === 0 ? (
          <p className="mt-3 text-sm text-muted-foreground">
            No other document mentions the same entities yet.
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {related.map((connection) => {
              const otherId =
                connection.documentAId === documentId ? connection.documentBId : connection.documentAId;
              const otherName =
                connection.documentAId === documentId ? connection.documentBName : connection.documentAName;
              return (
                <li
                  key={`${connection.documentAId}-${connection.documentBId}`}
                  className="flex items-start justify-between gap-3 rounded-lg border border-border/70 bg-background/60 px-3 py-2"
                >
                  <div className="min-w-0">
                    <Link
                      to={`/dashboard/documents/${otherId}`}
                      className="inline-flex max-w-full items-center gap-1 text-sm font-medium underline-offset-2 hover:underline"
                      title={otherName}
                    >
                      <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                      <span className="truncate">{otherName}</span>
                      <ArrowUpRight className="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
                    </Link>
                    {connection.sampleEntities.length > 0 && (
                      <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
                        {connection.sampleEntities.join(', ')}
                      </p>
                    )}
                  </div>
                  <span className="shrink-0 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-semibold text-primary">
                    {connection.sharedEntities} shared · weight {connection.weight.toFixed(2)}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
