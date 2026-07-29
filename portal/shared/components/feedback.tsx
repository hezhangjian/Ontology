import {
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
  return <div {...props} className={cx('ui-alert', `ui-alert-${type}`, props.className)} role={type === 'error' ? 'alert' : 'status'}>
    {showIcon && <span className="ui-alert-icon">{icon ?? alertIcon(type)}</span>}
    <div className="ui-alert-content">
      {title && <strong>{title}</strong>}
      {description && <div>{description}</div>}
    </div>
    {action}
    {closable && <button
      aria-label="关闭提示"
      className="ui-alert-close"
      onClick={(event) => {
        setVisible(false);
        onClose?.(event);
      }}
      type="button"
    >
      ×
    </button>}
  </div>;
};

export const Empty = Object.assign(({ children, description = '暂无数据', image, ...props }: AnyProps) =>
  <div {...props} className={cx('ui-empty', props.className)}>
    {image && <div className="ui-empty-image">{image}</div>}
    <div className="ui-empty-description">{description}</div>
    {children && <div className="ui-empty-actions">{children}</div>}
  </div>, { PRESENTED_IMAGE_SIMPLE: <span className="ui-empty-placeholder" /> });

export const Progress = ({ percent = 0, ...props }: AnyProps) =>
  <div {...props} aria-valuemax={100} aria-valuemin={0} aria-valuenow={percent} className={cx('ui-progress', props.className)} role="progressbar">
    <span style={{ width: `${Math.max(0, Math.min(100, percent))}%` }} />
  </div>;

export const Result = ({ extra, status, subTitle, title, ...props }: AnyProps) =>
  <div {...props} className={cx('ui-result', props.className)}>
    <strong>{title ?? status}</strong>
    {subTitle && <p>{subTitle}</p>}
    {extra}
  </div>;

export const Skeleton = ({ active, ...props }: AnyProps) =>
  <span {...props} aria-hidden className={cx('ui-skeleton', active && 'is-active', props.className)} />;

export const Spin = ({ tip, ...props }: AnyProps) =>
  <div className={cx('ui-spin', props.className)} role="status"><span aria-hidden className="ui-spinner" />{tip}</div>;

export const Statistic = ({ prefix, suffix, title, value, ...props }: AnyProps) =>
  <div {...props} className={cx('ui-statistic', props.className)}>
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
