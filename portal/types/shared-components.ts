import type React from 'react';

// Additional visual props are forwarded by the component adapters.
type VisualProps = Record<string, any>;

export type ButtonProps = VisualProps & {
  onClick?: React.MouseEventHandler<HTMLButtonElement>;
};

export type CheckboxProps = VisualProps & {
  onChange?: React.ChangeEventHandler<HTMLInputElement>;
};

export type InputProps = VisualProps & {
  onChange?: React.ChangeEventHandler<HTMLInputElement>;
  onInput?: React.FormEventHandler<HTMLInputElement>;
  onKeyDown?: React.KeyboardEventHandler<HTMLInputElement>;
  onPressEnter?: React.KeyboardEventHandler<HTMLInputElement>;
  onSearch?: (value: string) => void;
};

export type TextAreaProps = VisualProps & {
  onChange?: React.ChangeEventHandler<HTMLTextAreaElement>;
  onInput?: React.FormEventHandler<HTMLTextAreaElement>;
  onKeyDown?: React.KeyboardEventHandler<HTMLTextAreaElement>;
  onPressEnter?: React.KeyboardEventHandler<HTMLTextAreaElement>;
};

export type ValueControlProps<T> = VisualProps & {
  onChange?: (value: T) => void;
  value?: T;
};

export type SelectProps<T extends string | number | boolean = string> = VisualProps & {
  onOpenChange?: (open: boolean) => void;
  options?: Array<VisualProps & { value: T }>;
} & ({
  mode: 'multiple';
  onChange?: (value: T[]) => void;
  value?: T[];
} | {
  mode?: undefined;
  onChange?: (value: T) => void;
  value?: T;
});

export type MenuProps = VisualProps & {
  items?: VisualProps[];
  onClick?: (info: { key: string }) => void;
};

export type DropdownProps = VisualProps & {
  menu?: VisualProps & {
    items?: VisualProps[];
    onClick?: (info: { domEvent: { stopPropagation: () => void }; key: string }) => void;
  };
};

export type SelectDynamicProps<T extends string | number | boolean = string> = VisualProps & {
  mode?: 'multiple';
  onChange?: (value: T | T[]) => void;
  onOpenChange?: (open: boolean) => void;
  options?: Array<VisualProps & { value: T }>;
  value?: T | T[];
};

export type ColumnsType<T = Record<string, any>> = Array<VisualProps & {
  render?: (value: any, row: T, index: number) => React.ReactNode;
  sorter?: (left: T, right: T) => number;
}>;

export type TableProps<T> = VisualProps & {
  columns?: ColumnsType<T>;
  dataSource?: T[];
  onRow?: (row: T) => Omit<React.HTMLAttributes<HTMLTableRowElement>, 'onClick'> & { onClick?: () => void };
  pagination?: false | (VisualProps & { onChange?: (page: number, pageSize: number) => void });
  rowKey?: string | ((row: T, index: number) => React.Key);
  rowSelection?: {
    onChange?: (keys: string[], rows: T[]) => void;
    selectedRowKeys?: React.Key[];
  };
};

export type ListProps<T> = VisualProps & {
  dataSource?: T[];
  renderItem: (item: T, index: number) => React.ReactNode;
};
