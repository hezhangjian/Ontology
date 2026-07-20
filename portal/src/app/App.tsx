import { useEffect, useMemo, useState } from 'react';
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
import OntologyHealthPage from '../features/ontology/modeling/OntologyHealthPage';
import OntologyHistoryPage from '../features/ontology/modeling/OntologyHistoryPage';
import OntologyOverviewPage from '../features/ontology/modeling/OntologyOverviewPage';
import OntologyResourceLayout from '../features/ontology/modeling/OntologyResourceLayout';
import OntologySearchPage from '../features/ontology/modeling/OntologySearchPage';
import OntologySettingsPage from '../features/ontology/modeling/OntologySettingsPage';
import PropertyCatalogPage from '../features/ontology/modeling/PropertyCatalogPage';
import { ProposalDetailPage, ProposalListPage } from '../features/ontology/modeling/ProposalPages';
import ResourceDetailPage from '../features/ontology/modeling/ResourceDetailPage';
import ResourceEditorPage from '../features/ontology/modeling/ResourceEditorPage';
import ResourceListPage from '../features/ontology/modeling/ResourceListPage';
import type { ResourceKind } from '../features/ontology/modeling/ontology.types';

const { Content, Header, Sider } = Layout;
const { Paragraph, Title } = Typography;

type DrawerName = 'assistant' | 'notifications' | null;

interface AppProps {
  accessToken: string;
  displayName: string;
  roles: string[];
  userId: string;
  onLogout: () => Promise<void>;
}

function App({ accessToken, displayName, roles, userId, onLogout }: AppProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [drawer, setDrawer] = useState<DrawerName>(null);
  const [location, setLocation] = useState(window.location.pathname + window.location.search);
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
        ...(canBuild ? [{ key: '/data/connections', icon: <DatabaseOutlined />, label: '数据连接' }] : []),
        ...(canBuild ? [{ key: '/data/pipelines', icon: <BranchesOutlined />, label: '管道构建' }] : []),
        { key: '/data/quality', icon: <SafetyCertificateOutlined />, label: '数据质量', disabled: true },
        { key: '/data/lineage', icon: <PartitionOutlined />, label: '数据血缘', disabled: true },
      ],
    },
    { key: 'ontology-group', type: 'group', label: '本体', children: [{ key: '/ontology', icon: <DeploymentUnitOutlined />, label: '本体管理' }, { key: '/ontology/explorer', icon: <SearchOutlined />, label: '对象探索', disabled: true }] },
    { key: 'applications-group', type: 'group', label: '应用', children: [{ key: '/applications/dashboards', icon: <FundOutlined />, label: '分析看板', disabled: true }, { key: '/applications/business', icon: <AppstoreOutlined />, label: '业务应用', disabled: true }, { key: '/applications/automations', icon: <ThunderboltOutlined />, label: '自动化', disabled: true }, { key: '/applications/approvals', icon: <CheckCircleFilled />, label: '审批中心', disabled: true }] },
    { key: 'aip-group', type: 'group', label: 'AIP', children: [{ key: '/aip/agents', icon: <RobotOutlined />, label: '智能体工作室', disabled: true }, { key: '/aip/conversations', icon: <CommentOutlined />, label: '对话中心', disabled: true }] },
  ], [canBuild]);

  const userItems = useMemo<MenuProps['items']>(() => [
    { key: 'profile', label: '个人资料', icon: <UserOutlined /> },
    { key: 'settings', label: '偏好设置', icon: <SettingOutlined /> },
    { type: 'divider' },
    { key: 'logout', label: '退出登录', onClick: () => void onLogout() },
  ], [onLogout]);

  const path = window.location.pathname;
  const crumbs = path.startsWith('/ontology')
    ? ['本体', '本体管理', ontologyCrumb(path)]
    : path.startsWith('/data/pipelines')
    ? path.includes('/runs/') ? ['数据', '管道构建', '运行详情'] : path.endsWith('/new') ? ['数据', '管道构建', '新建管道'] : path.includes('/edit') ? ['数据', '管道构建', 'DAG 编辑器'] : ['数据', '管道构建']
    : path.includes('/new') ? ['数据', '数据连接', '新建连接'] : path.includes('/edit') ? ['数据', '数据连接', '编辑配置'] : path.match(/^\/data\/connections\/[^/]+/) ? ['数据', '数据连接', '连接详情'] : ['数据', '数据连接'];

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" collapsed={collapsed} collapsedWidth={72} trigger={null} width={238}>
        <div className="brand" aria-label="Ontology Platform"><div className="brand-mark">O</div>{!collapsed && <div><div className="brand-name">Ontology</div><div className="brand-caption">Platform</div></div>}</div>
        <Menu className="product-menu" mode="inline" items={navigation} selectedKeys={[path.startsWith('/ontology') ? '/ontology' : path.startsWith('/data/connections') ? '/data/connections' : path.startsWith('/data/pipelines') ? '/data/pipelines' : path]} onClick={({ key }) => navigate(key)} />
        <div className="sider-footer"><Divider /><Button aria-label="控制面板" className="control-button" disabled={!isAdmin} icon={<ControlOutlined />} type="text">{!collapsed && '控制面板'}</Button><Button aria-label={collapsed ? '展开导航' : '折叠导航'} className="collapse-button" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => setCollapsed((current) => !current)} type="text">{!collapsed && '收起导航'}</Button></div>
      </Sider>
      <Layout>
        <Header className="app-header"><Breadcrumb items={crumbs.map((title) => ({ title }))} /><Space size={8}><Button aria-label="全局搜索" icon={<SearchOutlined />} type="text"><span className="header-button-label">搜索</span></Button><Badge dot offset={[-7, 7]}><Button aria-label="通知" icon={<BellOutlined />} onClick={() => setDrawer('notifications')} type="text" /></Badge><Button aria-label="AIP 助手" className="assistant-button" icon={<BulbOutlined />} onClick={() => setDrawer('assistant')}>AIP 助手</Button><Dropdown menu={{ items: userItems }} placement="bottomRight" trigger={['click']}><Button aria-label="用户菜单" className="user-button" type="text"><Avatar size={32}>{displayName.slice(0, 1)}</Avatar><span className="header-button-label">{displayName}</span></Button></Dropdown></Space></Header>
        <Content className="app-content">{canBuild || path.startsWith('/ontology') ? <RouteContent accessToken={accessToken} canBuild={canBuild} displayName={displayName} isAdmin={isAdmin} location={location} navigate={navigate} userId={userId} /> : <Result extra={<Button onClick={() => void onLogout()}>切换账号</Button>} status="403" subTitle="Viewer 默认不能访问连接、资产 Schema、预览或运行记录。" title="无权访问数据连接" />}</Content>
      </Layout>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'notifications'} title="通知" width={420}><div className="drawer-empty"><NotificationOutlined /><Title level={4}>通知中心已准备</Title><Paragraph>资产变化、管道失败和质量告警将在相应阶段接入。</Paragraph></div></Drawer>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'assistant'} title="AIP 助手" width={480}><Tag color="geekblue">当前上下文：数据连接</Tag><div className="drawer-empty assistant-empty"><RobotOutlined /><Title level={4}>从问题开始</Title><Paragraph>打开助手不会自动调用模型。发送消息后才会创建临时 AssistSession。</Paragraph></div></Drawer>
    </Layout>
  );
}

function RouteContent({ accessToken, canBuild, displayName, isAdmin, location, navigate, userId }: { accessToken: string; canBuild: boolean; displayName: string; isAdmin: boolean; location: string; navigate: (path: string) => void; userId: string }) {
  const path = location.split('?')[0];
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

function ontologyRoute({ accessToken, canBuild, displayName, isAdmin, navigate, path, userId }: { accessToken: string; canBuild: boolean; displayName: string; isAdmin: boolean; navigate: (path: string) => void; path: string; userId: string }) {
  if (path === '/ontology' || path === '/ontology/modeling') return <OntologyOverviewPage accessToken={accessToken} canBuild={canBuild} navigate={navigate} />;
  if (path === '/ontology/search') return <OntologySearchPage accessToken={accessToken} navigate={navigate} />;
  if (path === '/ontology/properties') return <PropertyCatalogPage accessToken={accessToken} />;
  if (path === '/ontology/proposals') return <ProposalListPage accessToken={accessToken} canBuild={canBuild} navigate={navigate} />;
  const proposal = path.match(/^\/ontology\/proposals\/([^/]+)$/);
  if (proposal) return <ProposalDetailPage accessToken={accessToken} canBuild={canBuild} id={proposal[1]} isAdmin={isAdmin} navigate={navigate} />;
  if (path === '/ontology/health') return <OntologyHealthPage accessToken={accessToken} />;
  if (path === '/ontology/history') return <OntologyHistoryPage accessToken={accessToken} />;
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
  if (path.includes('/object-types')) return '对象类型';
  if (path.includes('/properties')) return '属性目录';
  if (path.includes('/link-types')) return '关系类型';
  if (path.includes('/interfaces')) return 'Interface';
  if (path.includes('/actions')) return 'Action';
  if (path.includes('/functions')) return 'Function';
  if (path.includes('/proposals')) return '变更提议';
  if (path.includes('/health')) return '健康问题';
  if (path.includes('/history')) return '变更历史';
  return '概览';
}

export default App;
