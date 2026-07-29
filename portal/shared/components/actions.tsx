import React from 'react';
import { X } from 'lucide-react';
import { cx } from './common';
import type { AnyProps } from './common';

export const Avatar = ({ children, icon, size, src, ...props }: AnyProps) =>
  <span
    {...props}
    className={cx('ui-avatar', props.className)}
    style={{ ...props.style, height: size, width: size }}
  >
    {src ? <img alt="" src={src} /> : icon ?? children}
  </span>;

export const Badge = ({ children, count, dot, ...props }: AnyProps) =>
  <span {...props} className={cx('ui-badge', props.className)}>
    {children}
    {(dot || count) && <sup>{dot ? '' : count}</sup>}
  </span>;

export const Button = React.forwardRef<HTMLButtonElement, AnyProps>(
  ({ block, children, danger, htmlType, icon, loading, size, type, ...props }, ref) =>
    <button
      {...props}
      aria-busy={loading || undefined}
      className={cx('ui-button', `ui-button-${type ?? 'default'}`, size && `ui-button-${size}`, danger && 'is-danger', block && 'is-block', props.className)}
      disabled={props.disabled || loading}
      ref={ref}
      type={htmlType ?? 'button'}
    >
      {loading ? <span aria-hidden className="ui-spinner" /> : icon}
      {children}
    </button>,
);

export const Tag = ({ bordered, children, closable, color, icon, onClose, ...props }: AnyProps) =>
  <span
    {...props}
    className={cx('ui-tag', bordered === false && 'is-borderless', color && `ui-tag-${color}`, props.className)}
  >
    {icon}
    {children}
    {closable && <button
      aria-label="移除"
      className="ui-tag-close"
      onClick={(event) => {
        event.stopPropagation();
        onClose?.(event);
      }}
      type="button"
    >
      <X aria-hidden size={11} />
    </button>}
  </span>;
