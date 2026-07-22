import { DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Dropdown, Form, Input, Modal, Table, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { DashboardApi } from '../services/dashboardApi';
import type { DashboardSummary } from '../types';

export default function DashboardListPage({ accessToken, canBuild, navigate }: { accessToken: string; canBuild: boolean; navigate: (path: string) => void }) {
  const api = useMemo(() => new DashboardApi(accessToken), [accessToken]);
  const [items, setItems] = useState<DashboardSummary[]>([]);
  const [keyword, setKeyword] = useState('');
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [toast, context] = message.useMessage();
  const load = useCallback(() => api.list(keyword, '', false).then(setItems).catch((error: Error) => void toast.error(error.message)), [api, keyword, toast]);
  useEffect(() => { void load(); }, [load]);
  const create = async () => { const values = await form.validateFields(); setBusy(true); try { const value = await api.create(values); setOpen(false); form.resetFields(); navigate(`/apps/dashboards/${value.summary.id}/edit`); } catch (error) { void toast.error((error as Error).message); } finally { setBusy(false); } };
  const remove = (item: DashboardSummary) => Modal.confirm({ title: `删除“${item.name}”？`, content: '看板和相关运行记录会一起删除。', okButtonProps: { danger: true }, okText: '删除', onOk: async () => { await api.delete(item.id); void toast.success('看板已删除'); await load(); } });
  return <div className="dashboard-list-page">{context}<div className="page-title-row"><div><Typography.Title level={2}>分析看板</Typography.Title><Typography.Paragraph>新建看板后，自行选择 Dataset 或业务对象、图表类型、维度和指标。</Typography.Paragraph></div>{canBuild && <Button icon={<PlusOutlined />} onClick={() => setOpen(true)} type="primary">新建看板</Button>}</div>
    <div className="filter-row"><Input.Search allowClear onSearch={() => void load()} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索看板" value={keyword} /></div>
    <Table dataSource={items} pagination={{ pageSize: 20 }} rowKey="id" size="small" columns={[
      { title: '名称', dataIndex: 'name', render: (value: string, item: DashboardSummary) => <button className="link-button" onClick={() => navigate(`/apps/dashboards/${item.id}/view`)}>{value}</button> },
      { title: '图表数', dataIndex: 'widgetCount' },
      { title: '更新时间', dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString() },
      { title: '', width: 110, render: (_: unknown, item: DashboardSummary) => <Dropdown menu={{ items: [{ key: 'view', icon: <EyeOutlined />, label: '打开' }, { key: 'edit', icon: <EditOutlined />, label: '编辑' }, { key: 'delete', danger: true, icon: <DeleteOutlined />, label: '删除' }], onClick: ({ key }) => { if (key === 'view') navigate(`/apps/dashboards/${item.id}/view`); if (key === 'edit') navigate(`/apps/dashboards/${item.id}/edit`); if (key === 'delete') remove(item); } }}><Button size="small">操作</Button></Dropdown> },
    ]} />
    <Modal confirmLoading={busy} onCancel={() => setOpen(false)} onOk={() => void create()} open={open} okText="创建并开始画图" title="新建看板"><Form form={form} initialValues={{ visibility: 'ORGANIZATION', refreshPolicy: 'MANUAL' }} layout="vertical"><Form.Item label="看板名称" name="name" rules={[{ required: true, message: '请输入名称' }]}><Input placeholder="例如：月度经营概览" /></Form.Item><Form.Item label="说明（可选）" name="description"><Input.TextArea rows={2} /></Form.Item><Form.Item hidden name="visibility"><Input /></Form.Item><Form.Item hidden name="refreshPolicy"><Input /></Form.Item></Form></Modal>
  </div>;
}
