'use client';

import { Link } from 'react-router-dom';
import { Compass } from 'lucide-react';
import { PageEmpty } from '@/components/ui/PageStates';

/**
 * Rendered for unknown paths inside /dashboard/* so a stale or mistyped link
 * lands on an explanation instead of an empty content area.
 */
export function DashboardNotFoundPage() {
  return (
    <PageEmpty
      icon={Compass}
      title="Page not found"
      description="That page does not exist. It may have moved, or the link you followed is out of date."
      action={
        <Link
          to="/dashboard"
          className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground transition hover:bg-primary/90"
        >
          Back to dashboard
        </Link>
      }
    />
  );
}
