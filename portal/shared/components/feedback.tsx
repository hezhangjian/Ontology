import {
  Alert as HeroAlert,
  EmptyState,
  ProgressBar,
  Skeleton as HeroSkeleton,
  Spinner,
  toast,
} from '@heroui/react';
import { AlertCircle, CheckCircle2, Info, TriangleAlert } from 'lucide-react';
import React, { useState } from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

const alertIcon = (type: string) => type === 'error'
  ? <AlertCircle />
  : type === 'success'
    ? <CheckCircle2 />
    : type === 'warning'
      ? <TriangleAlert />
      : <Info />;

export const Alert = ({ action, closable, description, icon, message: title, onClose, showIcon, type = 'info', ...props }: AnyProps) => {
  const [visible, setVisible] = useState(true);
  if (!visible) return null;
  return <HeroAlert
    {...props}
    className={cx('status-alert my-3 shadow-none', props.className)}
    status={type === 'error' ? 'danger' : type === 'info' ? 'accent' : type}
  >
    {showIcon && <HeroAlert.Indicator className="status-alert-icon">{icon ?? alertIcon(type)}</HeroAlert.Indicator>}
    <HeroAlert.Content className="status-alert-content">
      {title && <HeroAlert.Title>{title}</HeroAlert.Title>}
      {description && <HeroAlert.Description>{description}</HeroAlert.Description>}
    </HeroAlert.Content>
    {action}
    {closable && <button
      aria-label="关闭提示"
      className="status-alert-close rounded-sm p-1 opacity-70 hover:opacity-100"
      onClick={(event) => {
        setVisible(false);
        onClose?.(event);
      }}
      type="button"
    >
      ×
    </button>}
  </HeroAlert>;
};

export const Empty = Object.assign(({ children, description = '暂无数据', image, ...props }: AnyProps) =>
  <EmptyState {...props} className={cx('empty-state flex flex-col items-center justify-center gap-2.5 text-gray-500', props.className)}>
    {image && <div className="empty-state-image grid size-11 place-items-center rounded-md border border-blue-100 bg-blue-50 text-xl text-blue-600">{image}</div>}
    <div className="empty-state-description leading-relaxed">{description}</div>
    {children && <div className="empty-state-actions mt-1">{children}</div>}
  </EmptyState>, { PRESENTED_IMAGE_SIMPLE: <span className="empty-state-placeholder block h-3.5 w-4.5 rounded-sm border-2 border-current border-t opacity-70" /> });

export const Progress = ({ percent = 0, ...props }: AnyProps) =>
  <ProgressBar {...props} aria-label={props['aria-label'] ?? '进度'} className={cx('progress-indicator', props.className)} value={percent}>
    <ProgressBar.Track>
      <ProgressBar.Fill />
    </ProgressBar.Track>
  </ProgressBar>;

export const Result = ({ extra, status, subTitle, title, ...props }: AnyProps) =>
  <EmptyState {...props} className={cx('result-state', props.className)}>
    <strong>{title ?? status}</strong>
    {subTitle && <p>{subTitle}</p>}
    {extra}
  </EmptyState>;

export const Skeleton = ({ active, ...props }: AnyProps) =>
  <HeroSkeleton {...props} aria-hidden className={cx('loading-skeleton', props.className)} />;

export const Spin = ({ tip, ...props }: AnyProps) =>
  <div className={cx('loading-indicator inline-flex items-center gap-2', props.className)} role="status">
    <Spinner aria-label={tip ?? '加载中'} size={props.size === 'large' ? 'lg' : props.size === 'small' ? 'sm' : 'md'} />
    {tip}
  </div>;

export const Statistic = ({ prefix, suffix, title, value, ...props }: AnyProps) =>
  <div {...props} className={cx('statistic', props.className)}>
    <span>{title}</span>
    <strong>{prefix}{value}{suffix}</strong>
  </div>;

const messageApi = {
  error: (value: React.ReactNode) => toast.danger(value),
  info: (value: React.ReactNode) => toast.info(value),
  success: (value: React.ReactNode) => toast.success(value),
  warning: (value: React.ReactNode) => toast.warning(value),
};

export const message = Object.assign(messageApi, { useMessage: () => [messageApi, null] as const });
