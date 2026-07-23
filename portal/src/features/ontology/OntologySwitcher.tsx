import { DeploymentUnitOutlined, DownOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Dropdown, Form, Input, Modal, Typography } from 'antd';
import type { MenuProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { activeOntologyId, setActiveOntologyId } from './ontologyContext';

interface OntologyCatalogItem {
  id: string;
  apiName: string;
  displayName: string;
  description: string;
  color: string;
  objectTypeCount: number;
  linkTypeCount: number;
}

interface CreateOntologyValues {
  apiName: string;
  color: string;
  description?: string;
  displayName: string;
}

export default function OntologySwitcher({ accessToken, canCreate, collapsed, onChanged }: { accessToken: string; canCreate: boolean; collapsed: boolean; onChanged: () => void }) {
  const [items, setItems] = useState<OntologyCatalogItem[]>([]);
  const [value, setValue] = useState(activeOntologyId());
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const [form] = Form.useForm<CreateOntologyValues>();

  const load = useCallback(async () => {
    setLoading(true);
    setLoadFailed(false);
    try {
      const response = await fetch('/api/ontology/v1/ontologies', { headers: { Authorization: `Bearer ${accessToken}` } });
      if (!response.ok) throw new Error('本体目录加载失败');
      const catalog = await response.json() as OntologyCatalogItem[];
      setItems(catalog);
      if (!catalog.some((item) => item.id === activeOntologyId()) && catalog[0]) {
        setActiveOntologyId(catalog[0].id);
        setValue(catalog[0].id);
        onChanged();
      }
    } catch {
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [accessToken, onChanged]);

  useEffect(() => { void load(); }, [load]);

  const active = items.find((item) => item.id === value);
  const menuItems = useMemo<MenuProps['items']>(() => [
    ...items.map((item) => ({
      key: `ontology:${item.id}`,
      label: <div className="ontology-option"><span className="ontology-color" style={{ background: item.color }} /><div><strong>{item.displayName}</strong><small>{item.objectTypeCount} 个对象类型 · {item.linkTypeCount} 个关系</small></div></div>,
    })),
    ...(canCreate ? [{ type: 'divider' as const }, { key: 'create', icon: <PlusOutlined />, label: '新建本体' }] : []),
  ], [canCreate, items]);

  const choose: MenuProps['onClick'] = ({ key }) => {
    if (key === 'create') {
      setCreateError('');
      form.setFieldsValue({ color: '#3157d5' });
      setCreateOpen(true);
      return;
    }
    const id = key.slice('ontology:'.length);
    if (id === value) return;
    setActiveOntologyId(id);
    setValue(id);
    onChanged();
  };

  const create = async () => {
    const values = await form.validateFields();
    setCreating(true);
    setCreateError('');
    try {
      const response = await fetch('/api/ontology/v1/ontologies', {
        method: 'POST',
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string };
        throw new Error(problem.detail ?? problem.message ?? '本体创建失败');
      }
      const created = await response.json() as OntologyCatalogItem;
      setItems((current) => [...current, created].sort((left, right) => left.displayName.localeCompare(right.displayName, 'zh-CN')));
      setActiveOntologyId(created.id);
      setValue(created.id);
      setCreateOpen(false);
      form.resetFields();
      onChanged();
    } catch (cause) {
      setCreateError(cause instanceof Error ? cause.message : '本体创建失败');
    } finally {
      setCreating(false);
    }
  };

  const label = loading ? '加载中…' : loadFailed ? '加载失败' : active?.displayName ?? '选择本体';
  return <>
    <Dropdown menu={{ items: menuItems, onClick: choose }} placement="bottomLeft" trigger={['click']}>
      <Button
        aria-label={`切换本体，当前${label}`}
        className={collapsed ? 'ontology-switcher-trigger collapsed' : 'ontology-switcher-trigger'}
        icon={collapsed ? <DeploymentUnitOutlined /> : undefined}
        loading={loading}
        title={label}
      >
        {!collapsed && <><span className="ontology-color" style={{ background: active?.color ?? '#8c95a8' }} /><span className="ontology-switcher-label">{label}</span><DownOutlined className="ontology-switcher-arrow" /></>}
      </Button>
    </Dropdown>
    <Modal cancelText="取消" confirmLoading={creating} okText="创建并切换" onCancel={() => setCreateOpen(false)} onOk={() => void create()} open={createOpen} title="新建本体">
      <Typography.Paragraph type="secondary">新本体是独立业务场景，拥有自己的数据连接、管道、Dataset、对象类型、对象实例和看板。</Typography.Paragraph>
      {createError && <Typography.Paragraph type="danger">{createError}</Typography.Paragraph>}
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item label="本体名称" name="displayName" rules={[{ required: true, message: '请输入本体名称' }, { max: 240 }]}><Input autoFocus placeholder="例如：供应链运营" /></Form.Item>
        <Form.Item extra="创建后不可与已有本体重复" label="API 名称" name="apiName" rules={[{ required: true, message: '请输入 API 名称' }, { pattern: /^[a-z][a-z0-9_]{1,159}$/, message: '使用小写字母、数字和下划线，并以字母开头' }]}><Input placeholder="例如：supply_chain" /></Form.Item>
        <Form.Item label="场景说明" name="description" rules={[{ max: 1000 }]}><Input.TextArea placeholder="说明该本体覆盖的业务范围" rows={3} /></Form.Item>
        <Form.Item initialValue="#3157d5" label="标识颜色" name="color" rules={[{ required: true }]}><Input className="ontology-color-input" type="color" /></Form.Item>
      </Form>
    </Modal>
  </>;
}
