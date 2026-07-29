import React, { useEffect, useState } from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

export type MenuProps = {
  items?: AnyProps[];
  onClick?: (info: { key: string }) => void;
};

export const Breadcrumb = ({ items = [], ...props }: AnyProps) =>
  <nav {...props} aria-label={props['aria-label'] ?? '导航栏'} className={cx('ui-breadcrumb', props.className)}>
    <ol>
      {items.map((item: AnyProps, index: number) =>
        <li aria-current={index === items.length - 1 ? 'page' : undefined} key={item.key ?? index}>
          {item.title}
        </li>)}
    </ol>
  </nav>;

export const Menu = ({ items = [], onClick, selectedKeys = [], ...props }: AnyProps) =>
  <nav {...props} className={cx('ui-menu', props.className)}>
    <MenuItems items={items} onClick={onClick} selectedKeys={selectedKeys} />
  </nav>;

function MenuItems({ items, onClick, selectedKeys }: { items: AnyProps[]; onClick?: AnyProps['onClick']; selectedKeys: string[] }) {
  return <ul>
    {items.filter(Boolean).map((item, index) =>
      item.type === 'divider'
        ? <li className="ui-menu-divider" key={index} />
        : <MenuItem item={item} key={item.key ?? index} onClick={onClick} selectedKeys={selectedKeys} />)}
  </ul>;
}

function MenuItem({ item, onClick, selectedKeys }: { item: AnyProps; onClick?: AnyProps['onClick']; selectedKeys: string[] }) {
  const hasChildren = item.type !== 'group' && Boolean(item.children?.length);
  const containsSelectedChild = item.children?.some((child: AnyProps) => selectedKeys.includes(String(child.key)));
  const [expanded, setExpanded] = useState(Boolean(containsSelectedChild));
  useEffect(() => {
    if (containsSelectedChild) setExpanded(true);
  }, [containsSelectedChild]);

  if (item.type === 'group') return <li><strong>{item.label}</strong><MenuItems items={item.children ?? []} onClick={onClick} selectedKeys={selectedKeys} /></li>;

  return <li>
    <button
      aria-expanded={hasChildren ? expanded : undefined}
      className={cx(selectedKeys.includes(String(item.key)) && 'is-selected', hasChildren && 'has-submenu')}
      disabled={item.disabled}
      onClick={() => {
        if (hasChildren) {
          setExpanded((current) => !current);
          return;
        }
        (item.onClick ?? onClick)?.({ key: String(item.key) });
      }}
      title={typeof item.label === 'string' ? item.label : undefined}
      type="button"
    >
      {item.icon}<span className="menu-item-label">{item.label}</span>
    </button>
    {hasChildren && expanded && <MenuItems items={item.children} onClick={onClick} selectedKeys={selectedKeys} />}
  </li>;
}

export const Tabs = ({ activeKey, defaultActiveKey, items = [], onChange, ...props }: AnyProps) => {
  const [local, setLocal] = useState(defaultActiveKey ?? items[0]?.key);
  const current = activeKey ?? local;
  const selected = items.find((item: AnyProps) => item.key === current) ?? items[0];
  return <div {...props} className={cx('ui-tabs', props.className)}>
    <div aria-label={props['aria-label'] ?? '页面选项卡'} className="ui-tab-list" role="tablist">
      {items.map((item: AnyProps) => <button
        aria-selected={item.key === current}
        className={cx(item.key === current && 'is-active')}
        key={item.key}
        onClick={() => {
          setLocal(item.key);
          onChange?.(item.key);
        }}
        role="tab"
        type="button"
      >
        {item.label}
      </button>)}
    </div>
    {selected && <div className="ui-tab-panel" role="tabpanel">{selected.children}</div>}
  </div>;
};
