import {
  Card as HeroCard,
  Separator,
} from '@heroui/react';
import React from 'react';
import { cx, simple } from './common';
import type { AnyProps } from './common';

export const Card = ({ children, extra, size, title, ...props }: AnyProps) =>
  <HeroCard {...props} className={cx('surface-card', props.className)} variant="secondary">
    {(title || extra) && <HeroCard.Header>
      {title && <HeroCard.Title>{title}</HeroCard.Title>}
      {extra}
    </HeroCard.Header>}
    <HeroCard.Content className={cx('surface-card-body', size === 'small' ? 'p-3' : 'p-4')}>{children}</HeroCard.Content>
  </HeroCard>;

export const Col = ({ children, span, ...props }: AnyProps) =>
  <div
    {...props}
    className={cx('grid-column min-w-0', props.className)}
    style={{ ...props.style, flexBasis: span ? `${span / 24 * 100}%` : undefined }}
  >
    {children}
  </div>;

export const Divider = ({ children, ...props }: AnyProps) =>
  children
    ? <div {...props} className={cx('section-divider my-4 flex items-center gap-3 text-xs font-semibold text-gray-500', props.className)}>
      <Separator className="flex-1" />
      <span>{children}</span>
      <Separator className="flex-1" />
    </div>
    : <Separator {...props} className={cx('section-divider my-4', props.className)} />;

export const Flex = ({ align, children, gap, justify, vertical, ...props }: AnyProps) =>
  <div
    {...props}
    className={cx('flex-layout flex', vertical && 'flex-col', props.className)}
    style={{ ...props.style, alignItems: align, display: 'flex', flexDirection: vertical ? 'column' : 'row', gap, justifyContent: justify }}
  >
    {children}
  </div>;

export const Row = ({ children, gutter, ...props }: AnyProps) =>
  <div {...props} className={cx('grid-row flex flex-wrap', props.className)} style={{ ...props.style, gap: Array.isArray(gutter) ? gutter[0] : gutter }}>
    {children}
  </div>;

export const Space = ({ align, children, direction, justify, size = 8, wrap, ...props }: AnyProps) =>
  <div
    {...props}
    className={cx('cluster flex', direction === 'vertical' && 'flex-col', wrap && 'flex-wrap', props.className)}
    style={{ ...props.style, alignItems: align ?? 'center', display: 'flex', flexDirection: direction === 'vertical' ? 'column' : 'row', flexWrap: wrap ? 'wrap' : undefined, gap: size, justifyContent: justify }}
  >
    {children}
  </div>;

const LayoutBase = simple('div', 'app-layout flex min-w-0 flex-1');
const LayoutSider = React.forwardRef<HTMLElement, AnyProps>(
  ({ children, className, collapsed, collapsedWidth = 80, style, width = 200, ...props }, ref) =>
    <aside
      {...props}
      className={cx('app-layout-sider shrink-0 transition-[width] duration-150', collapsed && 'is-collapsed', className)}
      ref={ref}
      style={{ ...style, width: collapsed ? collapsedWidth : width }}
    >
      {children}
    </aside>,
);

export const Layout = Object.assign(LayoutBase, {
  Content: simple('main', 'app-layout-content min-w-0 flex-1'),
  Header: simple('header', 'app-layout-header flex items-center'),
  Sider: LayoutSider,
});
