import React from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

const Title = ({ children, level = 1, type, ...props }: AnyProps) =>
  React.createElement(`h${Math.min(6, Math.max(1, level))}`, { ...props, className: cx('ui-title', type && `ui-text-${type}`, props.className) }, children);

const Paragraph = ({ children, type, ...props }: AnyProps) =>
  <p {...props} className={cx('ui-paragraph', type && `ui-text-${type}`, props.className)}>{children}</p>;

const Text = ({ children, code, strong, type, ...props }: AnyProps) => code
  ? <code {...props}>{children}</code>
  : strong
    ? <strong {...props} className={cx(type && `ui-text-${type}`, props.className)}>{children}</strong>
    : <span {...props} className={cx(type && `ui-text-${type}`, props.className)}>{children}</span>;

export const Typography = { Paragraph, Text, Title };
