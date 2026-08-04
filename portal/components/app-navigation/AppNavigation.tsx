import {
  Database,
  GitBranch,
  Table2,
} from 'lucide-react';
import type {LucideIcon} from 'lucide-react';
import {useTranslation} from 'react-i18next';
import './AppNavigation.css';

type NavigationEntry = {
  href?: string;
  icon?: LucideIcon;
  label: string;
};

type NavigationGroup = {
  entries: NavigationEntry[];
  label: string;
};

type AppNavigationProps = {
  collapsed: boolean;
};

export function AppNavigation({collapsed}: AppNavigationProps) {
  const {t} = useTranslation();
  const groups: NavigationGroup[] = [
    {
      label: t('navigation.data'),
      entries: [
        {href: '/data/connections', icon: Database, label: t('navigation.dataConnections')},
        {href: '/data/pipelines', icon: GitBranch, label: t('navigation.pipelines')},
        {href: '/data/datasets', icon: Table2, label: t('navigation.datasets')},
      ],
    },
  ];

  return (
    <nav aria-label={t('navigation.primary')} className="app-navigation">
      {groups.map((group) => (
        <section className="navigation-group" key={group.label}>
          <strong aria-hidden={collapsed} className="navigation-group-title">
            {collapsed ? group.label.slice(0, 1) : group.label}
          </strong>
          <div className="navigation-items">
            {group.entries.map((entry) => (
              <NavigationItem entry={entry} key={entry.href ?? entry.label} />
            ))}
          </div>
        </section>
      ))}
    </nav>
  );
}

function NavigationItem({entry}: {entry: NavigationEntry}) {
  const Icon = entry.icon;
  const external = entry.href?.startsWith('http');
  const active = entry.href ? isCurrentPath(entry.href) : false;
  return (
    <a
      aria-current={active ? 'page' : undefined}
      className={active ? 'navigation-link is-active' : 'navigation-link'}
      href={entry.href}
      rel={external ? 'noreferrer' : undefined}
      target={external ? '_blank' : undefined}
      title={entry.label}
    >
      {Icon && <Icon aria-hidden="true" className="navigation-icon" size={18} />}
      <span className="navigation-label">{entry.label}</span>
    </a>
  );
}

function isCurrentPath(href: string) {
  if (href.startsWith('http')) {
    return false;
  }
  return window.location.pathname === href || window.location.pathname.startsWith(`${href}/`);
}
