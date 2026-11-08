import { describe, expect, it } from 'vitest';
import { layoutGraph } from './graphLayout';

describe('layoutGraph', () => {
  it('returns no positions for an empty graph', () => {
    expect(layoutGraph([], []).size).toBe(0);
  });

  it('places every node inside the canvas bounds', () => {
    const nodes = [
      { id: 'a', mentionCount: 1 },
      { id: 'b', mentionCount: 4 },
      { id: 'c', mentionCount: 9 },
    ];
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ];

    const layout = layoutGraph(nodes, edges, { width: 600, height: 400, iterations: 60 });

    expect(layout.size).toBe(3);
    for (const position of layout.values()) {
      expect(position.x).toBeGreaterThanOrEqual(0);
      expect(position.x).toBeLessThanOrEqual(600);
      expect(position.y).toBeGreaterThanOrEqual(0);
      expect(position.y).toBeLessThanOrEqual(400);
      expect(position.radius).toBeGreaterThan(0);
    }
  });

  it('is deterministic for the same input', () => {
    const nodes = [
      { id: 'node-1', mentionCount: 2 },
      { id: 'node-2', mentionCount: 5 },
      { id: 'node-3', mentionCount: 1 },
    ];
    const edges = [{ source: 'node-1', target: 'node-2' }];

    const first = layoutGraph(nodes, edges, { iterations: 40 });
    const second = layoutGraph(nodes, edges, { iterations: 40 });

    for (const [id, position] of first) {
      expect(second.get(id)).toEqual(position);
    }
  });

  it('gives more-mentioned entities a larger radius', () => {
    const layout = layoutGraph(
      [
        { id: 'rare', mentionCount: 1 },
        { id: 'frequent', mentionCount: 64 },
      ],
      [],
      { iterations: 20 },
    );

    expect(layout.get('frequent')!.radius).toBeGreaterThan(layout.get('rare')!.radius);
  });

  it('ignores edges whose endpoints are not in the node set', () => {
    const layout = layoutGraph(
      [{ id: 'only', mentionCount: 1 }],
      [{ source: 'only', target: 'missing' }],
      { iterations: 20 },
    );

    expect(layout.size).toBe(1);
    expect(layout.has('only')).toBe(true);
  });
});
