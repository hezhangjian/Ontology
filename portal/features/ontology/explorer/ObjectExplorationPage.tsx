import { FilterOutlined } from "@/shared/icons";
import { Button, Tag } from "@/shared/components/actions";
import { Alert, Skeleton, Statistic } from "@/shared/components/feedback";
import { Input, Select } from "@/shared/components/forms";
import { Card, Space } from "@/shared/components/layout";
import { Typography } from "@/shared/components/typography";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ExplorerApi } from "./explorer.service";
import type {
  ExplorerHome,
  ObjectSetPage,
  ObjectSetRequest,
  ObjectSummary,
  PropertyDefinition,
} from "./explorer.types";
import { ObjectTableView } from "./ExplorerViews";
import ObjectPreviewPanel from "./ObjectPreviewPanel";

export default function ObjectExplorationPage({
  navigate,
  objectTypeId,
}: {
  navigate: (path: string) => void;
  objectTypeId?: string;
}) {
  const api = useMemo(() => new ExplorerApi(), []);
  const [home, setHome] = useState<ExplorerHome>();
  const [page, setPage] = useState<ObjectSetPage>();
  const [query, setQuery] = useState<ObjectSetRequest>();
  const [selected, setSelected] = useState<string[]>([]);
  const [panel, setPanel] = useState<ObjectSummary>();
  const [error, setError] = useState("");
  const [filterProperty, setFilterProperty] = useState<string>();
  const [filterValue, setFilterValue] = useState("");
  const run = useCallback(
    async (next: ObjectSetRequest) => {
      setError("");
      setQuery(next);
      try {
        setPage(await api.query(next));
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : "查询失败");
      }
    },
    [api],
  );
  useEffect(() => {
    void (async () => {
      try {
        const loaded = await api.home();
        setHome(loaded);
        const type = loaded.objectTypes.find(
          (item) => item.id === objectTypeId,
        );
        if (!type) throw new Error("对象类型不存在或尚未发布");
        await run({
          objectTypeId: type.id,
          where: {},
          sort: [],
          pageSize: 50,
          columns: type.properties
            .filter((item) => !item.sensitive)
            .map((item) => item.id),
        });
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : "无法加载探索");
      }
    })();
  }, [api, objectTypeId, run]);
  const type = home?.objectTypes.find(
    (item) => item.id === query?.objectTypeId,
  );
  const filterable =
    type?.properties.filter((item) => item.filterable && !item.sensitive) ?? [];
  const addFilter = (property?: PropertyDefinition, rawValue?: unknown) => {
    if (!query) return;
    const current =
      query.where?.type === "and"
        ? (query.where.children as Array<Record<string, unknown>>)
        : query.where && Object.keys(query.where).length
          ? [query.where]
          : [];
    const id = property?.id ?? filterProperty;
    const value = rawValue ?? filterValue;
    if (!id || value === "") return;
    const leaf = { type: "property", propertyId: id, operator: "eq", value };
    void run({
      ...query,
      cursor: undefined,
      where: { type: "and", children: [...current, leaf] },
    });
    setFilterValue("");
  };
  const clearFilters = () =>
    query && void run({ ...query, cursor: undefined, where: {} });
  const openFull = (item: ObjectSummary) =>
    navigate(
      `/ontology/explorer/${item.objectTypeId}/${encodeURIComponent(item.objectId)}`,
    );
  if (!page && !error) return <Skeleton active />;
  return (
    <div className="object-explorer-page">
      <div className="explorer-toolbar">
        <div>
          <Space>
            <Typography.Title level={3}>
              {page?.objectTypeName ?? "对象探索"}
            </Typography.Title>
            <Tag color="success">可用</Tag>
          </Space>
          <Typography.Text type="secondary">
            服务端 Object Set · {page?.visibleCount ?? 0} 个可见对象 · 索引{" "}
            {page ? new Date(page.indexUpdatedAt).toLocaleString() : "—"}
          </Typography.Text>
        </div>
      </div>
      {error && <Alert closable message={error} showIcon type="error" />}
      <div className="object-set-layout">
        <aside className="filter-panel">
          <Typography.Title level={5}>
            <FilterOutlined /> 筛选器
          </Typography.Title>
          <Select
            aria-label="筛选属性"
            onChange={setFilterProperty}
            options={filterable.map((item) => ({
              value: item.id,
              label: `${item.displayName} · ${item.valueType}`,
            }))}
            placeholder="选择属性"
            value={filterProperty}
          />
          <Input
            aria-label="筛选值"
            onChange={(event) => setFilterValue(event.target.value)}
            onPressEnter={() => addFilter()}
            placeholder="输入筛选值"
            value={filterValue}
          />
          <Button block onClick={() => addFilter()} type="primary">
            添加条件
          </Button>
          <Button block onClick={clearFilters}>
            清除全部
          </Button>
          <Card size="small" title="活动条件">
            {query?.where?.type === "and" ? (
              (query.where.children as Array<Record<string, unknown>>).map(
                (item, index) => (
                  <Tag color="geekblue" key={index}>
                    {
                      type?.properties.find(
                        (property) => property.id === item.propertyId,
                      )?.displayName
                    }{" "}
                    = {String(item.value)}
                  </Tag>
                ),
              )
            ) : (
              <Typography.Text type="secondary">暂无筛选</Typography.Text>
            )}
          </Card>
          <Alert message="最多 50 条件 / 3 层逻辑 / 3 个一跳关系" type="info" />
        </aside>
        <main className="object-set-content">
          <div className="object-set-summary">
            <Statistic title="授权后数量" value={page?.visibleCount ?? 0} />
            <Typography.Text>已选择 {selected.length} 个</Typography.Text>
          </div>
          {page && (
            <ObjectTableView
              open={setPanel}
              page={page}
              selected={selected}
              setSelected={setSelected}
            />
          )}
          {page?.nextCursor && (
            <Button
              onClick={() =>
                query && void run({ ...query, cursor: page.nextCursor })
              }
            >
              加载下一页（稳定 Cursor）
            </Button>
          )}
        </main>
      </div>
      <ObjectPreviewPanel
        item={panel}
        onClose={() => setPanel(undefined)}
        openFull={openFull}
        properties={page?.properties ?? []}
      />
    </div>
  );
}
