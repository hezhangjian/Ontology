import { ArrowLeftOutlined, DeleteOutlined, FolderOpenOutlined, PlusOutlined, SaveOutlined } from '@/shared/icons';
import { Button } from '@/shared/components/actions';
import { Table } from '@/shared/components/data';
import { Alert, Skeleton, message } from '@/shared/components/feedback';
import { Input } from '@/shared/components/forms';
import { Card, Space } from '@/shared/components/layout';
import { Typography } from '@/shared/components/typography';
import { useEffect, useMemo, useRef, useState } from 'react';
import { datasetsApi } from './datasetsApi';

type Props = {
    id?: string;
  navigate: (path: string) => void;
};

export default function DatasetEditorPage({  id, navigate }: Props) {
  const api = useMemo(() => datasetsApi(), []);
  const [datasetId, setDatasetId] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [columns, setColumns] = useState<string[]>(['名称', '数值']);
  const [rows, setRows] = useState<Record<string, string>[]>([{}]);
  const [manual, setManual] = useState(true);
  const [loading, setLoading] = useState(Boolean(id));
  const [saving, setSaving] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!id) return;
    let active = true;
    void api.get(id).then(async (dataset) => {
      const loadedRows: Record<string, unknown>[] = [];
      if (dataset.source.kind === 'MANUAL') {
        for (let offset = 0; offset < dataset.rowCount; offset += 200) {
          const page = await api.preview(id, 200, offset);
          loadedRows.push(...page.rows);
        }
      }
      if (!active) return;
      setDatasetId(dataset.id);
      setName(dataset.name);
      setDescription(dataset.description);
      setManual(dataset.source.kind === 'MANUAL');
      const loadedColumns = dataset.fields.map((field) => field.name);
      setColumns(loadedColumns.length > 0 ? loadedColumns : ['名称', '数值']);
      setRows(loadedRows.length > 0
        ? loadedRows.map((row) => Object.fromEntries(Object.entries(row).map(([key, value]) => [key, value == null ? '' : String(value)])))
        : [{}]);
    }).catch((error: Error) => void message.error(error.message)).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [api, id]);

  const save = async () => {
    if (!datasetId.trim() || !name.trim()) {
      void message.error('请填写 Dataset ID 和名称');
      return;
    }
    const normalizedColumns = columns.map((column) => column.trim());
    if (manual && (normalizedColumns.some((column) => !column) || new Set(normalizedColumns).size !== normalizedColumns.length)) {
      void message.error('字段名称不能为空或重复');
      return;
    }
    const savedRows = rows
      .filter((row) => normalizedColumns.some((column, index) => String(row[columns[index]] ?? '').trim() !== ''))
      .map((row) => Object.fromEntries(normalizedColumns.map((column, index) => [column, row[columns[index]] ?? ''])));
    setSaving(true);
    try {
      const value = id
        ? await api.update(id, manual ? { description, name, rows: savedRows } : { description, name })
        : await api.create({ description, id: datasetId, name, rows: savedRows });
      void message.success(id ? 'Dataset 已更新' : 'Dataset 已创建');
      navigate(`/data/datasets/${value.id}`);
    } catch (error) {
      void message.error((error as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const renameColumn = (index: number, value: string) => {
    const previous = columns[index];
    setColumns((current) => current.map((column, currentIndex) => currentIndex === index ? value : column));
    setRows((current) => current.map((row) => {
      const next = { ...row, [value]: row[previous] ?? '' };
      if (previous !== value) delete next[previous];
      return next;
    }));
  };
  const addColumn = () => {
    const base = '新字段';
    let suffix = 1;
    let value = base;
    while (columns.includes(value)) value = `${base}${suffix++}`;
    setColumns((current) => [...current, value]);
  };
  const removeColumn = (index: number) => {
    if (columns.length === 1) {
      void message.error('Dataset 至少需要一个字段');
      return;
    }
    const removed = columns[index];
    setColumns((current) => current.filter((_, currentIndex) => currentIndex !== index));
    setRows((current) => current.map((row) => {
      const next = { ...row };
      delete next[removed];
      return next;
    }));
  };
  const updateCell = (rowIndex: number, column: string, value: string) => {
    setRows((current) => current.map((row, currentIndex) => currentIndex === rowIndex ? { ...row, [column]: value } : row));
  };
  const importCsv = async (file?: File) => {
    if (!file) return;
    try {
      const parsed = parseCsv(await file.text());
      if (parsed.length < 1 || parsed[0].every((cell) => !cell.trim())) throw new Error('CSV 文件没有表头');
      const importedColumns = parsed[0].map((cell, index) => cell.trim() || `字段${index + 1}`);
      if (new Set(importedColumns).size !== importedColumns.length) throw new Error('CSV 表头包含重复字段');
      setColumns(importedColumns);
      setRows(parsed.slice(1).filter((record) => record.some((cell) => cell.trim())).map((record) =>
        Object.fromEntries(importedColumns.map((column, index) => [column, record[index] ?? '']))));
      void message.success(`已导入 ${Math.max(0, parsed.length - 1)} 行数据`);
    } catch (error) {
      void message.error((error as Error).message);
    } finally {
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  if (loading) return <Skeleton active />;
  return <div className="dataset-editor-page">
    <div className="page-title-row">
      <div>
        <Typography.Title level={2}>{id ? '编辑 Dataset' : '手动创建 Dataset'}</Typography.Title>
        <Typography.Paragraph>像填写表格一样添加字段和数据，也可以直接导入 CSV 文件。</Typography.Paragraph>
      </div>
      <Space>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(id ? `/data/datasets/${id}` : '/data/datasets')}>取消</Button>
        <Button icon={<SaveOutlined />} loading={saving} onClick={() => void save()} type="primary">保存</Button>
      </Space>
    </div>
    {!manual && <Alert message="该 Dataset 由 Pipeline 生成，只能编辑名称和说明。" showIcon type="info" />}
    <Card>
      <label className="dataset-editor-field">
        <span>Dataset ID</span>
        <Input disabled={Boolean(id)} onChange={(event) => setDatasetId(event.target.value)} placeholder="例如：monthly_usage" value={datasetId} />
        <small>用于 API 路径和资源引用，创建后不可修改。</small>
      </label>
      <label className="dataset-editor-field">
        <span>名称</span>
        <Input onChange={(event) => setName(event.target.value)} placeholder="例如：月度用量" value={name} />
      </label>
      <label className="dataset-editor-field">
        <span>说明</span>
        <Input.TextArea onChange={(event) => setDescription(event.target.value)} rows={3} value={description} />
      </label>
      {manual && <section className="manual-dataset-grid">
        <div className="manual-dataset-toolbar">
          <div><strong>字段和数据</strong><Typography.Text type="secondary">第一行是字段名称，下面每行是一条数据。</Typography.Text></div>
          <Space>
            <input accept=".csv,text/csv" hidden onChange={(event) => void importCsv(event.target.files?.[0])} ref={fileInput} type="file" />
            <Button icon={<FolderOpenOutlined />} onClick={() => fileInput.current?.click()}>导入 CSV</Button>
            <Button icon={<PlusOutlined />} onClick={addColumn}>添加字段</Button>
            <Button icon={<PlusOutlined />} onClick={() => setRows((current) => [...current, {}])} type="primary">添加一行</Button>
          </Space>
        </div>
        <Table
          aria-label="手动维护 Dataset 数据"
          bordered
          columns={[
            { key: 'index', render: (_: unknown, __: Record<string, string>, rowIndex: number) => rowIndex + 1, title: '#', width: 48 },
            ...columns.map((column, columnIndex) => ({
              key: columnIndex,
              render: (_: unknown, row: Record<string, string>, rowIndex: number) =>
                <Input aria-label={`第 ${rowIndex + 1} 行${column || `字段${columnIndex + 1}`}`} onChange={(event) => updateCell(rowIndex, column, event.target.value)} value={row[column] ?? ''} />,
              title: <div className="manual-dataset-column"><Input aria-label={`字段 ${columnIndex + 1} 名称`} onChange={(event) => renameColumn(columnIndex, event.target.value)} value={column} /><Button aria-label={`删除字段${column}`} danger icon={<DeleteOutlined />} onClick={() => removeColumn(columnIndex)} type="text" /></div>,
              width: 210,
            })),
            {
              key: 'operation',
              render: (_: unknown, __: Record<string, string>, rowIndex: number) =>
                <Button aria-label={`删除第 ${rowIndex + 1} 行`} danger icon={<DeleteOutlined />} onClick={() => setRows((current) => current.filter((_, index) => index !== rowIndex))} type="text" />,
              title: '',
              width: 52,
            },
          ]}
          dataSource={rows}
          locale={{ emptyText: '还没有数据，点击“添加一行”开始填写' }}
          pagination={false}
          rowKey={(_: Record<string, string>, index: number) => index}
          scroll={{ x: 'max-content', y: 480 }}
          size="small"
        />
      </section>}
    </Card>
  </div>;
}

function parseCsv(content: string) {
  const records: string[][] = [];
  let record: string[] = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < content.length; index += 1) {
    const character = content[index];
    if (character === '"') {
      if (quoted && content[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (character === ',' && !quoted) {
      record.push(cell);
      cell = '';
    } else if ((character === '\n' || character === '\r') && !quoted) {
      if (character === '\r' && content[index + 1] === '\n') index += 1;
      record.push(cell);
      records.push(record);
      record = [];
      cell = '';
    } else {
      cell += character;
    }
  }
  if (quoted) throw new Error('CSV 文件中存在未闭合的引号');
  if (cell || record.length > 0) {
    record.push(cell);
    records.push(record);
  }
  return records;
}
