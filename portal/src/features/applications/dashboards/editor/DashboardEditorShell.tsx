import { ArrowLeftOutlined, CheckCircleOutlined, CloudSyncOutlined, CopyOutlined, DesktopOutlined, EyeOutlined, PlusOutlined, RedoOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { Alert, Button, Layout, Modal, Select, Space, Spin, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ExplorerApi } from '../../../ontology/explorer/explorer.service';
import type { ObjectTypeDefinition } from '../../../ontology/explorer/explorer.types';
import { DashboardApi } from '../services/dashboardApi';
import type { DashboardDataSource, DashboardDefinition, DashboardDetail, DashboardDraft, DashboardPage, DashboardValidation, DashboardWidget } from '../types';
import DashboardCanvas from './DashboardCanvas';
import PageNavigator from './PageNavigator';
import WidgetInspector from './WidgetInspector';
import WidgetPalette from './WidgetPalette';

export default function DashboardEditorShell({ accessToken, dashboardId, navigate }: { accessToken: string; dashboardId: string; navigate: (path: string) => void }) {
  const api = useMemo(() => new DashboardApi(accessToken), [accessToken]);
  const explorer = useMemo(() => new ExplorerApi(accessToken), [accessToken]);
  const [detail, setDetail] = useState<DashboardDetail>();
  const [draft, setDraft] = useState<DashboardDraft>();
  const [definition, setDefinition] = useState<DashboardDefinition>();
  const [activePage, setActivePage] = useState('');
  const [selectedWidget, setSelectedWidget] = useState<string>();
  const [saveState, setSaveState] = useState<'SAVED'|'SAVING'|'FAILED'|'DIRTY'>('SAVED');
  const [validation, setValidation] = useState<DashboardValidation>();
  const [types, setTypes] = useState<ObjectTypeDefinition[]>([]);
  const [sourceOpen, setSourceOpen] = useState(false);
  const [sourceType, setSourceType] = useState<string>();
  const [toast, context] = message.useMessage();
  const dirty = useRef(false);
  useEffect(() => { void Promise.all([api.detail(dashboardId), api.lock(dashboardId), api.draft(dashboardId), explorer.home()]).then(([dashboard,, value, home]) => { setDetail(dashboard); setDraft(value); setDefinition(value.definition); setActivePage([...value.definition.pages].sort((a,b) => a.order-b.order)[0]?.id ?? ''); setTypes(home.objectTypes); }).catch((error: Error) => void toast.error(error.message)); return () => { void api.releaseLock(dashboardId).catch(() => undefined); }; }, [api, dashboardId, explorer, toast]);
  const update = useCallback((next: DashboardDefinition) => { dirty.current = true; setSaveState('DIRTY'); setDefinition(next); }, []);
  const save = useCallback(async () => { if (!definition || !draft || !dirty.current) return draft; setSaveState('SAVING'); try { const value = await api.saveDraft(dashboardId, draft.etag, definition); dirty.current = false; setDraft(value); setSaveState('SAVED'); return value; } catch (error) { setSaveState('FAILED'); void toast.error((error as Error).message); throw error; } }, [api, dashboardId, definition, draft, toast]);
  useEffect(() => { if (!dirty.current) return; const timer = window.setTimeout(() => { void save(); }, 2000); return () => window.clearTimeout(timer); }, [definition, save]);
  useEffect(() => { const timer = window.setInterval(() => void api.renewLock(dashboardId).catch(() => setSaveState('FAILED')), 5 * 60 * 1000); return () => window.clearInterval(timer); }, [api, dashboardId]);
  if (!definition || !draft || !detail) return <div className="dashboard-editor-loading"><Spin size="large" tip="获取编辑租约" /></div>;
  const pages = definition.pages;
  const currentWidget = definition.widgets.find((item) => item.id === selectedWidget);
  const setPages = (next: DashboardPage[]) => { const pageIds = new Set(next.map((item) => item.id)); update({ ...definition, pages: next, widgets: definition.widgets.filter((item) => pageIds.has(item.pageId)) }); if (!pageIds.has(activePage)) setActivePage(next[0].id); };
  const addPage = () => { if (pages.length >= 10) { void toast.warning('每个看板最多 10 个页面'); return; } const id = crypto.randomUUID(); setPages([...pages, { id, name: `页面 ${pages.length + 1}`, description: '', order: pages.length }]); setActivePage(id); };
  const copyPage = () => { const source = pages.find((item) => item.id === activePage); if (!source || pages.length >= 10) return; const id = crypto.randomUUID(); const cloned = definition.widgets.filter((item) => item.pageId === source.id).map((item) => ({ ...item, id: crypto.randomUUID(), pageId: id })); update({ ...definition, pages: [...pages, { ...source, id, name: `${source.name} 副本`, order: pages.length }], widgets: [...definition.widgets, ...cloned] }); setActivePage(id); };
  const addWidget = (type: string) => { const requiresSource = !['MARKDOWN','SECTION'].includes(type); const dataSource = definition.dataSources[0]; if (requiresSource && !dataSource) { void toast.warning('请先添加对象数据源'); return; } const y = definition.widgets.filter((item) => item.pageId === activePage).reduce((max,item) => Math.max(max,item.layout.desktop.y+item.layout.desktop.h),0); const widget: DashboardWidget = { id: crypto.randomUUID(), pageId: activePage, dataSourceId: requiresSource ? dataSource.id : undefined, type, title: widgetTitle(type), description: '', layout: { desktop:{x:0,y,w:type==='METRIC'?6:12,h:type==='METRIC'?3:6}, tablet:{x:0,y,w:12,h:6}, mobile:{x:0,y,w:1,h:6} }, config: type==='METRIC'?{aggregation:'count'}:type==='MARKDOWN'?{markdown:'输入说明文本'}:{}, interaction:{} }; update({ ...definition, widgets:[...definition.widgets,widget] }); setSelectedWidget(widget.id); };
  const addSource = () => { const type = types.find((item) => item.id === sourceType); if (!type) return; const source: DashboardDataSource = { id: crypto.randomUUID(), name: type.displayName, kind:'OBJECT_SET', objectTypeId:type.id, ontologyRevision:type.ontologyRevision, query:{objectTypeId:type.id,where:{},sort:[],pageSize:50,columns:[]} }; update({ ...definition, dataSources:[...definition.dataSources,source] }); setSourceOpen(false); setSourceType(undefined); void toast.success('已添加服务端 Object Set 数据源'); };
  const validate = async () => { await save(); const value = await api.validate(dashboardId); setValidation(value); if (value.valid) void toast.success('验证通过，可以发布'); else void toast.error(`发现 ${value.issues.length} 个发布阻断问题`); };
  const publish = async () => { await save(); const checked = await api.validate(dashboardId); setValidation(checked); if (!checked.valid) return; await api.publish(dashboardId, '从全屏编辑器发布'); void toast.success('已原子发布新版本'); navigate(`/apps/dashboards/${dashboardId}/view`); };
  return <Layout className="dashboard-editor-shell">{context}<header className="dashboard-editor-header"><Space><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/apps/dashboards')}>退出</Button><div><strong>{detail.summary.name}</strong><small>24 列全屏编辑器</small></div></Space><Space><Tag icon={saveState === 'SAVING' ? <CloudSyncOutlined spin /> : <SaveOutlined />} color={saveState === 'FAILED' ? 'red' : saveState === 'DIRTY' ? 'gold' : 'green'}>{saveLabel(saveState)}</Tag><Button disabled icon={<UndoOutlined />}>撤销</Button><Button disabled icon={<RedoOutlined />}>重做</Button><Button icon={<DesktopOutlined />}>桌面预览</Button><Button icon={<EyeOutlined />} onClick={() => navigate(`/apps/dashboards/${dashboardId}/view`)}>预览</Button><Button icon={<CheckCircleOutlined />} onClick={() => void validate()}>验证</Button><Button onClick={() => void publish()} type="primary">发布</Button></Space></header>
    {validation && !validation.valid && <Alert closable description={validation.issues.slice(0,4).map((item) => `${item.path}: ${item.message}`).join('；')} message="发布被阻止" showIcon type="error" />}
    <Layout className="dashboard-editor-body"><aside className="dashboard-editor-left"><PageNavigator active={activePage} onAdd={addPage} onChange={setPages} onSelect={setActivePage} pages={pages} /><Button icon={<CopyOutlined />} onClick={copyPage}>复制当前页面</Button><WidgetPalette disabled={!definition.dataSources.length} onAdd={addWidget} /><div className="editor-section-title">数据源</div>{definition.dataSources.map((item) => <Tag key={item.id}>{item.name} · {item.kind}</Tag>)}<Button icon={<PlusOutlined />} onClick={() => setSourceOpen(true)}>添加对象数据源</Button><div className="editor-section-title">筛选变量</div><Typography.Text type="secondary">稳定 ID 显式绑定；当前 {definition.filters.length}/10</Typography.Text></aside><main className="dashboard-editor-main"><DashboardCanvas active={activePage} onSelect={setSelectedWidget} selected={selectedWidget} widgets={definition.widgets} /></main><aside className="dashboard-editor-right"><WidgetInspector dataSources={definition.dataSources} onChange={(widget) => update({ ...definition, widgets:definition.widgets.map((item) => item.id===widget.id?widget:item) })} onDelete={() => { update({ ...definition, widgets:definition.widgets.filter((item) => item.id!==selectedWidget) }); setSelectedWidget(undefined); }} widget={currentWidget} /></aside></Layout>
    <Modal onCancel={() => setSourceOpen(false)} onOk={addSource} open={sourceOpen} title="添加对象类型 + Object Set 数据源"><Select onChange={setSourceType} options={types.map((item) => ({value:item.id,label:`${item.displayName} · revision ${item.ontologyRevision}`}))} placeholder="选择已发布对象类型" style={{width:'100%'}} value={sourceType} /></Modal>
  </Layout>;
}

function widgetTitle(type: string) { return ({METRIC:'对象总数',BAR:'分类柱状图',PIE:'分类占比',OBJECT_TABLE:'对象明细',MARKDOWN:'说明',SECTION:'分节标题'} as Record<string,string>)[type] ?? type; }
function saveLabel(value: string) { return value === 'SAVING' ? '保存中' : value === 'FAILED' ? '保存失败' : value === 'DIRTY' ? '等待自动保存' : '已保存'; }
