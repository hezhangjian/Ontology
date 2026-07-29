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

  return <div className="ui-table-wrap">
    <div className="ui-table-scroll" style={{ maxHeight: scroll?.y, overflow: 'auto' }}>
      <table {...props} aria-label={props['aria-label'] ?? '数据表格'} className={cx('ui-table', bordered && 'is-bordered', props.className)} style={{ minWidth: horizontalWidth }}>
        <thead>
          <tr>
            {rowSelection && <th className="ui-table-selection">
              <Checkbox
                aria-label="选择全部"
                checked={allKeys.length > 0 && allKeys.every((key: string) => selectedKeys.includes(key))}
                indeterminate={selectedKeys.length > 0 && !allKeys.every((key: string) => selectedKeys.includes(key))}
                onChange={(event: React.ChangeEvent<HTMLInputElement>) => changeSelection(event.target.checked ? allKeys : [])}
              />
            </th>}
            {columns.map((column: AnyProps, index: number) =>
              <th key={`${String(column.key ?? column.dataIndex ?? 'column')}-${index}`} style={{ width: column.width }}>
                {column.title}
              </th>)}
          </tr>
        </thead>
        <tbody>
          {visibleRows.map((row: T, rowIndex: number) => {
            const rowProps = onRow?.(row) ?? {};
            const key = rowId(row, rowIndex);
            const selected = selectedKeys.includes(String(key));
            return <tr
              {...rowProps}
              className={cx(rowProps.className, (rowProps.onClick || rowProps.onDoubleClick) && 'is-interactive')}
              key={key}
            >
              {rowSelection && <td className="ui-table-selection" onClick={(event) => event.stopPropagation()}>
                <Checkbox
                  aria-label={`选择第 ${rowIndex + 1} 行`}
                  checked={selected}
                  onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                    changeSelection(event.target.checked
                      ? [...selectedKeys, String(key)]
                      : selectedKeys.filter((current: string) => current !== String(key)))}
                />
              </td>}
              {columns.map((column: AnyProps, columnIndex: number) => {
                const cellValue = column.dataIndex ? (row as any)[column.dataIndex] : undefined;
                return <td key={`${String(column.key ?? column.dataIndex ?? 'column')}-${columnIndex}`}>
                  {column.render ? column.render(cellValue, row, rowIndex) : String(cellValue ?? '')}
                </td>;
              })}
            </tr>;
          })}
        </tbody>
      </table>
    </div>
    {!dataSource.length && <div className="ui-table-empty">{React.isValidElement(empty) ? empty : <Empty description={empty} />}</div>}
    {paginationEnabled && pageCount > 1 && <nav aria-label="表格分页" className="ui-table-pagination">
      <Button aria-label="上一页" disabled={currentPage <= 1} onClick={() => changePage(currentPage - 1)}>上一页</Button>
      <span>第 {currentPage} / {pageCount} 页</span>
      <Button aria-label="下一页" disabled={currentPage >= pageCount} onClick={() => changePage(currentPage + 1)}>下一页</Button>
    </nav>}
  </div>;
}

export const Collapse = ({ items = [], ...props }: AnyProps) =>
  <div {...props} className={cx('ui-collapse', props.className)}>
    {items.map((item: AnyProps) => <details key={item.key}><summary>{item.label}</summary>{item.children}</details>)}
  </div>;

const DescriptionItem = ({ children, label }: AnyProps) => <div><dt>{label}</dt><dd>{children}</dd></div>;

export const Descriptions = Object.assign(
  ({ bordered, children, column = 1, items = [], size, ...props }: AnyProps) =>
    <dl
      {...props}
      className={cx('ui-descriptions', bordered && 'is-bordered', props.className)}
      style={{ ...props.style, '--description-columns': Math.max(1, Number(column)) }}
    >
      {children}
      {items.map((item: AnyProps) => <DescriptionItem key={item.key} label={item.label}>{item.children}</DescriptionItem>)}
    </dl>,
  { Item: DescriptionItem },
);

const ListItem = ({ actions, children, ...props }: AnyProps) =>
  <li {...props} className={cx(props.onClick && 'is-interactive', props.className)}>{children}{actions && <Space>{actions}</Space>}</li>;

ListItem.Meta = ({ description, title }: AnyProps) =>
  <div><strong>{title}</strong><div>{description}</div></div>;

export const List = Object.assign(
  ({ dataSource = [], locale, renderItem, ...props }: AnyProps) =>
    <ul {...props} className={cx('ui-list', props.className)}>
      {dataSource.map((item: AnyProps, index: number) =>
        <React.Fragment key={item.id ?? index}>{renderItem(item, index)}</React.Fragment>)}
      {dataSource.length === 0 && <li className="ui-list-empty">{React.isValidElement(locale?.emptyText) ? locale.emptyText : <Empty description={locale?.emptyText} />}</li>}
    </ul>,
  { Item: ListItem },
);

export const Steps = ({ current = 0, items = [], ...props }: AnyProps) =>
  <ol {...props} className={cx('ui-steps', props.className)}>
    {items.map((item: AnyProps, index: number) =>
      <li className={cx(index <= current && 'is-active')} key={item.key ?? index}>{item.title}</li>)}
  </ol>;

const TimelineItem = ({ children }: AnyProps) => <li>{children}</li>;

export const Timeline = Object.assign(
  ({ children, items = [], ...props }: AnyProps) =>
    <ol {...props} className={cx('ui-timeline', props.className)}>
      {children}
      {items.map((item: AnyProps, index: number) => <li key={index}>{item.children}</li>)}
    </ol>,
  { Item: TimelineItem },
);
