import { describe, expect, it } from 'vitest';
import { layoutGraph } from './graphLayout';

const WIDTH = 900;
const HEIGHT = 560;

describe('layoutGraph', () => {
  it('returns an empty layout for an empty graph', () => {
    const layout = layoutGraph([], []);
    expect(layout.nodes.size).toBe(0);
    expect(layout.islands).toEqual([]);
  });

  it('places every node inside the canvas bounds', () => {
    const nodes = [
      { id: 'a', mentionCount: 1, type: 'PERSON' },
      { id: 'b', mentionCount: 4, type: 'ORGANIZATION' },
      { id: 'c', mentionCount: 9, type: 'LOCATION' },
    ];
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ];

    const layout = layoutGraph(nodes, edges, { width: 600, height: 400 });

    expect(layout.nodes.size).toBe(3);
    for (const position of layout.nodes.values()) {
      expect(position.x).toBeGreaterThanOrEqual(0);
      expect(position.x).toBeLessThanOrEqual(600);
      expect(position.y).toBeGreaterThanOrEqual(0);
      expect(position.y).toBeLessThanOrEqual(400);
      expect(position.radius).toBeGreaterThan(0);
    }
  });

  it('is deterministic for the same input', () => {
    const nodes = [
      { id: 'node-1', mentionCount: 2, type: 'PERSON' },
      { id: 'node-2', mentionCount: 5, type: 'PERSON' },
      { id: 'node-3', mentionCount: 1, type: 'CONCEPT' },
    ];
    const edges = [{ source: 'node-1', target: 'node-2' }];

    const first = layoutGraph(nodes, edges);
    const second = layoutGraph(nodes, edges);

    expect([...first.nodes.entries()]).toEqual([...second.nodes.entries()]);
    expect(first.islands).toEqual(second.islands);
  });

  it('gives more-mentioned entities a larger radius', () => {
    const layout = layoutGraph(
      [
        { id: 'rare', mentionCount: 1, type: 'PERSON' },
        { id: 'frequent', mentionCount: 64, type: 'PERSON' },
      ],
      [],
    );

    expect(layout.nodes.get('frequent')!.radius).toBeGreaterThan(layout.nodes.get('rare')!.radius);
  });

  it('groups entities into one island per type and keeps each node inside it', () => {
    const nodes = [
      ...['p1', 'p2', 'p3', 'p4', 'p5', 'p6'].map((id) => ({ id, mentionCount: 3, type: 'PERSON' })),
      ...['l1', 'l2', 'l3', 'l4'].map((id) => ({ id, mentionCount: 2, type: 'LOCATION' })),
      { id: 'x1', mentionCount: 1, type: 'CONCEPT' },
    ];

    const layout = layoutGraph(nodes, [], { width: WIDTH, height: HEIGHT });

    expect(layout.islands.map((island) => [island.type, island.count])).toEqual([
      ['PERSON', 6],
      ['LOCATION', 4],
      ['CONCEPT', 1],
    ]);

    for (const node of nodes) {
      const point = layout.nodes.get(node.id)!;
      const island = layout.islands.find((entry) => entry.type === node.type)!;
      expect(point.x).toBeGreaterThanOrEqual(island.x);
      expect(point.x).toBeLessThanOrEqual(island.x + island.width);
      expect(point.y).toBeGreaterThanOrEqual(island.y);
      expect(point.y).toBeLessThanOrEqual(island.y + island.height);
    }
  });

  it('puts the most-connected entity of an island at its centre', () => {
    const nodes = [
      { id: 'hub', mentionCount: 10, type: 'PERSON' },
      { id: 'leaf-1', mentionCount: 1, type: 'PERSON' },
      { id: 'leaf-2', mentionCount: 1, type: 'PERSON' },
      { id: 'leaf-3', mentionCount: 1, type: 'PERSON' },
    ];
    const edges = nodes
      .filter((node) => node.id !== 'hub')
      .map((node) => ({ source: 'hub', target: node.id }));

    const layout = layoutGraph(nodes, edges);
    const island = layout.islands[0];
    const centre = { x: island.x + island.width / 2, y: island.y + island.height / 2 };
    const distance = (id: string) => {
      const point = layout.nodes.get(id)!;
      return Math.hypot(point.x - centre.x, point.y - centre.y);
    };

    expect(distance('hub')).toBeLessThan(distance('leaf-1'));
    expect(distance('hub')).toBeLessThan(distance('leaf-2'));
    expect(distance('hub')).toBeLessThan(distance('leaf-3'));
  });

  it('ignores edges whose endpoints are not in the node set', () => {
    const layout = layoutGraph(
      [{ id: 'only', mentionCount: 1, type: 'PERSON' }],
      [{ source: 'only', target: 'missing' }],
    );

    expect(layout.nodes.size).toBe(1);
    expect(layout.nodes.has('only')).toBe(true);
  });
});
