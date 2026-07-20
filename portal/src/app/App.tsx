import { useMemo, useState } from 'react';
import {
  AppstoreOutlined,
  BellOutlined,
  BranchesOutlined,
  BulbOutlined,
  CheckCircleFilled,
  CloudServerOutlined,
  CodeSandboxOutlined,
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
  SearchOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Card,
  Col,
  Divider,
  Drawer,
  Dropdown,
  Flex,
  Input,
  Layout,
  Menu,
  Row,
  Space,
  Tag,
  Typography,
} from 'antd';
import type { MenuProps } from 'antd';

const { Content, Header, Sider } = Layout;
const { Paragraph, Text, Title } = Typography;

type DrawerName = 'assistant' | 'notifications' | null;

interface AppProps {
  displayName: string;
  onLogout: () => Promise<void>;
}

const navigation: MenuProps['items'] = [
  {
    key: 'data-group',
    type: 'group',
    label: '数据',
    children: [
      { key: '/data/connections', icon: <DatabaseOutlined />, label: '数据连接' },
      { key: '/data/pipelines', icon: <BranchesOutlined />, label: '管道构建', disabled: true },
      { key: '/data/quality', icon: <SafetyCertificateOutlined />, label: '数据质量', disabled: true },
      { key: '/data/lineage', icon: <PartitionOutlined />, label: '数据血缘', disabled: true },
    ],
  },
  {
    key: 'ontology-group',
    type: 'group',
    label: '本体',
    children: [
      { key: '/ontology/modeling', icon: <DeploymentUnitOutlined />, label: '本体管理', disabled: true },
      { key: '/ontology/explorer', icon: <SearchOutlined />, label: '对象探索', disabled: true },
    ],
  },
  {
    key: 'applications-group',
    type: 'group',
    label: '应用',
    children: [
      { key: '/applications/dashboards', icon: <FundOutlined />, label: '分析看板', disabled: true },
      { key: '/applications/business', icon: <AppstoreOutlined />, label: '业务应用', disabled: true },
      { key: '/applications/automations', icon: <ThunderboltOutlined />, label: '自动化', disabled: true },
      { key: '/applications/approvals', icon: <CheckCircleFilled />, label: '审批中心', disabled: true },
    ],
  },
  {
    key: 'aip-group',
    type: 'group',
    label: 'AIP',
    children: [
      { key: '/aip/agents', icon: <RobotOutlined />, label: '智能体工作室', disabled: true },
      { key: '/aip/conversations', icon: <CommentOutlined />, label: '对话中心', disabled: true },
    ],
  },
];

const phaseCards = [
  { label: '实施阶段', value: 'P01 / P17', detail: '认证平台基座已启用', icon: <CodeSandboxOutlined /> },
  { label: '后端模块', value: '8', detail: 'Java 21 Maven 多模块', icon: <CloudServerOutlined /> },
  { label: '产品页面', value: '13', detail: '按 vertical slice 交付', icon: <AppstoreOutlined /> },
  { label: '认证基线', value: 'OIDC', detail: 'Keycloak + Authorization Code PKCE', icon: <SafetyCertificateOutlined /> },
];

function App({ displayName, onLogout }: AppProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [drawer, setDrawer] = useState<DrawerName>(null);
  const [selectedKey, setSelectedKey] = useState('/data/connections');

  const userItems = useMemo<MenuProps['items']>(
    () => [
      { key: 'profile', label: '个人资料', icon: <UserOutlined /> },
      { key: 'settings', label: '偏好设置', icon: <SettingOutlined /> },
      { type: 'divider' },
      { key: 'logout', label: '退出登录', onClick: () => void onLogout() },
    ],
    [onLogout],
  );

  const selectNavigation: MenuProps['onClick'] = ({ key }) => {
    setSelectedKey(key);
    window.history.replaceState({}, '', key);
  };

  return (
    <Layout className="app-shell">
      <Sider
        className="app-sider"
        collapsed={collapsed}
        collapsedWidth={72}
        trigger={null}
        width={238}
      >
        <div className="brand" aria-label="Ontology Platform">
          <div className="brand-mark">O</div>
          {!collapsed && (
            <div>
              <div className="brand-name">Ontology</div>
              <div className="brand-caption">Platform</div>
            </div>
          )}
        </div>
        <Menu
          className="product-menu"
          mode="inline"
          items={navigation}
          selectedKeys={[selectedKey]}
          onClick={selectNavigation}
        />
        <div className="sider-footer">
          <Divider />
          <Button
            aria-label="控制面板"
            className="control-button"
            disabled
            icon={<ControlOutlined />}
            type="text"
          >
            {!collapsed && '控制面板'}
          </Button>
          <Button
            aria-label={collapsed ? '展开导航' : '折叠导航'}
            className="collapse-button"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((current) => !current)}
            type="text"
          >
            {!collapsed && '收起导航'}
          </Button>
        </div>
      </Sider>

      <Layout>
        <Header className="app-header">
          <Breadcrumb items={[{ title: '数据' }, { title: '数据连接' }]} />
          <Space size={8}>
            <Button aria-label="全局搜索" icon={<SearchOutlined />} type="text">
              <span className="header-button-label">搜索</span>
            </Button>
            <Badge dot offset={[-7, 7]}>
              <Button
                aria-label="通知"
                icon={<BellOutlined />}
                onClick={() => setDrawer('notifications')}
                type="text"
              />
            </Badge>
            <Button
              aria-label="AIP 助手"
              className="assistant-button"
              icon={<BulbOutlined />}
              onClick={() => setDrawer('assistant')}
            >
              AIP 助手
            </Button>
            <Dropdown menu={{ items: userItems }} placement="bottomRight" trigger={['click']}>
              <Button aria-label="用户菜单" className="user-button" type="text">
                <Avatar size={32}>管</Avatar>
                <span className="header-button-label">{displayName}</span>
              </Button>
            </Dropdown>
          </Space>
        </Header>

        <Content className="app-content">
          <div className="content-heading">
            <div>
              <Space size={10}>
                <Title level={2}>数据连接</Title>
                <Tag color="blue">P01 认证基座</Tag>
              </Space>
              <Paragraph>
                连接、发现并管理平台的数据源。完整数据连接能力将在 P03 vertical slice 中交付。
              </Paragraph>
            </div>
            <Button disabled type="primary">
              新建连接
            </Button>
          </div>

          <Row gutter={[16, 16]}>
            {phaseCards.map((card) => (
              <Col key={card.label} lg={6} md={12} xs={24}>
                <Card className="metric-card" variant="borderless">
                  <Flex align="flex-start" justify="space-between">
                    <div>
                      <Text type="secondary">{card.label}</Text>
                      <div className="metric-value">{card.value}</div>
                      <Text type="secondary">{card.detail}</Text>
                    </div>
                    <div className="metric-icon">{card.icon}</div>
                  </Flex>
                </Card>
              </Col>
            ))}
          </Row>

          <Card className="baseline-card" variant="borderless">
            <Flex align="center" gap={16}>
              <div className="baseline-icon">
                <CheckCircleFilled />
              </div>
              <div>
                <Title level={4}>认证 Compose 基座</Title>
                <Paragraph>
                  默认入口已由 Keycloak PKCE 保护；平台组件使用固定镜像、私有网络、健康依赖和幂等 bootstrap。
                </Paragraph>
              </div>
            </Flex>
            <Divider />
            <div className="architecture-flow" aria-label="平台数据流">
              {['数据源', 'Flink', 'Pulsar', 'Projection', 'HugeGraph / OpenSearch'].map((step, index) => (
                <div className="flow-step" key={step}>
                  <span className="flow-index">{index + 1}</span>
                  <span>{step}</span>
                </div>
              ))}
            </div>
          </Card>
        </Content>
      </Layout>

      <Drawer
        onClose={() => setDrawer(null)}
        open={drawer === 'notifications'}
        title="通知"
        width={420}
      >
        <div className="drawer-empty">
          <NotificationOutlined />
          <Title level={4}>通知中心已准备</Title>
          <Paragraph>审批、质量告警、管道失败和系统通知将在对应阶段接入。</Paragraph>
        </div>
      </Drawer>

      <Drawer
        onClose={() => setDrawer(null)}
        open={drawer === 'assistant'}
        title="AIP 助手"
        width={480}
      >
        <Tag color="geekblue">当前上下文：数据连接</Tag>
        <div className="drawer-empty assistant-empty">
          <RobotOutlined />
          <Title level={4}>从问题开始</Title>
          <Paragraph>打开助手不会自动调用模型。发送消息后才会创建临时 AssistSession。</Paragraph>
        </div>
        <Input.TextArea disabled placeholder="AIP Runtime 将在 P13 接入" rows={4} />
      </Drawer>
    </Layout>
  );
}

export default App;
