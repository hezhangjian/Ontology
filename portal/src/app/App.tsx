import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AppstoreOutlined,
  BellOutlined,
  BranchesOutlined,
  BulbOutlined,
  CheckCircleFilled,
  CommentOutlined,
  ControlOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  FundOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MonitorOutlined,
  NotificationOutlined,
  PartitionOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Avatar, Badge, Breadcrumb, Button, Divider, Drawer, Dropdown, Layout, Menu, Result, Space, Tag, Typography } from 'antd';
import type { MenuProps } from 'antd';
import DataConnectionDetailPage from '../pages/data-connections/DataConnectionDetailPage';
import DataConnectionListPage from '../pages/data-connections/DataConnectionListPage';
import EditConnectionPage from '../pages/data-connections/EditConnectionPage';
import NewConnectionPage from '../pages/data-connections/NewConnectionPage';
import NewPipelinePage from '../pages/pipelines/NewPipelinePage';
import PipelineEditorPage from '../pages/pipelines/PipelineEditorPage';
import PipelineListPage from '../pages/pipelines/PipelineListPage';
import PipelineRunPage from '../pages/pipelines/PipelineRunPage';
import DatasetListPage from '../pages/datasets/DatasetListPage';
import DatasetDetailPage from '../pages/datasets/DatasetDetailPage';
import DatasetObjectWizardPage from '../pages/datasets/DatasetObjectWizardPage';
import OntologyOverviewPage from '../features/ontology/modeling/OntologyOverviewPage';
import OntologyResourceLayout from '../features/ontology/modeling/OntologyResourceLayout';
import OntologySearchPage from '../features/ontology/modeling/OntologySearchPage';
import OntologySettingsPage from '../features/ontology/modeling/OntologySettingsPage';
import PropertyCatalogPage from '../features/ontology/modeling/PropertyCatalogPage';
import ResourceDetailPage from '../features/ontology/modeling/ResourceDetailPage';
import ResourceEditorPage from '../features/ontology/modeling/ResourceEditorPage';
import ResourceListPage from '../features/ontology/modeling/ResourceListPage';
import type { ResourceKind } from '../features/ontology/modeling/ontology.types';
import ExplorerHomePage from '../features/ontology/explorer/ExplorerHomePage';
import ExplorerLayout from '../features/ontology/explorer/ExplorerLayout';
import GlobalObjectSearchPage from '../features/ontology/explorer/GlobalObjectSearchPage';
import ObjectDetailPage from '../features/ontology/explorer/ObjectDetailPage';
import ObjectExplorationPage from '../features/ontology/explorer/ObjectExplorationPage';
import SavedResourcesPage from '../features/ontology/explorer/SavedResourcesPage';
import DashboardEditorPage from '../features/applications/dashboards/pages/DashboardEditorPage';
import DashboardListPage from '../features/applications/dashboards/pages/DashboardListPage';
import DashboardVersionsPage from '../features/applications/dashboards/pages/DashboardVersionsPage';
import DashboardViewPage from '../features/applications/dashboards/pages/DashboardViewPage';
import OntologySwitcher from '../features/ontology/OntologySwitcher';
import ConversationCenterPage from '../features/aip/conversations/ConversationCenterPage';

const { Content, Header, Sider } = Layout;
const { Paragraph, Title } = Typography;

type DrawerName = 'assistant' | 'notifications' | null;
const componentUrl = (port: number) => `${window.location.protocol}//${window.location.hostname}:${port}`;

interface AppProps {
  accessToken: string;
  displayName: string;
  roles: string[];
  userId: string;
}

function App({ accessToken, displayName, roles, userId }: AppProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [drawer, setDrawer] = useState<DrawerName>(null);
  const [location, setLocation] = useState(window.location.pathname + window.location.search);
  const [ontologyEpoch, setOntologyEpoch] = useState(0);
  const handleOntologyChanged = useCallback(() => setOntologyEpoch((value) => value + 1), []);
  const isAdmin = roles.includes('Admin');
  const canBuild = isAdmin || roles.includes('Builder');

  useEffect(() => {
    const handler = () => setLocation(window.location.pathname + window.location.search);
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
  }, []);

  const navigate = (path: string) => {
    window.history.pushState({}, '', path);
    setLocation(path);
    window.dispatchEvent(new PopStateEvent('popstate'));
  };

  const navigation = useMemo<MenuProps['items']>(() => [
    {
      key: 'data-group', type: 'group', label: '数据', children: [
        { key: '/data/connections', icon: <DatabaseOutlined />, label: '数据连接' },
        { key: '/data/pipelines', icon: <BranchesOutlined />, label: '管道构建' },
        { key: '/data/datasets', icon: <DatabaseOutlined />, label: '数据集' },
        { key: '/data/quality', icon: <SafetyCertificateOutlined />, label: '数据质量', disabled: true },
        { key: '/data/lineage', icon: <PartitionOutlined />, label: '数据血缘', disabled: true },
      ],
    },
    { key: 'ontology-group', type: 'group', label: '本体', children: [{ key: '/ontology', icon: <DeploymentUnitOutlined />, label: '本体管理' }, { key: '/ontology/explorer', icon: <SearchOutlined />, label: '对象探索' }] },
    { key: 'applications-group', type: 'group', label: '应用', children: [{ key: '/apps/dashboards', icon: <FundOutlined />, label: '分析看板' }, { key: '/applications/business', icon: <AppstoreOutlined />, label: '业务应用', disabled: true }, { key: '/applications/automations', icon: <ThunderboltOutlined />, label: '自动化', disabled: true }, { key: '/applications/approvals', icon: <CheckCircleFilled />, label: '审批中心', disabled: true }] },
    { key: 'aip-group', type: 'group', label: 'AIP', children: [{ key: '/aip/conversations', icon: <CommentOutlined />, label: '对话中心' }, { key: '/aip/agents', icon: <RobotOutlined />, label: '智能体工作室', disabled: true }] },
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
          { key: `external:${componentUrl(9200)}`, label: 'OpenSearch' },
          { key: `external:${componentUrl(8080)}`, label: 'Pulsar' },
          { key: `external:${componentUrl(8082)}`, label: 'SkyWalking' },
        ],
      }],
    },
  ], []);

  const userItems = useMemo<MenuProps['items']>(() => [
    { key: 'profile', label: '个人资料', icon: <UserOutlined />, disabled: true },
    { key: 'settings', label: '偏好设置', icon: <SettingOutlined />, disabled: true },
  ], []);

  const path = window.location.pathname;
  if (path.match(/^\/apps\/dashboards\/[^/]+\/(edit|fullscreen)$/)) {
    return <RouteContent accessToken={accessToken} canBuild={canBuild} displayName={displayName} isAdmin={isAdmin} location={location} navigate={navigate} userId={userId} />;
  }
  const crumbs = path.startsWith('/ontology/explorer')
    ? ['本体', '对象探索', explorerCrumb(path)]
    : path.startsWith('/ontology')
    ? ['本体', '本体管理', ontologyCrumb(path)]
    : path.startsWith('/aip/conversations')
    ? ['AIP', '对话中心']
    : path.startsWith('/apps/dashboards')
    ? ['应用', '分析看板', dashboardCrumb(path)]
    : path.startsWith('/data/datasets')
    ? ['数据', '数据集', path === '/data/datasets' ? '列表' : '详情']
    : path.startsWith('/data/pipelines')
    ? path.includes('/runs/') ? ['数据', '管道构建', '运行详情'] : path.endsWith('/new') ? ['数据', '管道构建', '新建管道'] : path.includes('/edit') ? ['数据', '管道构建', 'DAG 编辑器'] : ['数据', '管道构建']
    : path.includes('/new') ? ['数据', '数据连接', '新建连接'] : path.includes('/edit') ? ['数据', '数据连接', '编辑配置'] : path.match(/^\/data\/connections\/[^/]+/) ? ['数据', '数据连接', '连接详情'] : ['数据', '数据连接'];

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" collapsed={collapsed} collapsedWidth={72} trigger={null} width={238}>
        <div className="brand"><div className="brand-identity" aria-label="Ontology Platform"><div className="brand-mark">O</div>{!collapsed && <div className="brand-copy"><div className="brand-name">Ontology</div><div className="brand-caption">Platform</div></div>}</div><OntologySwitcher accessToken={accessToken} canCreate={canBuild} collapsed={collapsed} onChanged={handleOntologyChanged} /></div>
        <Menu className="product-menu" mode="inline" items={navigation} selectedKeys={[path.startsWith('/aip/conversations') ? '/aip/conversations' : path.startsWith('/ontology/explorer') ? '/ontology/explorer' : path.startsWith('/ontology') ? '/ontology' : path.startsWith('/apps/dashboards') ? '/apps/dashboards' : path.startsWith('/data/connections') ? '/data/connections' : path.startsWith('/data/pipelines') ? '/data/pipelines' : path.startsWith('/data/datasets') ? '/data/datasets' : path]} onClick={({ key }) => key.startsWith('external:') ? window.open(key.slice(9), '_blank', 'noopener,noreferrer') : navigate(key)} />
        <div className="sider-footer"><Divider /><Button aria-label="控制面板" className="control-button" icon={<ControlOutlined />} type="text">{!collapsed && '控制面板'}</Button><Button aria-label={collapsed ? '展开导航' : '折叠导航'} className="collapse-button" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => setCollapsed((current) => !current)} type="text">{!collapsed && '收起导航'}</Button></div>
      </Sider>
      <Layout>
        <Header className="app-header"><Breadcrumb items={crumbs.map((title) => ({ title }))} /><Space size={8}><Tag color="green">免登录</Tag><Button aria-label="全局搜索" icon={<SearchOutlined />} type="text"><span className="header-button-label">搜索</span></Button><Badge dot offset={[-7, 7]}><Button aria-label="通知" icon={<BellOutlined />} onClick={() => setDrawer('notifications')} type="text" /></Badge><Button aria-label="AIP 助手" className="assistant-button" icon={<BulbOutlined />} onClick={() => setDrawer('assistant')}>AIP 助手</Button><Dropdown menu={{ items: userItems }} placement="bottomRight" trigger={['click']}><Button aria-label="用户菜单" className="user-button" type="text"><Avatar size={32}>{displayName.slice(0, 1)}</Avatar><span className="header-button-label">{displayName}</span></Button></Dropdown></Space></Header>
        <Content className="app-content"><RouteContent key={ontologyEpoch} accessToken={accessToken} canBuild={canBuild} displayName={displayName} isAdmin={isAdmin} location={location} navigate={navigate} userId={userId} /></Content>
      </Layout>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'notifications'} title="通知" width={420}><div className="drawer-empty"><NotificationOutlined /><Title level={4}>通知中心已准备</Title><Paragraph>资产变化、管道失败和质量告警将在相应阶段接入。</Paragraph></div></Drawer>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'assistant'} title="AIP 助手" width={480}><Tag color="geekblue">当前上下文：平台</Tag><div className="drawer-empty assistant-empty"><RobotOutlined /><Title level={4}>在对话中心使用 Agent</Title><Paragraph>Agent 会调用受控的平台工具完成查询、分析和变更预览。</Paragraph><Button onClick={() => { setDrawer(null); navigate('/aip/conversations'); }} type="primary">打开对话中心</Button></div></Drawer>
    </Layout>
  );
}

function RouteContent({ accessToken, canBuild, displayName, isAdmin, location, navigate, userId }: { accessToken: string; canBuild: boolean; displayName: string; isAdmin: boolean; location: string; navigate: (path: string) => void; userId: string }) {
  const path = location.split('?')[0];
  if (path === '/aip/conversations') return <ConversationCenterPage accessToken={accessToken} />;
  if (path === '/apps/dashboards') return <DashboardListPage accessToken={accessToken} canBuild={canBuild} navigate={navigate} />;
  if (path === '/data/datasets') return <DatasetListPage accessToken={accessToken} canBuild={canBuild} navigate={navigate} />;
  const datasetDetail = path.match(/^\/data\/datasets\/([^/]+)$/);
  if (datasetDetail) return <DatasetDetailPage accessToken={accessToken} canBuild={canBuild} id={datasetDetail[1]} navigate={navigate} />;
  const dashboardVersion = path.match(/^\/apps\/dashboards\/([^/]+)\/versions\/([^/]+)$/);
  if (dashboardVersion) return <DashboardViewPage accessToken={accessToken} dashboardId={dashboardVersion[1]} navigate={navigate} versionId={dashboardVersion[2]} />;
  const dashboardRoute = path.match(/^\/apps\/dashboards\/([^/]+)\/(view|edit|fullscreen|versions)$/);
  if (dashboardRoute) {
    const [, id, mode] = dashboardRoute;
    if (mode === 'edit') return canBuild ? <DashboardEditorPage accessToken={accessToken} dashboardId={id} navigate={navigate} /> : <Result status="403" title="Viewer 不能编辑看板" />;
    if (mode === 'versions') return <DashboardVersionsPage accessToken={accessToken} dashboardId={id} navigate={navigate} />;
    return <DashboardViewPage accessToken={accessToken} dashboardId={id} fullscreen={mode === 'fullscreen'} navigate={navigate} />;
  }
  if (path.startsWith('/ontology/explorer')) {
    const page = explorerRoute({ accessToken, canBuild, location, navigate, path });
    return <ExplorerLayout navigate={navigate} path={path}>{page}</ExplorerLayout>;
  }
  if (path.startsWith('/ontology')) {
    const page = ontologyRoute({ accessToken, canBuild, displayName, isAdmin, navigate, path, userId });
    return <OntologyResourceLayout navigate={navigate} path={path}>{page}</OntologyResourceLayout>;
  }
  if (path === '/data/pipelines/new') return <NewPipelinePage accessToken={accessToken} displayName={displayName} navigate={navigate} userId={userId} />;
  const pipelineRun = path.match(/^\/data\/pipelines\/([^/]+)\/runs\/([^/]+)$/);
  if (pipelineRun) return <PipelineRunPage accessToken={accessToken} isAdmin={isAdmin} navigate={navigate} pipelineId={pipelineRun[1]} runId={pipelineRun[2]} />;
  const pipelineProposal = path.match(/^\/data\/pipelines\/([^/]+)\/proposals\/([^/]+)$/);
  if (pipelineProposal) return <PipelineEditorPage accessToken={accessToken} id={pipelineProposal[1]} initialTab="proposals" isAdmin={isAdmin} navigate={navigate} />;
  const pipelineEditor = path.match(/^\/data\/pipelines\/([^/]+)(?:\/edit)?$/);
  if (pipelineEditor) return <PipelineEditorPage accessToken={accessToken} id={pipelineEditor[1]} isAdmin={isAdmin} navigate={navigate} />;
  if (path === '/data/pipelines') return <PipelineListPage accessToken={accessToken} isAdmin={isAdmin} navigate={navigate} />;
  if (path === '/data/connections/new') return <NewConnectionPage accessToken={accessToken} displayName={displayName} isAdmin={isAdmin} navigate={navigate} userId={userId} />;
  const asset = path.match(/^\/data\/connections\/([^/]+)\/assets\/([^/]+)$/);
  if (asset) return <DataConnectionDetailPage accessToken={accessToken} assetId={asset[2]} id={asset[1]} isAdmin={isAdmin} navigate={navigate} />;
  const edit = path.match(/^\/data\/connections\/([^/]+)\/edit$/);
  if (edit) return <EditConnectionPage accessToken={accessToken} id={edit[1]} navigate={navigate} />;
  const detail = path.match(/^\/data\/connections\/([^/]+)$/);
  if (detail) return <DataConnectionDetailPage accessToken={accessToken} id={detail[1]} isAdmin={isAdmin} navigate={navigate} />;
  if (path === '/data/connections' || path === '/') return <DataConnectionListPage accessToken={accessToken} isAdmin={isAdmin} navigate={navigate} />;
  return <Result extra={<Button onClick={() => navigate('/data/connections')}>返回数据连接</Button>} status="404" title="页面尚未交付" />;
}

function explorerRoute({ accessToken, canBuild, location, navigate, path }: { accessToken: string; canBuild: boolean; location: string; navigate: (path: string) => void; path: string }) {
  if (path === '/ontology/explorer/search') return <GlobalObjectSearchPage accessToken={accessToken} navigate={navigate} />;
  if (path === '/ontology/explorer') {
    const section = new URLSearchParams(location.split('?')[1] ?? '').get('section');
    if (section === 'explorations') return <SavedResourcesPage accessToken={accessToken} kind="explorations" navigate={navigate} />;
    if (section === 'lists') return <SavedResourcesPage accessToken={accessToken} kind="lists" navigate={navigate} />;
    return <ExplorerHomePage accessToken={accessToken} navigate={navigate} />;
  }
  const exploration = path.match(/^\/ontology\/explorer\/explorations\/([^/]+)$/);
  if (exploration) return <ObjectExplorationPage accessToken={accessToken} explorationId={exploration[1]} navigate={navigate} />;
  const list = path.match(/^\/ontology\/explorer\/lists\/([^/]+)$/);
  if (list) return <SavedResourcesPage accessToken={accessToken} kind="lists" navigate={navigate} />;
  const object = path.match(/^\/ontology\/explorer\/([^/]+)\/([^/]+)$/);
  if (object) return <ObjectDetailPage accessToken={accessToken} canBuild={canBuild} navigate={navigate} objectId={decodeURIComponent(object[2])} objectTypeId={object[1]} />;
  const type = path.match(/^\/ontology\/explorer\/([^/]+)$/);
  if (type) return <ObjectExplorationPage accessToken={accessToken} navigate={navigate} objectTypeId={type[1]} />;
  return <Result extra={<Button onClick={() => navigate('/ontology/explorer')}>返回对象探索</Button>} status="404" title="探索页面不存在" />;
}

function ontologyRoute({ accessToken, canBuild, displayName, isAdmin, navigate, path, userId }: { accessToken: string; canBuild: boolean; displayName: string; isAdmin: boolean; navigate: (path: string) => void; path: string; userId: string }) {
  if (path === '/ontology' || path === '/ontology/modeling') return <OntologyOverviewPage accessToken={accessToken} canBuild={canBuild} navigate={navigate} />;
  if (path === '/ontology/object-types/new/from-dataset') return canBuild ? <DatasetObjectWizardPage accessToken={accessToken} displayName={displayName} navigate={navigate} userId={userId} /> : <Result status="403" title="Viewer 只能查看已发布本体" />;
  if (path === '/ontology/search') return <OntologySearchPage accessToken={accessToken} navigate={navigate} />;
  if (path === '/ontology/properties') return <PropertyCatalogPage accessToken={accessToken} />;
  if (path.startsWith('/ontology/proposals') || path === '/ontology/health' || path === '/ontology/history') return <Result status="404" title="该治理页面不向普通用户开放" />;
  if (path === '/ontology/settings') return <OntologySettingsPage isAdmin={isAdmin} />;
  const routes: Array<[string, ResourceKind]> = [['object-types', 'OBJECT_TYPE'], ['link-types', 'LINK_TYPE'], ['interfaces', 'INTERFACE'], ['actions', 'ACTION'], ['functions', 'FUNCTION']];
  for (const [segment, kind] of routes) {
    if (path === `/ontology/${segment}`) return <ResourceListPage accessToken={accessToken} canBuild={canBuild} kind={kind} navigate={navigate} />;
    if (path === `/ontology/${segment}/new`) return canBuild ? <ResourceEditorPage accessToken={accessToken} displayName={displayName} kind={kind} navigate={navigate} userId={userId} /> : <Result status="403" title="Viewer 只能查看已发布本体" />;
    const detail = path.match(new RegExp(`^/ontology/${segment}/([^/]+)$`));
    if (detail) return <ResourceDetailPage accessToken={accessToken} canBuild={canBuild} id={detail[1]} isAdmin={isAdmin} kind={kind} navigate={navigate} />;
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
  return '概览';
}

function explorerCrumb(path: string) {
  if (path.includes('/search')) return '全局搜索';
  if (path.includes('/explorations/')) return '已保存探索';
  if (path.includes('/lists/')) return '对象清单';
  return path === '/ontology/explorer' ? '首页' : '对象视图';
}

function dashboardCrumb(path: string) {
  if (path.endsWith('/edit')) return '全屏编辑器';
  if (path.includes('/versions/')) return '历史版本预览';
  if (path.endsWith('/versions')) return '版本记录';
  if (path.endsWith('/fullscreen')) return '演示模式';
  if (path.endsWith('/view')) return '查看';
  return '列表';
}

export default App;
