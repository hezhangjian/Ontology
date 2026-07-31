import { Typography as HeroTypography } from '@heroui/react';
import { cx } from './common';
import type { AnyProps } from './common';

const toneClass = (type: unknown) => {
  if (type === 'danger' || type === 'error') return 'text-danger';
  if (type === 'secondary') return 'text-secondary';
  if (type === 'success') return 'text-success';
  if (type === 'warning') return 'text-warning';
  return undefined;
};

const Title = ({ children, level = 1, type, ...props }: AnyProps) => {
  const headingLevel = Math.min(6, Math.max(1, level)) as 1 | 2 | 3 | 4 | 5 | 6;
  return <HeroTypography.Heading
      {...props}
      className={cx('text-heading', toneClass(type), props.className)}
      level={headingLevel}
    >
      {children}
    </HeroTypography.Heading>;
};

const Paragraph = ({ children, type, ...props }: AnyProps) =>
  <HeroTypography.Paragraph {...props} className={cx('text-paragraph', toneClass(type), props.className)}>
    {children}
  </HeroTypography.Paragraph>;

const Text = ({ children, code, strong, type, ...props }: AnyProps) => code
  ? <HeroTypography.Code {...props}>{children}</HeroTypography.Code>
  : strong
    ? <strong {...props} className={cx(toneClass(type), props.className)}>{children}</strong>
    : <HeroTypography {...props} className={cx(toneClass(type), props.className)}>{children}</HeroTypography>;

export const Typography = { Paragraph, Text, Title };
