import { lazy, startTransition, Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import {
  BranchesOutlined,
  BulbOutlined,
  CommentOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  ExperimentOutlined,
  FundOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MonitorOutlined,
  RobotOutlined,
  CompassOutlined,
  SearchOutlined,
} from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Result, Spin } from '@/shared/components/feedback';
import { Layout, Space } from '@/shared/components/layout';
import { Breadcrumb, Menu } from '@/shared/components/navigation';
import type { MenuProps } from '@/shared/components/navigation';
import { Drawer } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import OntologyResourceLayout from './features/ontology/modeling/OntologyResourceLayout';
import type { ResourceKind } from './features/ontology/modeling/ontology.types';
import OntologySwitcher from './features/ontology/OntologySwitcher';
import { activeOntologyId } from './features/ontology/ontologyContext';

const AssistedDevelopmentDashboardPage = lazy(() => import('./app/effective/AiAssistedDevelopmentDashboardPage'));
const ConversationCenterPage = lazy(() => import('./features/aip/conversations/ConversationCenterPage'));
const DashboardEditorPage = lazy(() => import('./features/applications/dashboards/pages/DashboardEditorPage'));
const DashboardListPage = lazy(() => import('./features/applications/dashboards/pages/DashboardListPage'));
const DashboardViewPage = lazy(() => import('./features/applications/dashboards/pages/DashboardViewPage'));
const QualityOperationsDashboardPage = lazy(() => import('./app/quality/DataAiOperationsDashboardPage'));
const QualityDashboardOnePage = lazy(() => import('./app/quality/dashboard-one/DashboardOnePage'));
const QualityDashboardThreePage = lazy(() => import('./app/quality/dashboard-three/DashboardThreePage'));
const QualityDashboardTwoPage = lazy(() => import('./app/quality/dashboard-two/DashboardTwoPage'));
const DataConnectionDetailPage = lazy(() => import('./pages/data-connections/DataConnectionDetailPage'));
const DataConnectionListPage = lazy(() => import('./pages/data-connections/DataConnectionListPage'));
const DatasetDetailPage = lazy(() => import('./pages/datasets/DatasetDetailPage'));
const DatasetEditorPage = lazy(() => import('./pages/datasets/DatasetEditorPage'));
const DatasetListPage = lazy(() => import('./pages/datasets/DatasetListPage'));
const DatasetObjectWizardPage = lazy(() => import('./pages/datasets/DatasetObjectWizardPage'));
const EditConnectionPage = lazy(() => import('./pages/data-connections/EditConnectionPage'));
const ExplorerHomePage = lazy(() => import('./features/ontology/explorer/ExplorerHomePage'));
const GlobalObjectSearchPage = lazy(() => import('./features/ontology/explorer/GlobalObjectSearchPage'));
const NewConnectionPage = lazy(() => import('./pages/data-connections/NewConnectionPage'));
const NewPipelinePage = lazy(() => import('./pages/pipelines/NewPipelinePage'));
const ObjectDetailPage = lazy(() => import('./features/ontology/explorer/ObjectDetailPage'));
const ObjectExplorationPage = lazy(() => import('./features/ontology/explorer/ObjectExplorationPage'));
const OntologyHealthPage = lazy(() => import('./features/ontology/modeling/OntologyHealthPage'));
const OntologyOverviewPage = lazy(() => import('./features/ontology/modeling/OntologyOverviewPage'));
const OntologySearchPage = lazy(() => import('./features/ontology/modeling/OntologySearchPage'));
const PipelineEditorPage = lazy(() => import('./pages/pipelines/PipelineEditorPage'));
const PipelineListPage = lazy(() => import('./pages/pipelines/PipelineListPage'));
const PipelineRunPage = lazy(() => import('./pages/pipelines/PipelineRunPage'));
const PropertyCatalogPage = lazy(() => import('./features/ontology/modeling/PropertyCatalogPage'));
const ResourceDetailPage = lazy(() => import('./features/ontology/modeling/ResourceDetailPage'));
const ResourceEditorPage = lazy(() => import('./features/ontology/modeling/ResourceEditorPage'));
const ResourceListPage = lazy(() => import('./features/ontology/modeling/ResourceListPage'));

const { Content, Header, Sider } = Layout;
const { Paragraph, Title } = Typography;

type DrawerName = 'assistant' | null;
const componentUrl = (port: number) => `${window.location.protocol}//${window.location.hostname}:${port}`;

function App() {
  const [collapsed, setCollapsed] = useState(false);
  const [drawer, setDrawer] = useState<DrawerName>(null);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [location, setLocation] = useState(window.location.pathname + window.location.search);
  const [ontologyId, setOntologyId] = useState(activeOntologyId);
  const [ontologyEpoch, setOntologyEpoch] = useState(0);
  const handleOntologyChanged = useCallback(() => {
    startTransition(() => {
      setOntologyEpoch((value) => value + 1);
      setOntologyId(activeOntologyId());
    });
  }, []);

  useEffect(() => {
    const handler = () => {
      startTransition(() => setLocation(window.location.pathname + window.location.search));
    };
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
  }, []);

  const navigate = (path: string) => {
    window.history.pushState({}, '', path);
    startTransition(() => setLocation(path));
    window.dispatchEvent(new PopStateEvent('popstate'));
  };
  const navigateFromMenu = (key: string) => {
    setMobileNavigationOpen(false);
    if (key.startsWith('external:')) {
      window.open(key.slice(9), '_blank', 'noopener,noreferrer');
      return;
    }
    navigate(key);
  };

  const navigation = useMemo<MenuProps['items']>(() => [
    {
      key: 'data-group', type: 'group', label: '数据', children: [
        { key: '/data/connections', icon: <DatabaseOutlined />, label: '数据连接' },
        { key: '/data/pipelines', icon: <BranchesOutlined />, label: '管道构建' },
        { key: '/data/datasets', icon: <DatabaseOutlined />, label: '数据集' },
      ],
    },
    {
      key: 'ontology-group',
      type: 'group',
      label: '本体',
      children: [
        { key: '/ontology', icon: <DeploymentUnitOutlined />, label: '本体管理' },
        {
          key: 'object-explorer',
          icon: <SearchOutlined />,
          label: '对象探索',
          children: [
            { key: '/ontology/explorer', icon: <CompassOutlined />, label: '概览' },
            { key: '/ontology/explorer/search', icon: <SearchOutlined />, label: '搜索' },
          ],
        },
      ],
    },
    {
      key: 'applications-group',
      type: 'group',
      label: '应用',
      children: [
        ...(ontologyId === 'dataai' ? [
          { key: '/apps/assisted-development', icon: <ExperimentOutlined />, label: '辅助研发看板' },
          {
            key: '/apps/quality-operations',
            icon: <DashboardOutlined />,
            label: '质量运营看板',
            children: [
              { key: '/apps/quality-operations/dashboard-1', label: '看板一' },
              { key: '/apps/quality-operations/dashboard-2', label: '看板二' },
              { key: '/apps/quality-operations/dashboard-3', label: '看板三' },
            ],
          },
        ] : []),
        { key: '/apps/dashboards', icon: <FundOutlined />, label: '分析看板' },
      ],
    },
    { key: 'aip-group', type: 'group', label: 'AIP', children: [{ key: '/aip/conversations', icon: <CommentOutlined />, label: '对话中心' }] },
    {
      key: 'runtime-group',
      type: 'group',
      label: '系统',
      children: [{
        key: 'runtime',
        icon: <MonitorOutlined />,
        label: '组件运行状态',
        children: [
          { key: `external:${componentUrl(8081)}`, label: 'Flink' },
          { key: `external:${componentUrl(8088)}`, label: 'HugeGraph' },
          { key: `external:${componentUrl(9001)}`, label: 'MinIO' },
          { key: `external:${componentUrl(5601)}`, label: 'OpenSearch' },
          { key: `external:${componentUrl(8083)}`, label: 'Pulsar' },
          { key: `external:${componentUrl(8084)}`, label: 'SkyWalking' },
        ],
      }],
    },
  ], [ontologyId]);

  const path = window.location.pathname;
  if (path.match(/^\/apps\/dashboards\/[^/]+\/(edit|fullscreen)$/)) {
    return <Suspense fallback={<PageFallback />}>
      <RouteContent location={location} navigate={navigate} ontologyId={ontologyId} />
    </Suspense>;
  }
  const crumbs = path.startsWith('/ontology/explorer')
    ? ['本体', '对象探索', explorerCrumb(path)]
    : path.startsWith('/ontology')
    ? ['本体', '本体管理', ontologyCrumb(path)]
    : path.startsWith('/aip/conversations')
    ? ['AIP', '对话中心']
    : path.startsWith('/apps/assisted-development')
    ? ['应用', '辅助研发看板']
    : path.startsWith('/apps/quality-operations')
    ? ['应用', '质量运营看板', qualityDashboardCrumb(path)]
    : path.startsWith('/apps/dashboards')
    ? ['应用', '分析看板', dashboardCrumb(path)]
    : path.startsWith('/data/datasets')
    ? ['数据', '数据集', path === '/data/datasets' ? '列表' : '详情']
    : path.startsWith('/data/pipelines')
    ? path.includes('/runs/') ? ['数据', '管道构建', '运行详情'] : path.endsWith('/new') ? ['数据', '管道构建', '新建管道'] : path.includes('/edit') ? ['数据', '管道构建', 'DAG 编辑器'] : ['数据', '管道构建']
    : path.includes('/new') ? ['数据', '数据连接', '新建连接'] : path.includes('/edit') ? ['数据', '数据连接', '编辑配置'] : path.match(/^\/data\/connections\/[^/]+/) ? ['数据', '数据连接', '连接详情'] : ['数据', '数据连接'];
  const selectedNavigationKey = path.startsWith('/aip/conversations') ? '/aip/conversations' : path === '/ontology/explorer/search' ? '/ontology/explorer/search' : path.startsWith('/ontology/explorer') ? '/ontology/explorer' : path.startsWith('/ontology') ? '/ontology' : path.startsWith('/apps/assisted-development') ? '/apps/assisted-development' : path.startsWith('/apps/quality-operations') ? path : path.startsWith('/apps/dashboards') ? '/apps/dashboards' : path.startsWith('/data/connections') ? '/data/connections' : path.startsWith('/data/pipelines') ? '/data/pipelines' : path.startsWith('/data/datasets') ? '/data/datasets' : path;

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" collapsed={collapsed} collapsedWidth={72} trigger={null} width={238}>
        <Button
          aria-label={collapsed ? '展开导航' : '折叠导航'}
          className="sidebar-toggle"
          icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          onClick={() => setCollapsed((current) => !current)}
          type="text"
        />
        <div className="brand">
          {!collapsed && <div className="brand-identity" aria-label="Ontology Platform">
            <img className="brand-mark" src="/ontology-icon.svg" alt="" aria-hidden="true" />
            <div className="brand-copy"><div className="brand-name">Ontology</div><div className="brand-caption">Data Intelligence</div></div>
          </div>}
          <OntologySwitcher collapsed={collapsed} onChanged={handleOntologyChanged} />
        </div>
        <Menu className="product-menu" mode="inline" items={navigation} selectedKeys={[selectedNavigationKey]} onClick={({ key }) => navigateFromMenu(key)} />
      </Sider>
      <Layout>
        <Header className="app-header">
          <div className="header-context">
            <Button aria-label="打开导航" className="header-icon-button mobile-nav-button" icon={<MenuUnfoldOutlined />} onClick={() => setMobileNavigationOpen(true)} type="text" />
            <Breadcrumb items={crumbs.map((title) => ({ title }))} />
            <span className="environment-badge"><span />本地开发</span>
          </div>
          <Space className="header-actions" size={6}>
            <Button aria-label="AIP 助手" className="assistant-button" icon={<BulbOutlined />} onClick={() => setDrawer('assistant')}>AIP 助手</Button>
          </Space>
        </Header>
        <Content className="app-content">
          <Suspense fallback={<PageFallback />}>
            <RouteContent key={ontologyEpoch} location={location} navigate={navigate} ontologyId={ontologyId} />
          </Suspense>
        </Content>
      </Layout>
      <Drawer className="mobile-navigation-drawer" onClose={() => setMobileNavigationOpen(false)} open={mobileNavigationOpen} placement="left" title="导航" width="min(320px, calc(100vw - 32px))">
        <div className="mobile-navigation-brand">
          <div className="brand-identity" aria-label="Ontology Platform">
            <img className="brand-mark" src="/ontology-icon.svg" alt="" aria-hidden="true" />
            <div className="brand-copy"><div className="brand-name">Ontology</div><div className="brand-caption">Data Intelligence</div></div>
          </div>
          <OntologySwitcher collapsed={false} onChanged={handleOntologyChanged} />
        </div>
        <Menu className="product-menu mobile-product-menu" mode="inline" items={navigation} selectedKeys={[selectedNavigationKey]} onClick={({ key }) => navigateFromMenu(key)} />
      </Drawer>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'assistant'} title="AIP 助手" width={480}><Tag color="geekblue">当前上下文：平台</Tag><div className="drawer-empty assistant-empty"><RobotOutlined /><Title level={4}>在对话中心使用 Agent</Title><Paragraph>Agent 会调用受控的平台工具完成查询、分析和变更预览。</Paragraph><Button onClick={() => { setDrawer(null); navigate('/aip/conversations'); }} type="primary">打开对话中心</Button></div></Drawer>
    </Layout>
  );
}

function PageFallback() {
  return <div className="page-loading" role="status"><Spin /></div>;
}

function RouteContent({ location, navigate, ontologyId }: { location: string; navigate: (path: string) => void; ontologyId: string }) {
  const path = location.split('?')[0];
  if (path === '/aip/conversations') return <ConversationCenterPage />;
  if (path === '/apps/assisted-development' || path.startsWith('/apps/quality-operations')) {
    if (ontologyId !== 'dataai') return <Result extra={<Button onClick={() => navigate('/apps/dashboards')}>返回应用</Button>} status="404" title="页面不存在" />;
    if (path === '/apps/assisted-development') return <AssistedDevelopmentDashboardPage />;
    if (path === '/apps/quality-operations/dashboard-1') return <QualityDashboardOnePage />;
    if (path === '/apps/quality-operations/dashboard-2') return <QualityDashboardTwoPage />;
    if (path === '/apps/quality-operations/dashboard-3') return <QualityDashboardThreePage />;
    return <QualityOperationsDashboardPage />;
  }
  if (path === '/apps/dashboards') return <DashboardListPage navigate={navigate} />;
  if (path === '/data/datasets') return <DatasetListPage navigate={navigate} />;
  if (path === '/data/datasets/new') return <DatasetEditorPage navigate={navigate} />;
  const datasetEdit = path.match(/^\/data\/datasets\/([^/]+)\/edit$/);
  if (datasetEdit) return <DatasetEditorPage id={datasetEdit[1]} navigate={navigate} />;
  const datasetDetail = path.match(/^\/data\/datasets\/([^/]+)$/);
  if (datasetDetail) return <DatasetDetailPage id={datasetDetail[1]} navigate={navigate} />;
  const dashboardRoute = path.match(/^\/apps\/dashboards\/([^/]+)\/(view|edit|fullscreen)$/);
  if (dashboardRoute) {
    const [, id, mode] = dashboardRoute;
    if (mode === 'edit') return <DashboardEditorPage dashboardId={id} navigate={navigate} />;
    return <DashboardViewPage dashboardId={id} fullscreen={mode === 'fullscreen'} navigate={navigate} />;
  }
  if (path.startsWith('/ontology/explorer')) {
    return explorerRoute({ navigate, path });
  }
  if (path.startsWith('/ontology')) {
    const page = ontologyRoute({ navigate, path });
    return <OntologyResourceLayout navigate={navigate} path={path}>{page}</OntologyResourceLayout>;
  }
  if (path === '/data/pipelines/new') return <NewPipelinePage navigate={navigate} />;
  const pipelineRun = path.match(/^\/data\/pipelines\/([^/]+)\/runs\/([^/]+)$/);
  if (pipelineRun) return <PipelineRunPage navigate={navigate} pipelineId={pipelineRun[1]} runId={pipelineRun[2]} />;
  const pipelineEditor = path.match(/^\/data\/pipelines\/([^/]+)(?:\/edit)?$/);
  if (pipelineEditor) return <PipelineEditorPage id={pipelineEditor[1]} navigate={navigate} />;
  if (path === '/data/pipelines') return <PipelineListPage navigate={navigate} />;
  if (path === '/data/connections/new') return <NewConnectionPage navigate={navigate} />;
  const asset = path.match(/^\/data\/connections\/([^/]+)\/assets\/([^/]+)$/);
  if (asset) return <DataConnectionDetailPage assetId={asset[2]} id={asset[1]} navigate={navigate} />;
  const edit = path.match(/^\/data\/connections\/([^/]+)\/edit$/);
  if (edit) return <EditConnectionPage id={edit[1]} navigate={navigate} />;
  const detail = path.match(/^\/data\/connections\/([^/]+)$/);
  if (detail) return <DataConnectionDetailPage id={detail[1]} navigate={navigate} />;
  if (path === '/data/connections' || path === '/') return <DataConnectionListPage navigate={navigate} />;
  return <Result extra={<Button onClick={() => navigate('/data/connections')}>返回数据连接</Button>} status="404" title="页面尚未交付" />;
}

function explorerRoute({ navigate, path }: { navigate: (path: string) => void; path: string }) {
  if (path === '/ontology/explorer/search') return <GlobalObjectSearchPage navigate={navigate} />;
  if (path === '/ontology/explorer') return <ExplorerHomePage navigate={navigate} />;
  const object = path.match(/^\/ontology\/explorer\/([^/]+)\/([^/]+)$/);
  if (object) return <ObjectDetailPage navigate={navigate} objectId={decodeURIComponent(object[2])} objectTypeId={object[1]} />;
  const type = path.match(/^\/ontology\/explorer\/([^/]+)$/);
  if (type) return <ObjectExplorationPage navigate={navigate} objectTypeId={type[1]} />;
  return <Result extra={<Button onClick={() => navigate('/ontology/explorer')}>返回对象探索</Button>} status="404" title="探索页面不存在" />;
}

function ontologyRoute({ navigate, path }: { navigate: (path: string) => void; path: string }) {
  if (path === '/ontology' || path === '/ontology/modeling') return <OntologyOverviewPage navigate={navigate} />;
  if (path === '/ontology/object-types/new/from-dataset') return <DatasetObjectWizardPage navigate={navigate} />;
  if (path === '/ontology/search') return <OntologySearchPage navigate={navigate} />;
  if (path === '/ontology/properties') return <PropertyCatalogPage />;
  if (path === '/ontology/health') return <OntologyHealthPage />;
  const routes: Array<[string, ResourceKind]> = [['object-types', 'OBJECT_TYPE'], ['link-types', 'LINK_TYPE'], ['interfaces', 'INTERFACE'], ['actions', 'ACTION'], ['functions', 'FUNCTION']];
  for (const [segment, kind] of routes) {
    if (path === `/ontology/${segment}`) return <ResourceListPage kind={kind} navigate={navigate} />;
    if (path === `/ontology/${segment}/new`) return <ResourceEditorPage kind={kind} navigate={navigate} />;
    const detail = path.match(new RegExp(`^/ontology/${segment}/([^/]+)$`));
    if (detail) return <ResourceDetailPage id={detail[1]} kind={kind} navigate={navigate} />;
  }
  return <Result extra={<Button onClick={() => navigate('/ontology')}>返回本体概览</Button>} status="404" title="本体页面不存在" />;
}

function ontologyCrumb(path: string) {
  if (path === '/ontology/object-types/new/from-dataset') return '从 Dataset 创建对象';
  if (path.includes('/object-types')) return '对象类型';
  if (path.includes('/properties')) return '属性目录';
  if (path.includes('/link-types')) return '关系类型';
  if (path.includes('/interfaces')) return '接口';
  if (path.includes('/actions')) return '动作';
  if (path.includes('/functions')) return '函数';
  if (path.includes('/health')) return '健康问题';
  return '概览';
}

function explorerCrumb(path: string) {
  if (path.includes('/search')) return '搜索';
  return path === '/ontology/explorer' ? '概览' : '对象视图';
}

function dashboardCrumb(path: string) {
  if (path.endsWith('/edit')) return '全屏编辑器';
  if (path.endsWith('/fullscreen')) return '演示模式';
  if (path.endsWith('/view')) return '查看';
  return '列表';
}

function qualityDashboardCrumb(path: string) {
  if (path.endsWith('/dashboard-1')) return '看板一';
  if (path.endsWith('/dashboard-2')) return '看板二';
  if (path.endsWith('/dashboard-3')) return '看板三';
  return '概览';
}

export default App;
