import { ArrowLeftOutlined, CloudSyncOutlined, CopyOutlined, EyeOutlined, PlusOutlined, SaveOutlined } from '@/shared/icons';
import { Button, Tag } from '@/shared/components/actions';
import { Alert, Spin, message } from '@/shared/components/feedback';
import { Layout, Space } from '@/shared/components/layout';
import { randomUuid } from '@/shared/utils/randomUuid';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ExplorerApi } from '../../../ontology/explorer/explorer.service';
import type { ObjectTypeDefinition } from '../../../ontology/explorer/explorer.types';
import { datasetsApi } from '../../../../pages/datasets/datasetsApi';
import type { Dataset } from '../../../../pages/datasets/types';
import { DashboardApi } from '../services/dashboardApi';
import type { DashboardDataSource, DashboardDefinition, DashboardDetail, DashboardDraft, DashboardPage, DashboardValidation, DashboardWidget } from '../types';
import DashboardCanvas from './DashboardCanvas';
import DashboardDataSourceDialog from './DashboardDataSourceDialog';
import PageNavigator from './PageNavigator';
import WidgetInspector from './WidgetInspector';
import WidgetPalette from './WidgetPalette';

export default function DashboardEditorShell({  dashboardId, navigate }: { dashboardId: string; navigate: (path: string) => void }) {
  const api = useMemo(() => new DashboardApi(), []);
  const explorer = useMemo(() => new ExplorerApi(), []);
  const datasets = useMemo(() => datasetsApi(), []);
  const [detail, setDetail] = useState<DashboardDetail>();
  const [draft, setDraft] = useState<DashboardDraft>();
  const [definition, setDefinition] = useState<DashboardDefinition>();
  const [activePage, setActivePage] = useState('');
  const [selectedWidget, setSelectedWidget] = useState<string>();
  const [saveState, setSaveState] = useState<'SAVED'|'SAVING'|'FAILED'|'DIRTY'>('SAVED');
  const [validation, setValidation] = useState<DashboardValidation>();
  const [types, setTypes] = useState<ObjectTypeDefinition[]>([]);
  const [datasetItems, setDatasetItems] = useState<Dataset[]>([]);
  const [sourceOpen, setSourceOpen] = useState(false);
  const [sourceKind, setSourceKind] = useState<'DATASET'|'OBJECT_SET'>('DATASET');
  const [sourceType, setSourceType] = useState<string>();
  const [finishing, setFinishing] = useState(false);
  const [toast, context] = message.useMessage();
  const dirty = useRef(false);
  const changeVersion = useRef(0);
  const definitionRef = useRef<DashboardDefinition | undefined>(undefined);
  const draftRef = useRef<DashboardDraft | undefined>(undefined);
  const savePromise = useRef<Promise<DashboardDraft | undefined> | undefined>(undefined);
  const loadFieldValues = useCallback(async (datasetId: string, field: string) => {
    const result = await datasets.query(datasetId, [field], [{ operation: 'COUNT', label: '__count' }], [], field, 'ASC', 1000);
    return result.rows
      .map((row) => row[field])
      .filter((value) => value !== null && value !== undefined && String(value).length > 0)
      .map(String);
  }, [datasets]);
  useEffect(() => { void Promise.all([api.detail(dashboardId), api.lock(dashboardId), api.draft(dashboardId), explorer.home(), datasets.list()]).then(([dashboard,, value, home, datasetPage]) => { draftRef.current = value; definitionRef.current = value.definition; setDetail(dashboard); setDraft(value); setDefinition(value.definition); setActivePage([...value.definition.pages].sort((a,b) => a.order-b.order)[0]?.id ?? ''); setTypes(home.objectTypes); setDatasetItems(datasetPage.items); }).catch((error: Error) => void toast.error(error.message)); return () => { void api.releaseLock(dashboardId).catch(() => undefined); }; }, [api, dashboardId, datasets, explorer, toast]);
  const update = useCallback((next: DashboardDefinition) => { definitionRef.current = next; changeVersion.current += 1; dirty.current = true; setSaveState('DIRTY'); setDefinition(next); }, []);
  const flushSave = useCallback(async () => {
    try {
      while (dirty.current) {
        const nextDefinition = definitionRef.current;
        const currentDraft = draftRef.current;
        if (!nextDefinition || !currentDraft) return currentDraft;
        const savingVersion = changeVersion.current;
        setSaveState('SAVING');
        const value = await api.saveDraft(dashboardId, currentDraft.etag, nextDefinition);
        draftRef.current = value;
        setDraft(value);
        if (changeVersion.current === savingVersion) {
          dirty.current = false;
          setSaveState('SAVED');
        }
      }
      return draftRef.current;
    } catch (error) {
      setSaveState('FAILED');
      void toast.error((error as Error).message);
      throw error;
    }
  }, [api, dashboardId, toast]);
  const save = useCallback(() => {
    if (!dirty.current) return Promise.resolve(draftRef.current);
    if (!savePromise.current) {
      const pending = flushSave();
      savePromise.current = pending;
      void pending.finally(() => {
        if (savePromise.current === pending) savePromise.current = undefined;
      }).catch(() => undefined);
    }
    return savePromise.current;
  }, [flushSave]);
  useEffect(() => { if (!dirty.current) return; const timer = window.setTimeout(() => { void save(); }, 2000); return () => window.clearTimeout(timer); }, [definition, save]);
  useEffect(() => { const timer = window.setInterval(() => void api.renewLock(dashboardId).catch(() => setSaveState('FAILED')), 5 * 60 * 1000); return () => window.clearInterval(timer); }, [api, dashboardId]);
  if (!definition || !draft || !detail) return <div className="dashboard-editor-loading"><Spin size="large" tip="获取编辑租约" /></div>;
  const pages = definition.pages;
  const currentWidget = definition.widgets.find((item) => item.id === selectedWidget);
  const setPages = (next: DashboardPage[]) => { const pageIds = new Set(next.map((item) => item.id)); update({ ...definition, pages: next, widgets: definition.widgets.filter((item) => pageIds.has(item.pageId)) }); if (!pageIds.has(activePage)) setActivePage(next[0].id); };
  const addPage = () => { if (pages.length >= 10) { void toast.warning('每个看板最多 10 个页面'); return; } const id = randomUuid(); setPages([...pages, { id, name: `页面 ${pages.length + 1}`, description: '', order: pages.length }]); setActivePage(id); };
  const copyPage = () => { const source = pages.find((item) => item.id === activePage); if (!source || pages.length >= 10) return; const id = randomUuid(); const cloned = definition.widgets.filter((item) => item.pageId === source.id).map((item) => ({ ...item, id: randomUuid(), pageId: id })); update({ ...definition, pages: [...pages, { ...source, id, name: `${source.name} 副本`, order: pages.length }], widgets: [...definition.widgets, ...cloned] }); setActivePage(id); };
  const addWidget = (type: string) => { const requiresSource = !['MARKDOWN','SECTION'].includes(type); const dataSource = definition.dataSources[0]; if (requiresSource && !dataSource) { void toast.warning('请先添加数据源'); return; } const y = definition.widgets.filter((item) => item.pageId === activePage).reduce((max,item) => Math.max(max,item.layout.desktop.y+item.layout.desktop.h),0); const queryConfig = { xTimeGrain:'NONE', measures:[{id:randomUuid(),label:'指标值',aggregation:'count'}] }; const widget: DashboardWidget = { id: randomUuid(), pageId: activePage, dataSourceId: requiresSource ? dataSource.id : undefined, type, title: widgetTitle(type), description: '', layout: { desktop:{x:0,y,w:type==='METRIC'?6:12,h:type==='METRIC'?3:6}, tablet:{x:0,y,w:12,h:6}, mobile:{x:0,y,w:1,h:6} }, config: type==='MARKDOWN'?{markdown:'输入说明文本'}:type==='SECTION'?{}:type==='OBJECT_TABLE'?{}:queryConfig, interaction:{} }; update({ ...definition, widgets:[...definition.widgets,widget] }); setSelectedWidget(widget.id); };
  const addSource = () => { if (sourceKind === 'DATASET') { const dataset = datasetItems.find((item) => item.id === sourceType); if (!dataset) return; const source: DashboardDataSource = { id: randomUuid(), name: dataset.name, kind:'DATASET', datasetId:dataset.id, query:{} }; update({ ...definition, dataSources:[...definition.dataSources,source] }); } else { const type = types.find((item) => item.id === sourceType); if (!type) return; const source: DashboardDataSource = { id: randomUuid(), name: type.displayName, kind:'OBJECT_SET', objectTypeId:type.id, query:{objectTypeId:type.id,where:{},sort:[],pageSize:50,columns:[]} }; update({ ...definition, dataSources:[...definition.dataSources,source] }); } setSourceOpen(false); setSourceType(undefined); void toast.success('已添加看板数据源'); };
  const finish = async () => {
    if (finishing) return;
    setFinishing(true);
    try {
      try {
        await save();
      } catch {
        return;
      }
      const checked = await api.validate(dashboardId);
      setValidation(checked);
      if (!checked.valid) {
        void toast.error('还有图表没有选好数据字段');
        return;
      }
      await api.publish(dashboardId, '自动保存');
      void toast.success('看板已保存');
      navigate(`/apps/dashboards/${dashboardId}/view`);
    } catch (error) {
      void toast.error((error as Error).message);
    } finally {
      setFinishing(false);
    }
  };
  return <Layout className="dashboard-editor-shell">{context}<header className="dashboard-editor-header"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/apps/dashboards')}>返回</Button><div><strong>{detail.summary.name}</strong><small>拖入图表，右侧点选字段</small></div></Space><Space><Tag icon={saveState === 'SAVING' ? <CloudSyncOutlined spin /> : <SaveOutlined />} color={saveState === 'FAILED' ? 'red' : saveState === 'DIRTY' ? 'gold' : 'green'}>{saveLabel(saveState)}</Tag><Button icon={<EyeOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/view`)}>查看</Button><Button loading={finishing} onClick={() => void finish()} type="primary">完成并查看</Button></Space></header>
    {validation && !validation.valid && <Alert closable description={validation.issues.slice(0,4).map((item) => item.message).join('；')} message="请补全图表的数据字段" showIcon type="error" />}
    <Layout className="dashboard-editor-body"><aside className="dashboard-editor-left"><PageNavigator active={activePage} onAdd={addPage} onChange={setPages} onSelect={setActivePage} pages={pages} /><Button icon={<CopyOutlined />} onClick={copyPage}>复制页面</Button><WidgetPalette disabled={!definition.dataSources.length} onAdd={addWidget} /><div className="editor-section-title">数据源</div>{definition.dataSources.map((item) => <Tag key={item.id}>{item.kind === 'DATASET' ? 'Dataset' : '本体'} · {item.name}</Tag>)}<Button icon={<PlusOutlined />} onClick={() => setSourceOpen(true)}>添加数据源</Button></aside><main className="dashboard-editor-main"><DashboardCanvas active={activePage} onSelect={setSelectedWidget} selected={selectedWidget} widgets={definition.widgets} /></main><aside className="dashboard-editor-right"><WidgetInspector dataSources={definition.dataSources} datasets={datasetItems} loadFieldValues={loadFieldValues} objectTypes={types} onChange={(widget) => update({ ...definition, widgets:definition.widgets.map((item) => item.id===widget.id?widget:item) })} onDelete={() => { update({ ...definition, widgets:definition.widgets.filter((item) => item.id!==selectedWidget) }); setSelectedWidget(undefined); }} widget={currentWidget} /></aside></Layout>
    <DashboardDataSourceDialog datasets={datasetItems} kind={sourceKind} onCancel={() => setSourceOpen(false)} onConfirm={addSource} onKindChange={(value) => { setSourceKind(value); setSourceType(undefined); }} onSourceChange={setSourceType} open={sourceOpen} sourceId={sourceType} types={types} />
  </Layout>;
}

function widgetTitle(type: string) { return ({METRIC:'指标卡',BAR:'柱状图',STACKED_BAR:'堆叠柱状图',LINE:'折线图',AREA:'面积图',PIE:'饼图',DONUT:'环形图',PIVOT:'透视表',OBJECT_TABLE:'明细表',MARKDOWN:'说明',SECTION:'分节标题'} as Record<string,string>)[type] ?? type; }
function saveLabel(value: string) { return value === 'SAVING' ? '保存中' : value === 'FAILED' ? '保存失败' : value === 'DIRTY' ? '等待自动保存' : '已保存'; }
