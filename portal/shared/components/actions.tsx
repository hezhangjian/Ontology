import {
  Avatar as HeroAvatar,
  Badge as HeroBadge,
  Button as HeroButton,
  Chip,
  Spinner,
} from '@heroui/react';
import { X } from 'lucide-react';
import React from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

const componentSize = (size: unknown) =>
  size === 'small' || (typeof size === 'number' && size <= 28)
    ? 'sm'
    : size === 'large' || (typeof size === 'number' && size >= 40)
      ? 'lg'
      : 'md';

const semanticColor = (color: unknown) => {
  if (color === 'error' || color === 'red' || color === 'volcano') return 'danger';
  if (color === 'blue' || color === 'geekblue' || color === 'processing') return 'accent';
  if (color === 'gold' || color === 'orange' || color === 'warning') return 'warning';
  if (color === 'green' || color === 'success') return 'success';
  return 'default';
};

export const Avatar = ({ children, icon, size, src, ...props }: AnyProps) =>
  <HeroAvatar
    {...props}
    className={cx('user-avatar', props.className)}
    size={componentSize(size)}
    style={{ ...props.style, height: typeof size === 'number' ? size : undefined, width: typeof size === 'number' ? size : undefined }}
  >
    {src && <HeroAvatar.Image alt="" src={src} />}
    <HeroAvatar.Fallback>{icon ?? children}</HeroAvatar.Fallback>
  </HeroAvatar>;

export const Badge = ({ children, count, dot, ...props }: AnyProps) =>
  <HeroBadge {...props} className={cx('status-badge', props.className)} color="danger" size="sm">
    <HeroBadge.Anchor>{children}</HeroBadge.Anchor>
    {(dot || count) && <HeroBadge.Label aria-label={dot ? '有新内容' : String(count)}>{dot ? '' : count}</HeroBadge.Label>}
  </HeroBadge>;

export const Button = React.forwardRef<HTMLButtonElement, AnyProps>(
  ({ block, children, danger, htmlType, icon, loading, size, type, ...props }, ref) =>
    <HeroButton
      {...props}
      aria-busy={loading || undefined}
      className={cx(
        'action-button',
        type === 'link' && 'action-button--link',
        type === 'text' && 'action-button--text',
        props.className,
      )}
      fullWidth={Boolean(block)}
      isDisabled={Boolean(props.disabled || loading)}
      ref={ref}
      type={htmlType ?? 'button'}
      size={componentSize(size)}
      variant={danger ? 'danger' : type === 'primary' ? 'primary' : type === 'text' || type === 'link' ? 'tertiary' : 'outline'}
    >
      {loading ? <Spinner aria-hidden size="sm" /> : icon}
      {children}
    </HeroButton>,
);

export const Tag = ({ bordered, children, closable, color, icon, onClose, ...props }: AnyProps) =>
  <Chip
    {...props}
    className={cx('status-tag', props.className)}
    color={semanticColor(color)}
    size="sm"
    variant={bordered === false ? 'soft' : 'secondary'}
  >
    {icon}
    <Chip.Label>{children}</Chip.Label>
    {closable && <button
      aria-label="移除"
      className="status-tag-close rounded-xs p-0.5 opacity-70 transition-opacity hover:opacity-100"
      onClick={(event) => {
        event.stopPropagation();
        onClose?.(event);
      }}
      type="button"
    >
      <X aria-hidden size={11} />
    </button>}
  </Chip>;
