import { describe, expect, it } from 'vitest';
import type { KnowledgeGraphEdge } from '@/lib/api';
import { backboneLinks } from './graphEdges';

const edge = (id: string, source: string, target: string, weight: number): KnowledgeGraphEdge => ({
  id,
  source,
  target,
  weight,
  relationType: 'CO_OCCURS',
  documentId: 'doc-1',
  firstPage: 1,
  evidenceExcerpt: null,
});

describe('backboneLinks', () => {
  it('keeps only one link per entity — its strongest', () => {
    const edges = [
      edge('a-b', 'a', 'b', 5),
      edge('a-c', 'a', 'c', 3),
      edge('b-c', 'b', 'c', 2),
    ];

    const links = backboneLinks(edges);

    // "a" and "b" both keep a-b; "c" keeps its own strongest, a-c.
    expect(links.map((link) => link.id)).toEqual(['a-b', 'a-c']);
  });

  it('drops single-passage links that carry the least evidence', () => {
    const edges = [edge('weak', 'a', 'b', 1), edge('strong', 'c', 'd', 4)];

    expect(backboneLinks(edges).map((link) => link.id)).toEqual(['strong']);
  });

  it('never returns more than the cap, strongest first', () => {
    const edges = Array.from({ length: 10 }, (_, index) =>
      edge(`e-${index}`, `n-${index}`, `n-${index}-peer`, index + 2),
    );

    const links = backboneLinks(edges, { maxLinks: 3 });

    expect(links.map((link) => link.id)).toEqual(['e-9', 'e-8', 'e-7']);
  });

  it('honours a custom per-node budget and threshold', () => {
    const edges = [edge('a-b', 'a', 'b', 3), edge('a-c', 'a', 'c', 2), edge('d-e', 'd', 'e', 1)];

    expect(backboneLinks(edges, { perNode: 2, minWeight: 1 }).map((link) => link.id)).toEqual([
      'a-b',
      'a-c',
      'd-e',
    ]);
  });

  it('returns nothing for an empty graph', () => {
    expect(backboneLinks([])).toEqual([]);
  });
});
