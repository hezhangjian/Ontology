import { CopyOutlined, EditOutlined, EyeOutlined, FolderOpenOutlined, PlusOutlined, StarFilled, StarOutlined } from '@ant-design/icons';
import { Button, Dropdown, Form, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { DashboardApi } from '../services/dashboardApi';
import type { DashboardSummary } from '../types';

export default function DashboardListPage({ accessToken, canBuild, navigate }: { accessToken: string; canBuild: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => new DashboardApi(accessToken), [accessToken]);
  const [items, setItems] = useState<DashboardSummary[]>([]);
  const [keyword, setKeyword] = useState('');
  const [lifecycle, setLifecycle] = useState('');
  const [favorites, setFavorites] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [toast, context] = message.useMessage();
  const load = useCallback(() => api.list(keyword, lifecycle, favorites).then(setItems).catch((error: Error) => void toast.error(error.message)), [api, favorites, keyword, lifecycle, toast]);
  useEffect(() => { void load(); }, [load]);
  const create = async () => { const values = await form.validateFields(); setBusy(true); try { const value = await api.create(values); setOpen(false); form.resetFields(); navigate(`/apps/dashboards/${value.summary.id}/edit`); } catch (error) { void toast.error((error as Error).message); } finally { setBusy(false); } };
  const favorite = async (item: DashboardSummary) => { await api.favorite(item.id, !item.favorite); await load(); };
  const copy = async (item: DashboardSummary) => { const value = await api.copy(item.id); void toast.success('已创建私有副本'); navigate(`/apps/dashboards/${value.summary.id}/edit`); };
  const archive = async (item: DashboardSummary) => { if (item.lifecycle === 'ARCHIVED') await api.restore(item.id); else await api.archive(item.id); await load(); };
  return <div className="dashboard-list-page">{context}<div className="page-heading"><div><Typography.Title level={3}>分析看板</Typography.Title><Typography.Text type="secondary">以当前用户权限运行的只读对象分析产品</Typography.Text></div>{canBuild && <Button icon={<PlusOutlined />} onClick={() => setOpen(true)} type="primary">新建看板</Button>}</div>
    <div className="filter-row"><Input.Search allowClear onSearch={() => void load()} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索名称或说明" value={keyword} /><Select allowClear onChange={(value) => setLifecycle(value ?? '')} options={['DRAFT','READY','PUBLISHED','VALIDATION_FAILED','PUBLISH_FAILED','ARCHIVED'].map((value) => ({ value, label: value }))} placeholder="状态" value={lifecycle || undefined} /><Button icon={favorites ? <StarFilled /> : <StarOutlined />} onClick={() => setFavorites((value) => !value)}>{favorites ? '全部看板' : '仅看收藏'}</Button></div>
    <Table dataSource={items} pagination={{ pageSize: 20 }} rowKey="id" size="small" columns={[
      { title: '名称', dataIndex: 'name', render: (value: string, item: DashboardSummary) => <button className="link-button" onClick={() => navigate(`/apps/dashboards/${item.id}/view`)}>{value}</button> },
      { title: '状态', dataIndex: 'lifecycle', render: (value: string) => <Tag color={statusColor(value)}>{value}</Tag> },
      { title: '版本', dataIndex: 'currentVersion', render: (value?: number) => value ? `v${value}` : '—' },
      { title: '页面 / 组件', render: (_: unknown, item: DashboardSummary) => `${item.pageCount} / ${item.widgetCount}` },
      { title: '负责人', dataIndex: 'ownerName' }, { title: '可见范围', dataIndex: 'visibility' }, { title: '刷新', dataIndex: 'refreshPolicy' },
      { title: '健康', dataIndex: 'healthStatus', render: (value: string) => <Tag color={value === 'HEALTHY' ? 'green' : value === 'ERROR' ? 'red' : 'gold'}>{value}</Tag> },
      { title: '最近发布', dataIndex: 'lastPublishedAt', render: (value?: string) => value ? new Date(value).toLocaleString() : '—' },
      { title: '', width: 130, render: (_: unknown, item: DashboardSummary) => <Space><Tooltip title={item.favorite ? '取消收藏' : '收藏'}><Button aria-label={item.favorite ? '取消收藏' : '收藏'} icon={item.favorite ? <StarFilled /> : <StarOutlined />} onClick={() => void favorite(item)} size="small" type="text" /></Tooltip><Dropdown menu={{ items: [{ key: 'view', icon: <EyeOutlined />, label: '打开' }, ...(canBuild ? [{ key: 'edit', icon: <EditOutlined />, label: '编辑' }, { key: 'copy', icon: <CopyOutlined />, label: '复制' }] : []), { key: 'versions', icon: <FolderOpenOutlined />, label: '版本' }, ...(canBuild ? [{ key: 'archive', label: item.lifecycle === 'ARCHIVED' ? '恢复' : '归档' }] : [])], onClick: ({ key }) => { if (key === 'view') navigate(`/apps/dashboards/${item.id}/view`); if (key === 'edit') navigate(`/apps/dashboards/${item.id}/edit`); if (key === 'copy') void copy(item); if (key === 'versions') navigate(`/apps/dashboards/${item.id}/versions`); if (key === 'archive') void archive(item); } }}><Button size="small">操作</Button></Dropdown></Space> },
    ]} />
    <Modal confirmLoading={busy} onCancel={() => setOpen(false)} onOk={() => void create()} open={open} title="新建分析看板"><Form form={form} initialValues={{ visibility: 'PRIVATE', refreshPolicy: 'MANUAL' }} layout="vertical"><Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item><Form.Item label="说明" name="description"><Input.TextArea rows={3} /></Form.Item><Form.Item label="可见范围" name="visibility"><Select options={[{value:'PRIVATE',label:'私有'},{value:'TEAM',label:'团队'},{value:'ORGANIZATION',label:'组织内'}]} /></Form.Item><Form.Item label="刷新策略" name="refreshPolicy"><Select options={[{value:'MANUAL',label:'手动'},{value:'1_MIN',label:'1 分钟'},{value:'5_MIN',label:'5 分钟'},{value:'15_MIN',label:'15 分钟'},{value:'60_MIN',label:'60 分钟'}]} /></Form.Item></Form></Modal>
  </div>;
}

function statusColor(value: string) { if (value === 'PUBLISHED') return 'green'; if (value.includes('FAILED')) return 'red'; if (value === 'READY') return 'blue'; if (value === 'ARCHIVED') return 'default'; return 'gold'; }
