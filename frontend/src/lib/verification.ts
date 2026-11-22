import type { ClaimResult, ClaimVerificationResponse, SupportClass } from '@/lib/api';
import { pluralize } from '@/lib/utils';

/**
 * Plain-language labels for the API's support classes. These are AI
 * assessments of grounding in the cited excerpts, so they describe what the
 * document says — never a probability and never "verified".
 */
export const SUPPORT_LABELS: Record<SupportClass, string> = {
  SUPPORTED: 'Backed by the document',
  PARTIALLY_SUPPORTED: 'Partly backed',
  UNSUPPORTED: 'Not found in the document',
  CONFLICTING: 'Contradicts the document',
};

export const SUPPORT_STYLES: Record<SupportClass, string> = {
  SUPPORTED: 'border-emerald-400/25 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  PARTIALLY_SUPPORTED: 'border-amber-400/25 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  UNSUPPORTED: 'border-rose-400/25 bg-rose-500/10 text-rose-700 dark:text-rose-300',
  CONFLICTING: 'border-primary/25 bg-primary/10 text-primary',
};

/** Null means the claim was recorded but not assessed (usually a quick check). */
export function supportLabel(value: string | null | undefined): string {
  if (!value) return 'Not assessed yet';
  return SUPPORT_LABELS[value as SupportClass] ?? 'Not assessed yet';
}

/**
 * One line describing only what the deterministic layer checked: which claims
 * cite a source. It deliberately makes no claim about whether the cited text
 * supports them — that needs the model-assisted deep check.
 */
export function trustLine(claims: ClaimResult[]): string {
  if (claims.length === 0) {
    return 'No checkable claims in this answer.';
  }
  const cited = claims.filter((claim) => claim.citationIndexes.length > 0).length;
  const uncited = claims.length - cited;

  let line: string;
  if (cited === claims.length) {
    line = `All ${pluralize(claims.length, 'claim')} cite a source`;
  } else if (cited === 0) {
    line = `None of ${pluralize(claims.length, 'claim')} cite a source`;
  } else {
    line = `${cited} of ${claims.length} claims cite a source · ${
      uncited === 1 ? '1 has no citation' : `${uncited} have no citation`
    }`;
  }

  if (claims.some((claim) => claim.hasInvalidCitation)) {
    line += ' · a citation does not match the sources';
  }
  return line;
}

/** True when the stored result came from the free, deterministic layer only. */
export function isQuickCheck(verification?: ClaimVerificationResponse | null): boolean {
  return !!verification && verification.mode === 'QUICK';
}
