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
        { key: '/data/pipelines', icon: <BranchesOutlined />, label: '管道构建', disabled: true },
        { key: '/data/quality', icon: <SafetyCertificateOutlined />, label: '数据质量', disabled: true },
        { key: '/data/lineage', icon: <PartitionOutlined />, label: '数据血缘', disabled: true },
      ],
    },
    { key: 'ontology-group', type: 'group', label: '本体', children: [{ key: '/ontology/modeling', icon: <DeploymentUnitOutlined />, label: '本体管理', disabled: true }, { key: '/ontology/explorer', icon: <SearchOutlined />, label: '对象探索', disabled: true }] },
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
  const crumbs = path.includes('/new') ? ['数据', '数据连接', '新建连接'] : path.includes('/edit') ? ['数据', '数据连接', '编辑配置'] : path.match(/^\/data\/connections\/[^/]+/) ? ['数据', '数据连接', '连接详情'] : ['数据', '数据连接'];

  return (
    <Layout className="app-shell">
      <Sider className="app-sider" collapsed={collapsed} collapsedWidth={72} trigger={null} width={238}>
        <div className="brand" aria-label="Ontology Platform"><div className="brand-mark">O</div>{!collapsed && <div><div className="brand-name">Ontology</div><div className="brand-caption">Platform</div></div>}</div>
        <Menu className="product-menu" mode="inline" items={navigation} selectedKeys={[path.startsWith('/data/connections') ? '/data/connections' : path]} onClick={({ key }) => navigate(key)} />
        <div className="sider-footer"><Divider /><Button aria-label="控制面板" className="control-button" disabled={!isAdmin} icon={<ControlOutlined />} type="text">{!collapsed && '控制面板'}</Button><Button aria-label={collapsed ? '展开导航' : '折叠导航'} className="collapse-button" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => setCollapsed((current) => !current)} type="text">{!collapsed && '收起导航'}</Button></div>
      </Sider>
      <Layout>
        <Header className="app-header"><Breadcrumb items={crumbs.map((title) => ({ title }))} /><Space size={8}><Button aria-label="全局搜索" icon={<SearchOutlined />} type="text"><span className="header-button-label">搜索</span></Button><Badge dot offset={[-7, 7]}><Button aria-label="通知" icon={<BellOutlined />} onClick={() => setDrawer('notifications')} type="text" /></Badge><Button aria-label="AIP 助手" className="assistant-button" icon={<BulbOutlined />} onClick={() => setDrawer('assistant')}>AIP 助手</Button><Dropdown menu={{ items: userItems }} placement="bottomRight" trigger={['click']}><Button aria-label="用户菜单" className="user-button" type="text"><Avatar size={32}>{displayName.slice(0, 1)}</Avatar><span className="header-button-label">{displayName}</span></Button></Dropdown></Space></Header>
        <Content className="app-content">{canBuild ? <RouteContent accessToken={accessToken} displayName={displayName} isAdmin={isAdmin} location={location} navigate={navigate} userId={userId} /> : <Result extra={<Button onClick={() => void onLogout()}>切换账号</Button>} status="403" subTitle="Viewer 默认不能访问连接、资产 Schema、预览或运行记录。" title="无权访问数据连接" />}</Content>
      </Layout>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'notifications'} title="通知" width={420}><div className="drawer-empty"><NotificationOutlined /><Title level={4}>通知中心已准备</Title><Paragraph>资产变化、管道失败和质量告警将在相应阶段接入。</Paragraph></div></Drawer>
      <Drawer onClose={() => setDrawer(null)} open={drawer === 'assistant'} title="AIP 助手" width={480}><Tag color="geekblue">当前上下文：数据连接</Tag><div className="drawer-empty assistant-empty"><RobotOutlined /><Title level={4}>从问题开始</Title><Paragraph>打开助手不会自动调用模型。发送消息后才会创建临时 AssistSession。</Paragraph></div></Drawer>
    </Layout>
  );
}

function RouteContent({ accessToken, displayName, isAdmin, location, navigate, userId }: { accessToken: string; displayName: string; isAdmin: boolean; location: string; navigate: (path: string) => void; userId: string }) {
  const path = location.split('?')[0];
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

export default App;
