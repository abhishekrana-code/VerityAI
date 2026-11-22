import { AlertCircle, Check, Loader2 } from 'lucide-react';
import type { Document } from '@/lib/api';
import { PROCESSING_STEPS, processingStepIndex } from '@/lib/processing';
import { cn } from '@/lib/utils';

interface ProcessingStepperProps {
  status: Document['status'];
  stage?: string | null;
  compact?: boolean;
  className?: string;
}

export function ProcessingStepper({ status, stage, compact, className }: ProcessingStepperProps) {
  const activeIndex = processingStepIndex(status, stage);
  const failed = status === 'FAILED';

  if (compact) {
    return (
      <div
        className={cn('flex items-center gap-1.5', className)}
        role="list"
        aria-label={`Processing: ${failed ? 'failed' : PROCESSING_STEPS[activeIndex]}`}
      >
        {PROCESSING_STEPS.map((step, index) => (
          <span
            key={step}
            role="listitem"
            title={step}
            className={cn(
              'h-1.5 w-4 rounded-full',
              index < activeIndex && 'bg-green-500',
              index === activeIndex && (failed ? 'bg-destructive' : 'bg-primary'),
              index > activeIndex && 'bg-muted',
            )}
          />
        ))}
        <span
          className={cn(
            'ml-1 text-xs font-medium',
            failed ? 'text-destructive' : 'text-muted-foreground',
          )}
        >
          {failed ? 'Failed' : PROCESSING_STEPS[activeIndex]}
        </span>
      </div>
    );
  }

  return (
    <ol className={cn('flex flex-wrap items-center gap-x-2 gap-y-1', className)} aria-label="Processing progress">
      {PROCESSING_STEPS.map((step, index) => {
        const done = index < activeIndex || (index === activeIndex && !failed && status === 'READY');
        const current = index === activeIndex && !failed && status !== 'READY';
        const failedHere = failed && index === activeIndex;
        return (
          <li key={step} className="flex items-center gap-2" aria-current={current ? 'step' : undefined}>
            {index > 0 && <span className="h-px w-4 bg-border" aria-hidden="true" />}
            <span
              className={cn(
                'flex h-5 w-5 items-center justify-center rounded-full border text-[10px]',
                done && 'border-green-500 bg-green-500/15 text-green-600 dark:text-green-400',
                current && 'border-primary text-primary',
                failedHere && 'border-destructive bg-destructive/15 text-destructive',
                !done && !current && !failedHere && 'border-border text-muted-foreground',
              )}
            >
              {done ? (
                <Check className="h-3 w-3" />
              ) : failedHere ? (
                <AlertCircle className="h-3 w-3" />
              ) : current ? (
                <Loader2 className="h-3 w-3 animate-spin" />
              ) : (
                index + 1
              )}
            </span>
            <span
              className={cn(
                'text-xs',
                done || current || failedHere ? 'text-foreground' : 'text-muted-foreground',
              )}
            >
              {step}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
