import {
  Accordion,
  Table as HeroTable,
} from '@heroui/react';
import React from 'react';
import { Button } from './actions';
import { Checkbox } from './forms';
import { Empty, Spin } from './feedback';
import { Space } from './layout';
import { cx } from './common';
import type { AnyProps } from './common';

export type ColumnsType<T = any> = Array<AnyProps & {
  render?: (value: any, row: T, index: number) => React.ReactNode;
}>;

export function Table<T>({
  bordered,
  columns = [],
  dataSource = [],
  loading,
  locale,
  onRow,
  pagination,
  rowKey,
  rowSelection,
  scroll,
  ...props
}: AnyProps) {
  const [localPage, setLocalPage] = React.useState(1);
  if (loading) return <Spin />;
  const empty = locale?.emptyText;
  const rowId = (row: T, rowIndex: number) =>
    typeof rowKey === 'function' ? rowKey(row, rowIndex) : rowKey ? (row as any)[rowKey] : rowIndex;
  const paginationEnabled = pagination && pagination !== false;
  const pageSize = paginationEnabled ? Number(pagination.pageSize ?? 10) : dataSource.length || 1;
  const currentPage = paginationEnabled ? Number(pagination.current ?? localPage) : 1;
  const total = paginationEnabled ? Number(pagination.total ?? dataSource.length) : dataSource.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const serverPaginated = paginationEnabled && pagination.total != null && pagination.onChange;
  const visibleRows = paginationEnabled && !serverPaginated
    ? dataSource.slice((currentPage - 1) * pageSize, currentPage * pageSize)
    : dataSource;
  const selectedKeys = (rowSelection?.selectedRowKeys ?? []).map(String);
  const allKeys = visibleRows.map((row: T, index: number) => String(rowId(row, index)));
  const changeSelection = (keys: string[]) =>
    rowSelection?.onChange?.(keys, dataSource.filter((row: T, index: number) => keys.includes(String(rowId(row, index)))));
  const changePage = (nextPage: number) => {
    const boundedPage = Math.max(1, Math.min(pageCount, nextPage));
    setLocalPage(boundedPage);
    pagination?.onChange?.(boundedPage, pageSize);
  };
  const horizontalWidth = scroll?.x === true || scroll?.x === 'max-content' ? 'max-content' : scroll?.x;

  return <HeroTable className={cx(bordered && 'is-bordered')} variant="secondary">
    <HeroTable.ScrollContainer className="data-table-wrap max-w-full overflow-auto" style={{ maxHeight: scroll?.y }}>
      <HeroTable.Content
        {...props}
        aria-label={props['aria-label'] ?? '数据表格'}
        className={cx('data-table', props.className)}
        style={{ minWidth: horizontalWidth }}
      >
        <HeroTable.Header>
            {rowSelection && <HeroTable.Column className="data-table-selection w-11 px-2" id="__selection">
              <Checkbox
                aria-label="选择全部"
                checked={allKeys.length > 0 && allKeys.every((key: string) => selectedKeys.includes(key))}
                indeterminate={selectedKeys.length > 0 && !allKeys.every((key: string) => selectedKeys.includes(key))}
                onChange={(event: React.ChangeEvent<HTMLInputElement>) => changeSelection(event.target.checked ? allKeys : [])}
                slot="selection"
              />
            </HeroTable.Column>}
            {columns.map((column: AnyProps, index: number) =>
              <HeroTable.Column
                id={`${String(column.key ?? column.dataIndex ?? 'column')}-${index}`}
                isRowHeader={index === 0}
                key={`${String(column.key ?? column.dataIndex ?? 'column')}-${index}`}
                style={{ width: column.width }}
              >
                {column.title}
              </HeroTable.Column>)}
        </HeroTable.Header>
        <HeroTable.Body>
          {visibleRows.map((row: T, rowIndex: number) => {
            const rowProps = onRow?.(row) ?? {};
            const { onClick, ...tableRowProps } = rowProps;
            const key = rowId(row, rowIndex);
            const selected = selectedKeys.includes(String(key));
            return <HeroTable.Row
              {...tableRowProps}
              className={cx(rowProps.className, (rowProps.onClick || rowProps.onDoubleClick) && 'is-interactive cursor-pointer hover:bg-gray-50')}
              id={String(key)}
              key={String(key)}
              onAction={onClick}
            >
              {rowSelection && <HeroTable.Cell className="data-table-selection w-11 px-2" onClick={(event) => event.stopPropagation()}>
                <Checkbox
                  aria-label={`选择第 ${rowIndex + 1} 行`}
                  checked={selected}
                  onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                    changeSelection(event.target.checked
                      ? [...selectedKeys, String(key)]
                      : selectedKeys.filter((current: string) => current !== String(key)))}
                  slot="selection"
                />
              </HeroTable.Cell>}
              {columns.map((column: AnyProps, columnIndex: number) => {
                const cellValue = column.dataIndex ? (row as any)[column.dataIndex] : undefined;
                return <HeroTable.Cell key={`${String(column.key ?? column.dataIndex ?? 'column')}-${columnIndex}`}>
                  {column.render ? column.render(cellValue, row, rowIndex) : String(cellValue ?? '')}
                </HeroTable.Cell>;
              })}
            </HeroTable.Row>;
          })}
        </HeroTable.Body>
      </HeroTable.Content>
      {visibleRows.length === 0 && <div className="sticky left-0 flex min-h-60 w-full items-center justify-center text-center">
        {React.isValidElement(empty) ? empty : <Empty description={empty} />}
      </div>}
    </HeroTable.ScrollContainer>
    {paginationEnabled && pageCount > 1 && <nav aria-label="表格分页" className="data-table-pagination mt-3 flex items-center justify-end gap-2 text-sm text-gray-500">
      <Button aria-label="上一页" disabled={currentPage <= 1} onClick={() => changePage(currentPage - 1)}>上一页</Button>
      <span>第 {currentPage} / {pageCount} 页</span>
      <Button aria-label="下一页" disabled={currentPage >= pageCount} onClick={() => changePage(currentPage + 1)}>下一页</Button>
    </nav>}
  </HeroTable>;
}

export const Collapse = ({ items = [], ...props }: AnyProps) =>
  <Accordion {...props} className={cx('collapsible-list', props.className)}>
    {items.map((item: AnyProps) => <Accordion.Item id={String(item.key)} key={item.key}>
      <Accordion.Heading>
        <Accordion.Trigger>{item.label}<Accordion.Indicator /></Accordion.Trigger>
      </Accordion.Heading>
      <Accordion.Panel><Accordion.Body>{item.children}</Accordion.Body></Accordion.Panel>
    </Accordion.Item>)}
  </Accordion>;

const DescriptionItem = ({ children, label }: AnyProps) =>
  <div className="grid grid-cols-[minmax(120px,32%)_1fr]">
    <dt className="p-2 text-gray-500">{label}</dt>
    <dd className="m-0 p-2">{children}</dd>
  </div>;

export const Descriptions = Object.assign(
  ({ bordered, children, column = 1, items = [], size, ...props }: AnyProps) =>
    <dl
      {...props}
      className={cx('descriptions-list grid divide-y divide-gray-200 [grid-template-columns:repeat(var(--description-columns),minmax(0,1fr))]', bordered && 'rounded-md border border-gray-200', props.className)}
      style={{ ...props.style, '--description-columns': Math.max(1, Number(column)) }}
    >
      {children}
      {items.map((item: AnyProps) => <DescriptionItem key={item.key} label={item.label}>{item.children}</DescriptionItem>)}
    </dl>,
  { Item: DescriptionItem },
);

const ListItem = ({ actions, children, ...props }: AnyProps) =>
  <li {...props} className={cx('flex min-h-12 items-center justify-between gap-4 px-1 py-2.5', props.onClick && 'is-interactive cursor-pointer hover:bg-gray-50', props.className)}>{children}{actions && <Space>{actions}</Space>}</li>;

ListItem.Meta = ({ description, title }: AnyProps) =>
  <div className="grid gap-1"><strong>{title}</strong><div className="text-sm text-gray-500">{description}</div></div>;

export const List = Object.assign(
  ({ dataSource = [], locale, renderItem, ...props }: AnyProps) =>
    <ul {...props} className={cx('data-list m-0 list-none divide-y divide-gray-100 p-0', props.className)}>
      {dataSource.map((item: AnyProps, index: number) =>
        <React.Fragment key={item.id ?? index}>{renderItem(item, index)}</React.Fragment>)}
      {dataSource.length === 0 && <li className="data-list-empty flex min-h-44 items-center justify-center">{React.isValidElement(locale?.emptyText) ? locale.emptyText : <Empty description={locale?.emptyText} />}</li>}
    </ul>,
  { Item: ListItem },
);

export const Steps = ({ current = 0, items = [], ...props }: AnyProps) =>
  <ol {...props} className={cx('process-steps m-0 flex list-none gap-2 p-0', props.className)}>
    {items.map((item: AnyProps, index: number) =>
      <li className={cx('flex flex-1 items-center gap-2 text-sm text-gray-400', index <= current && 'is-active text-brand-600')} key={item.key ?? index}>
        <span className="grid size-6 place-items-center rounded-full border border-current text-xs">{index + 1}</span>
        <span>{item.title}</span>
        {index < items.length - 1 && <span aria-hidden className={cx('h-px min-w-4 flex-1 bg-gray-200', index < current && 'bg-brand-500')} />}
      </li>)}
  </ol>;

const TimelineItem = ({ children, index = 0 }: AnyProps) => <li className="event-timeline-item relative pb-4 pl-5 before:absolute before:left-[-5px] before:top-1.5 before:size-2.5 before:rounded-full before:bg-brand-500" style={{ '--timeline-index': index } as React.CSSProperties}>{children}</li>;

export const Timeline = Object.assign(
  ({ children, items = [], ...props }: AnyProps) =>
    <ol {...props} className={cx('event-timeline m-0 list-none border-l border-gray-200 p-0', props.className)}>
      {children}
      {items.map((item: AnyProps, index: number) => <TimelineItem index={index} key={index}>{item.children}</TimelineItem>)}
    </ol>,
  { Item: TimelineItem },
);
