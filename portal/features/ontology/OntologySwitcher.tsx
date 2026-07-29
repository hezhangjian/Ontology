import { DeploymentUnitOutlined, DownOutlined, PlusOutlined, SettingOutlined } from '@/shared/icons';
import { Button } from '@/shared/components/actions';
import { Form, Input } from '@/shared/components/forms';
import type { MenuProps } from '@/shared/components/navigation';
import { Dropdown, Modal } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { activeOntologyId, onActiveOntologyChanged, setActiveOntologyId } from './ontologyContext';

interface OntologyCatalogItem {
  id: string;
  name: string;
  health: 'DEGRADED' | 'FAILED' | 'HEALTHY';
  role: string;
}

interface CreateOntologyValues {
  description?: string;
  id: string;
  name: string;
}

interface OntologyDetail extends OntologyCatalogItem {
  description: string;
  etag: string;
}

export default function OntologySwitcher({ collapsed, onChanged }: { collapsed: boolean; onChanged: () => void }) {
  const [items, setItems] = useState<OntologyCatalogItem[]>([]);
  const [value, setValue] = useState(activeOntologyId());
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsError, setSettingsError] = useState('');
  const [settingsEtag, setSettingsEtag] = useState('');
  const [form] = Form.useForm<CreateOntologyValues>();
  const [settingsForm] = Form.useForm<CreateOntologyValues>();

  const load = useCallback(async () => {
    setLoading(true);
    setLoadFailed(false);
    try {
      const response = await fetch('/v1/ontologies');
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
  }, [ onChanged]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => onActiveOntologyChanged(setValue), []);

  const active = items.find((item) => item.id === value);
  const menuItems = useMemo<MenuProps['items']>(() => [
    ...items.map((item) => ({
      key: `ontology:${item.id}`,
      label: <div className="ontology-option"><span className="ontology-color" style={{ background: colorFor(item.id) }} /><div><strong>{item.name}</strong><small>{item.id} · {item.health}</small></div></div>,
    })),
    { type: 'divider' as const },
    { key: 'create', icon: <PlusOutlined />, label: '新建本体' },
    { key: 'settings', icon: <SettingOutlined />, label: '当前本体设置' },
  ], [items]);

  const choose: MenuProps['onClick'] = ({ key }) => {
    if (key === 'create') {
      setCreateError('');
      setCreateOpen(true);
      return;
    }
    if (key === 'settings') {
      setSettingsError('');
      setSettingsLoading(true);
      setSettingsOpen(true);
      void fetch(`/v1/ontologies/${value}`)
        .then(async (response) => {
          if (!response.ok) throw new Error('本体设置加载失败');
          const detail = await response.json() as OntologyDetail;
          setSettingsEtag(response.headers.get('ETag')?.replaceAll('"', '') ?? detail.etag);
          settingsForm.setFieldsValue({ description: detail.description, id: detail.id, name: detail.name });
        })
        .catch((cause: Error) => setSettingsError(cause.message))
        .finally(() => setSettingsLoading(false));
      return;
    }
    const id = key.slice('ontology:'.length);
    if (id === value) return;
    setActiveOntologyId(id);
    setValue(id);
    onChanged();
  };

  const saveSettings = async () => {
    const values = await settingsForm.validateFields();
    setSettingsLoading(true);
    setSettingsError('');
    try {
      const response = await fetch(`/v1/ontologies/${value}`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          'If-Match': settingsEtag,
        },
        body: JSON.stringify(values),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => ({})) as { detail?: string };
        throw new Error(problem.detail ?? '本体设置保存失败');
      }
      const updated = await response.json() as OntologyDetail;
      setItems((current) => current
        .map((item) => item.id === value ? { ...item, id: updated.id, name: updated.name } : item)
        .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')));
      setActiveOntologyId(updated.id);
      setValue(updated.id);
      setSettingsOpen(false);
      onChanged();
    } catch (cause) {
      setSettingsError(cause instanceof Error ? cause.message : '本体设置保存失败');
    } finally {
      setSettingsLoading(false);
    }
  };

  const create = async () => {
    const values = await form.validateFields();
    setCreating(true);
    setCreateError('');
    try {
      const response = await fetch('/v1/ontologies', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => ({})) as { detail?: string; message?: string };
        throw new Error(problem.detail ?? problem.message ?? '本体创建失败');
      }
      const created = await response.json() as OntologyCatalogItem;
      setItems((current) => [...current, created].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')));
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

  const label = loading ? '加载中…' : loadFailed ? '加载失败' : active?.name ?? '选择本体';
  return <>
    <Dropdown menu={{ items: menuItems, onClick: choose }} placement="bottomLeft" trigger={['click']}>
      <Button
        aria-label={`切换本体，当前${label}`}
        className={collapsed ? 'ontology-switcher-trigger collapsed' : 'ontology-switcher-trigger'}
        icon={collapsed ? <DeploymentUnitOutlined /> : undefined}
        loading={loading}
        title={label}
      >
        {!collapsed && <><span className="ontology-color" style={{ background: active ? colorFor(active.id) : '#8c95a8' }} /><span className="ontology-switcher-label">{label}</span><DownOutlined className="ontology-switcher-arrow" /></>}
      </Button>
    </Dropdown>
    <Modal cancelText="取消" confirmLoading={creating} okText="创建并切换" onCancel={() => setCreateOpen(false)} onOk={() => void create()} open={createOpen} title="新建本体">
      <Typography.Paragraph type="secondary">新本体是独立业务场景，拥有自己的数据连接、管道、Dataset、对象类型、对象实例和看板。</Typography.Paragraph>
      {createError && <Typography.Paragraph type="danger">{createError}</Typography.Paragraph>}
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item label="本体名称" name="name" rules={[{ required: true, message: '请输入本体名称' }, { max: 240 }]}><Input autoFocus placeholder="例如：供应链运营" /></Form.Item>
        <Form.Item extra="用于接口路径和资源引用，创建后不可修改" label="本体 ID" name="id" rules={[{ required: true, message: '请输入本体 ID' }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{0,159}$/, message: '以英文字母开头，只能包含字母、数字、下划线和连字符' }]}><Input placeholder="例如：supply-chain" /></Form.Item>
        <Form.Item label="场景说明" name="description" rules={[{ max: 1000 }]}><Input.TextArea placeholder="说明该本体覆盖的业务范围" rows={3} /></Form.Item>
      </Form>
    </Modal>
    <Modal
      cancelText="取消"
      className="ontology-settings-modal"
      confirmLoading={settingsLoading}
      okText="保存设置"
      onCancel={() => setSettingsOpen(false)}
      onOk={() => void saveSettings()}
      open={settingsOpen}
      title="当前本体设置"
    >
      <Typography.Paragraph type="secondary">名称面向用户展示；ID 用于页面地址、接口路径和资源引用。</Typography.Paragraph>
      {settingsError && <Typography.Paragraph type="danger">{settingsError}</Typography.Paragraph>}
      <Form form={settingsForm} layout="vertical" requiredMark="optional">
        <Form.Item label="本体名称" name="name" rules={[{ required: true, message: '请输入本体名称' }, { max: 240 }]}><Input disabled={settingsLoading} /></Form.Item>
        <Form.Item extra="修改后页面地址和 API 路径会同步使用新 ID" label="本体 ID" name="id" rules={[{ required: true, message: '请输入本体 ID' }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{0,159}$/, message: '以英文字母开头，只能包含字母、数字、下划线和连字符' }]}><Input disabled={settingsLoading} /></Form.Item>
        <Form.Item label="场景说明" name="description" rules={[{ max: 4000 }]}><Input.TextArea disabled={settingsLoading} rows={4} /></Form.Item>
      </Form>
    </Modal>
  </>;
}

function colorFor(id: string) {
  const colors = ['#3157d5', '#0f8f6f', '#a45a00', '#7b4bb7', '#b83260'];
  const hash = [...id].reduce((value, character) => value + character.charCodeAt(0), 0);
  return colors[hash % colors.length];
}
