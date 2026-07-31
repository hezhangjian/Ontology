import React from 'react';

export type AnyProps = Record<string, any>;

export const cx = (...values: unknown[]) => values.filter(Boolean).join(' ');

export const simple = (tag: keyof React.JSX.IntrinsicElements, base: string) =>
  React.forwardRef<any, AnyProps>(({ children, className, ...props }, ref) =>
    React.createElement(tag, { ...props, className: cx(base, className), ref }, children));

export const chipColor = (color?: string) => {
  if (['error', 'red'].includes(color ?? '')) return 'danger';
  if (['green', 'success'].includes(color ?? '')) return 'success';
  if (['gold', 'orange', 'warning'].includes(color ?? '')) return 'warning';
  if (['blue', 'geekblue', 'processing'].includes(color ?? '')) return 'accent';
  return 'default';
};
