/* eslint-disable react-hooks/exhaustive-deps */
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Table } from '@/shared/components/data';
import { Empty, message } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Space } from '@/shared/components/layout';
import { Modal } from '@/shared/components/overlays';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useState } from 'react';
import { modelingApi } from './ontology.service';
import type { OntologyResource, ResourceKind } from './ontology.types';
import { resourcePath } from './OntologyOverviewPage';

const { Paragraph, Title } = Typography;
const labels: Record<ResourceKind, [string, string]> = {
  ACTION: ['Action', '用声明式规则定义允许发生的业务改变。'],
  FUNCTION: ['Function', '构建类型化、只读的可信计算。'],
  INTERFACE: ['Interface', '用显式属性槽位统一跨对象类型查询。'],
  LINK_TYPE: ['关系类型', '定义可从两侧遍历的稳定关系资源。'],
  OBJECT_TYPE: ['对象类型', '定义业务对象身份、属性、映射和投影策略。'],
};

export default function ResourceListPage({ kind, navigate }: { kind: ResourceKind; navigate: (path: string) => void }) {
  const api = useMemo(() => modelingApi(), []);
  const [items, setItems] = useState<OntologyResource[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const load = () => {
    setLoading(true);
    api.listResources(kind, search).then(setItems).finally(() => setLoading(false));
  };
  const remove = (resource: OntologyResource) => {
    return Modal.confirm({
      title: `永久删除“${resource.displayName}”？`,
      content: '资源、映射、实例及关系数据将永久删除。',
      okButtonProps: { danger: true },
      okText: '永久删除',
      onOk: async () => {
        await api.deleteResource(kind, resource.id);
        message.success('本体资源已删除');
        load();
      },
    });
  };
  useEffect(() => { void load(); }, [kind, search]);
  const newPath = `/ontology/${resourcePath(kind, '').split('/')[2]}/new`;
  return <div>
    <div className="page-title-row">
      <div><Title level={2}>{labels[kind][0]}</Title><Paragraph>{labels[kind][1]}</Paragraph></div>
      <Space>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        {kind === 'OBJECT_TYPE' && <Button onClick={() => navigate('/ontology/object-types/new/from-dataset')}>从 Dataset 创建</Button>}
        <Button icon={<PlusOutlined />} onClick={() => navigate(newPath)} type="primary">{kind === 'OBJECT_TYPE' ? '手工创建空对象' : `新建${labels[kind][0]}`}</Button>
      </Space>
    </div>
    <div className="table-toolbar"><Input.Search allowClear onSearch={setSearch} placeholder={`搜索${labels[kind][0]}`} style={{ width: 340 }} /><Space><Tag>稳定 ID</Tag></Space></div>
    <Table columns={[{ title: '显示名称 / ID', dataIndex: 'displayName', render: (value, row) => <><a onClick={() => navigate(resourcePath(row.kind, row.id))}>{value}</a><br /><Typography.Text code>{row.id}</Typography.Text></> }, { title: '成熟度', dataIndex: 'maturity', render: (value) => <Tag color={value === 'ACTIVE' ? 'green' : value === 'DEPRECATED' ? 'orange' : 'blue'}>{value}</Tag> }, ...(kind === 'OBJECT_TYPE' ? [{ title: '核心', dataIndex: 'promoted', render: (value: boolean) => value ? <Tag color="gold">核心</Tag> : '—' }, { title: '属性', dataIndex: 'properties', render: (value: unknown[]) => value.length }] : []), { title: '更新时间', dataIndex: 'updatedAt', render: (value) => new Date(value).toLocaleString() }, { title: '', width: 56, render: (_: unknown, row: OntologyResource) => <Button aria-label={`删除${row.displayName}`} danger icon={<DeleteOutlined />} onClick={(event) => { event.stopPropagation(); remove(row); }} type="text" /> }]} dataSource={items} loading={loading} locale={{ emptyText: <Empty description={`暂无${labels[kind][0]}`} /> }} onRow={(row) => ({ onClick: () => navigate(resourcePath(row.kind, row.id)) })} pagination={false} rowKey="id" size="small" />
  </div>;
}
