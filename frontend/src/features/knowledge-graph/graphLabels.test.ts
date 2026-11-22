import { describe, expect, it } from 'vitest';
import { visibleLabels } from './graphLabels';

const positions = (entries: Array<[string, { x: number; y: number; radius: number }]>) =>
  new Map(entries);

const node = (id: string, mentionCount: number) => ({ id, name: id, mentionCount });

describe('visibleLabels', () => {
  it('skips labels that would overlap an already placed one', () => {
    const labels = visibleLabels(
      [node('hub', 10), node('nearby', 9)],
      positions([
        ['hub', { x: 100, y: 100, radius: 12 }],
        ['nearby', { x: 104, y: 102, radius: 10 }],
      ]),
    );

    expect(labels.map((label) => label.id)).toEqual(['hub']);
  });

  it('keeps labels for entities that are far enough apart', () => {
    const labels = visibleLabels(
      [node('left', 10), node('right', 9)],
      positions([
        ['left', { x: 100, y: 100, radius: 8 }],
        ['right', { x: 320, y: 100, radius: 8 }],
      ]),
    );

    expect(labels.map((label) => label.id)).toEqual(['left', 'right']);
  });

  it('always labels the focused entity even when its neighbours crowd it', () => {
    const labels = visibleLabels(
      [node('focused', 1), node('crowd-a', 30), node('crowd-b', 29)],
      positions([
        ['focused', { x: 200, y: 200, radius: 9 }],
        ['crowd-a', { x: 202, y: 201, radius: 9 }],
        ['crowd-b', { x: 198, y: 202, radius: 9 }],
      ]),
      { always: new Set(['focused']) },
    );

    expect(labels[0].id).toBe('focused');
    expect(labels).toHaveLength(1);
  });

  it('respects the label budget and truncates long names', () => {
    const longName = { id: 'long', name: 'A very long entity name that needs cutting', mentionCount: 5 };
    const labels = visibleLabels(
      [longName, node('second', 1)],
      positions([
        ['long', { x: 50, y: 50, radius: 6 }],
        ['second', { x: 400, y: 300, radius: 6 }],
      ]),
      { max: 1, truncateAt: 12 },
    );

    expect(labels).toHaveLength(1);
    expect(labels[0].text).toBe('A very long…');
  });

  it('ignores nodes without a position', () => {
    const labels = visibleLabels([node('missing', 3)], positions([]));
    expect(labels).toEqual([]);
  });
});
