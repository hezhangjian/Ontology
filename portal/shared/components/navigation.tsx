import {
  Breadcrumbs,
  Button as HeroButton,
  Disclosure,
  Separator,
  Tabs as HeroTabs,
} from '@heroui/react';
import React, { useEffect, useState } from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

export type MenuProps = {
  items?: AnyProps[];
  onClick?: (info: { key: string }) => void;
};

export const Breadcrumb = ({ items = [], ...props }: AnyProps) =>
  <Breadcrumbs {...props} aria-label={props['aria-label'] ?? '导航栏'} className={cx('app-breadcrumb text-sm', props.className)}>
    {items.map((item: AnyProps, index: number) =>
      <Breadcrumbs.Item
        href={item.href}
        key={item.key ?? index}
      >
        {item.title}
      </Breadcrumbs.Item>)}
  </Breadcrumbs>;

export const Menu = ({ items = [], onClick, selectedKeys = [], ...props }: AnyProps) =>
  <nav {...props} className={cx('app-menu', props.className)}>
    <MenuItems items={items} onClick={onClick} selectedKeys={selectedKeys} />
  </nav>;

function MenuItems({ items, onClick, selectedKeys }: { items: AnyProps[]; onClick?: AnyProps['onClick']; selectedKeys: string[] }) {
  return <div className="grid gap-1">
    {items.filter(Boolean).map((item, index) =>
      item.type === 'divider'
        ? <Separator className="app-menu-divider my-1" key={index} />
        : <MenuItem item={item} key={item.key ?? index} onClick={onClick} selectedKeys={selectedKeys} />)}
  </div>;
}

function MenuItem({ item, onClick, selectedKeys }: { item: AnyProps; onClick?: AnyProps['onClick']; selectedKeys: string[] }) {
  const hasChildren = item.type !== 'group' && Boolean(item.children?.length);
  const containsSelectedChild = item.children?.some((child: AnyProps) => selectedKeys.includes(String(child.key)));
  const [expanded, setExpanded] = useState(Boolean(containsSelectedChild));
  useEffect(() => {
    if (containsSelectedChild) setExpanded(true);
  }, [containsSelectedChild]);

  if (item.type === 'group') return <section className="grid gap-1">
    <strong className="px-2 py-1 text-xs font-semibold text-muted">{item.label}</strong>
    <MenuItems items={item.children ?? []} onClick={onClick} selectedKeys={selectedKeys} />
  </section>;

  if (hasChildren) return <Disclosure className="menu-branch" isExpanded={expanded} onExpandedChange={setExpanded}>
    <Disclosure.Heading>
      <Disclosure.Trigger
        aria-label={typeof item.label === 'string' ? item.label : undefined}
        className="menu-entry flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-sm"
        isDisabled={item.disabled}
      >
        {item.icon}<span className="menu-item-label min-w-0 flex-1 truncate">{item.label}</span>
        <Disclosure.Indicator />
      </Disclosure.Trigger>
    </Disclosure.Heading>
    <Disclosure.Content>
      <Disclosure.Body className="grid gap-1 pl-3">
        <MenuItems items={item.children} onClick={onClick} selectedKeys={selectedKeys} />
      </Disclosure.Body>
    </Disclosure.Content>
  </Disclosure>;

  const selected = selectedKeys.includes(String(item.key));
  return <HeroButton
    aria-label={typeof item.label === 'string' ? item.label : undefined}
    className={cx('menu-entry justify-start', selected && 'is-selected')}
    fullWidth
    isDisabled={item.disabled}
    onPress={() => (item.onClick ?? onClick)?.({ key: String(item.key) })}
    variant={selected ? 'secondary' : 'tertiary'}
  >
    {item.icon}<span className="menu-item-label min-w-0 truncate">{item.label}</span>
  </HeroButton>;
}

export const Tabs = ({ activeKey, defaultActiveKey, items = [], onChange, ...props }: AnyProps) => {
  const initialKey = defaultActiveKey ?? items[0]?.key;
  return <HeroTabs
    {...props}
    className={cx('app-tabs', props.className)}
    defaultSelectedKey={initialKey}
    onSelectionChange={(key) => onChange?.(String(key))}
    selectedKey={activeKey}
  >
    <HeroTabs.ListContainer>
      <HeroTabs.List aria-label={props['aria-label'] ?? '页面选项卡'} className="app-tab-list">
        {items.map((item: AnyProps) =>
          <HeroTabs.Tab id={item.key} key={item.key}>{item.label}</HeroTabs.Tab>)}
      </HeroTabs.List>
    </HeroTabs.ListContainer>
    {items.map((item: AnyProps) =>
      <HeroTabs.Panel className="app-tab-panel" id={item.key} key={item.key}>{item.children}</HeroTabs.Panel>)}
  </HeroTabs>;
};
