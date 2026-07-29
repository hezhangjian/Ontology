import React from 'react';
import { cx, simple } from './common';
import type { AnyProps } from './common';

export const Card = ({ children, extra, size, title, ...props }: AnyProps) =>
  <section {...props} className={cx('ui-card', size && `ui-card-${size}`, props.className)}>
    {(title || extra) && <header><strong>{title}</strong>{extra}</header>}
    <div className="ui-card-body">{children}</div>
  </section>;

export const Col = ({ children, span, ...props }: AnyProps) =>
  <div
    {...props}
    className={cx('ui-col', props.className)}
    style={{ ...props.style, flexBasis: span ? `${span / 24 * 100}%` : undefined }}
  >
    {children}
  </div>;

export const Divider = ({ children, ...props }: AnyProps) =>
  children
    ? <div {...props} className={cx('ui-divider', props.className)}><span>{children}</span></div>
    : <div {...props} className={cx('ui-divider', props.className)} role="separator" />;

export const Flex = ({ align, children, gap, justify, vertical, ...props }: AnyProps) =>
  <div
    {...props}
    className={cx('ui-flex', props.className)}
    style={{ ...props.style, alignItems: align, display: 'flex', flexDirection: vertical ? 'column' : 'row', gap, justifyContent: justify }}
  >
    {children}
  </div>;

export const Row = ({ children, gutter, ...props }: AnyProps) =>
  <div {...props} className={cx('ui-row', props.className)} style={{ ...props.style, gap: Array.isArray(gutter) ? gutter[0] : gutter }}>
    {children}
  </div>;

export const Space = ({ align, children, direction, justify, size = 8, wrap, ...props }: AnyProps) =>
  <div
    {...props}
    className={cx('ui-space', props.className)}
    style={{ ...props.style, alignItems: align ?? 'center', display: 'flex', flexDirection: direction === 'vertical' ? 'column' : 'row', flexWrap: wrap ? 'wrap' : undefined, gap: size, justifyContent: justify }}
  >
    {children}
  </div>;

const LayoutBase = simple('div', 'ui-layout');
const LayoutSider = React.forwardRef<HTMLElement, AnyProps>(
  ({ children, className, collapsed, collapsedWidth = 80, style, width = 200, ...props }, ref) =>
    <aside
      {...props}
      className={cx('ui-layout-sider', collapsed && 'is-collapsed', className)}
      ref={ref}
      style={{ ...style, width: collapsed ? collapsedWidth : width }}
    >
      {children}
    </aside>,
);

export const Layout = Object.assign(LayoutBase, {
  Content: simple('main', 'ui-layout-content'),
  Header: simple('header', 'ui-layout-header'),
  Sider: LayoutSider,
});
