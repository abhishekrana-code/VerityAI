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
 * The default view: names that say what a document is about. A bare year or a
 * two-letter code appears in almost every document and connects nothing.
 */
const SUBJECT_TYPES = 'PERSON,ORGANIZATION,LOCATION,PRODUCT,EVENT';

const TYPE_OPTIONS = [
  // Covers SUBJECT_TYPES exactly: a product or an event is a subject too.
  { value: 'subjects', label: 'People, places, products & events' },
  { value: 'all', label: 'All entity types' },
  { value: 'PERSON', label: 'People' },
  { value: 'ORGANIZATION', label: 'Organizations' },
  { value: 'LOCATION', label: 'Locations' },
  { value: 'PRODUCT', label: 'Products' },
  { value: 'EVENT', label: 'Events' },
  { value: 'METRIC', label: 'Numbers' },
  // Bare years are hidden as noise, so only explicit dates surface here.
  { value: 'DATE', label: 'Dates (bare years hidden)' },
  { value: 'CONCEPT', label: 'Topics' },
  { value: 'ACRONYM', label: 'Acronyms' },
] as const;

/**
 * Library-wide entity index: every name mentioned anywhere in the library,
 * merged across documents, with the documents that mention it. This replaces
 * the old canvas, where the same entity appeared once per document and no edge
 * crossed a document boundary.
 */
export function LibraryEntitiesTab() {
  const [typeFilter, setTypeFilter] = useState<string>('subjects');
  const [query, setQuery] = useState('');

  const entitiesQuery = useQuery({
    queryKey: ['knowledge-graph', 'library-entities', typeFilter, query],
    queryFn: () => {
      const params = new URLSearchParams({ limit: '100' });
      if (typeFilter === 'subjects') params.set('types', SUBJECT_TYPES);
      else if (typeFilter !== 'all') params.set('type', typeFilter);
      if (query) params.set('q', query);
      return api.get<KnowledgeEntityIndexResponse>(`/knowledge-graph/entities?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
  });

  const connectionsQuery = useQuery({
    queryKey: ['knowledge-graph', 'library-connections'],
    queryFn: () => api.get<KnowledgeConnectionsResponse>('/knowledge-graph/connections?limit=12'),
  });

  const entities = entitiesQuery.data?.entities ?? [];
  const connections = connectionsQuery.data?.connections ?? [];

  return (
    <div className="space-y-5">
      <section className="rounded-xl border p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3 className="font-semibold">What your library mentions</h3>
            <p className="mt-1 max-w-xl text-xs leading-5 text-muted-foreground">
              One row per name, with every document that mentions it. Years, two-letter codes and
              table labels are ignored.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={typeFilter}
              onChange={(event) => setTypeFilter(event.target.value)}
              aria-label="Filter entities by type"
              className="h-9 rounded-lg border border-border/70 bg-background/70 px-2 text-xs outline-none focus:border-ring focus:ring-2 focus:ring-ring/25"
            >
              {TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Find an entity…"
                aria-label="Find an entity in the library"
                className="h-9 w-48 rounded-lg border border-border/70 bg-background/70 pl-8 pr-7 text-xs outline-none focus:border-ring focus:ring-2 focus:ring-ring/25"
              />
              {query && (
                <button
                  type="button"
                  onClick={() => setQuery('')}
                  className="absolute right-1 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                  aria-label="Clear entity search"
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
          </div>
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
          <div className="mt-4">
            <p className="text-sm text-muted-foreground">
              {query
                ? `No entity matches “${query}”.`
                : typeFilter === 'subjects'
                  ? 'No people, places, products or events found yet. They are extracted automatically once processing finishes.'
                  : 'No entities found yet. They are extracted automatically once processing finishes.'}
            </p>
            {!query && typeFilter === 'subjects' && (
              <button
                type="button"
                onClick={() => setTypeFilter('all')}
                className="mt-3 rounded-lg border border-border px-3 py-1.5 text-xs font-medium hover:bg-accent transition-colors"
              >
                Show all entity types
              </button>
            )}
          </div>
        ) : (
          <ul className="mt-4 divide-y divide-border/50">
            {entities.map((entity) => (
              <li key={entity.normalizedName} className="py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="truncate text-sm font-medium">{entity.name}</span>
                  <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
                    {TYPE_LABELS[entity.type] ?? entity.type}
                  </span>
                  <span className="text-[11px] text-muted-foreground">
                    {pluralize(entity.mentionCount, 'mention')} in{' '}
                    {pluralize(entity.documentCount, 'document')}
                  </span>
                </div>
                {entity.evidenceExcerpt && (
                  <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                    “{entity.evidenceExcerpt}”
                  </p>
                )}
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {entity.documents.map((document) => (
                    <Link
                      key={document.id}
                      to={`/dashboard/documents/${document.id}`}
                      title={
                        document.firstPage != null
                          ? `${document.name} — first on page ${document.firstPage}`
                          : document.name
                      }
                      className="inline-flex max-w-[260px] items-center gap-1 rounded-full border border-border/70 bg-background/60 px-2 py-0.5 text-[11px] text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                    >
                      <FileText className="h-3 w-3 shrink-0" aria-hidden="true" />
                      <span className="truncate">{document.name}</span>
                      <span className="shrink-0">· {document.mentionCount}</span>
                    </Link>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        )}
        {entitiesQuery.data && entities.length > 0 && (
          <p className="mt-2 text-[11px] text-muted-foreground">
            {entitiesQuery.data.returnedEntities < entitiesQuery.data.totalEntities
              ? `Showing the ${entitiesQuery.data.returnedEntities} most-mentioned of ${pluralize(entitiesQuery.data.totalEntities, 'name')}.`
              : `${pluralize(entitiesQuery.data.totalEntities, 'name')} in this view.`}
          </p>
        )}
      </section>

      <section className="rounded-xl border p-5">
        <h3 className="font-semibold">Documents that share entities</h3>
        <p className="mt-1 max-w-xl text-xs leading-5 text-muted-foreground">
          Documents connected by the names they both mention, strongest first. Weight counts how
          specific the shared names are, so a rare name can outrank a larger raw count; years and
          two-letter codes are ignored.
        </p>
        {connectionsQuery.isLoading ? (
          <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Comparing documents…
          </p>
        ) : connections.length === 0 ? (
          <p className="mt-3 text-sm text-muted-foreground">
            No two documents mention the same entities yet.
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {connections.map((connection) => (
              <li
                key={`${connection.documentAId}-${connection.documentBId}`}
                className="flex items-start justify-between gap-3 rounded-lg border border-border/70 bg-background/60 px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5 text-sm font-medium">
                    <Link
                      to={`/dashboard/documents/${connection.documentAId}`}
                      className="inline-flex max-w-[220px] items-center gap-1 underline-offset-2 hover:underline"
                      title={connection.documentAName}
                    >
                      <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                      <span className="truncate">{connection.documentAName}</span>
                    </Link>
                    <span className="text-muted-foreground">+</span>
                    <Link
                      to={`/dashboard/documents/${connection.documentBId}`}
                      className="inline-flex max-w-[220px] items-center gap-1 underline-offset-2 hover:underline"
                      title={connection.documentBName}
                    >
                      <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                      <span className="truncate">{connection.documentBName}</span>
                      <ArrowUpRight className="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
                    </Link>
                  </div>
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
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
