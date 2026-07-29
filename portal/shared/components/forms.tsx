import { Check, ChevronDown, Search as SearchIcon, X } from 'lucide-react';
import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { cx } from './common';
import type { AnyProps } from './common';
export { Form } from './form';

export const Checkbox = React.forwardRef<HTMLInputElement, AnyProps>(
  ({ children, checked, indeterminate, onChange, ...props }, ref) =>
    <label className={cx('ui-checkbox', props.disabled && 'is-disabled', props.className)}>
      <input
        {...props}
        checked={Boolean(checked)}
        onChange={onChange}
        ref={(element) => {
          if (element) element.indeterminate = Boolean(indeterminate);
          if (typeof ref === 'function') ref(element);
          else if (ref) ref.current = element;
        }}
        type="checkbox"
      />
      <span aria-hidden className="ui-checkbox-control">{(checked || indeterminate) && <Check size={13} strokeWidth={3} />}</span>
      {children && <span className="ui-checkbox-label">{children}</span>}
    </label>,
);

const InputBase = React.forwardRef<HTMLInputElement, AnyProps>(
  ({ allowClear, bordered = true, defaultValue, onChange, onKeyDown, onPressEnter, prefix, size, suffix, type = 'text', value, ...props }, ref) => {
    const [internalValue, setInternalValue] = useState(defaultValue ?? '');
    const currentValue = value ?? internalValue;
    useEffect(() => {
      if (value !== undefined) setInternalValue(value);
    }, [value]);
    const keyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
      if (event.key === 'Enter') onPressEnter?.(event);
      onKeyDown?.(event);
    };
    const change = (event: React.ChangeEvent<HTMLInputElement>) => {
      if (value === undefined) setInternalValue(event.target.value);
      onChange?.(event);
    };
    const clear = () => {
      if (value === undefined) setInternalValue('');
      onChange?.({ target: { value: '' }, currentTarget: { value: '' } });
    };
    const input = <input
      {...props}
      className={cx('ui-input', size && `ui-input-${size}`, bordered === false && 'is-borderless', !prefix && !suffix && !allowClear && props.className)}
      onChange={change}
      onKeyDown={keyDown}
      ref={ref}
      type={type}
      value={currentValue}
    />;
    if (!prefix && !suffix && !allowClear) return input;
    return <span className={cx('ui-input-affix', size && `ui-input-${size}`, bordered === false && 'is-borderless', props.className)}>
      {prefix && <span className="ui-input-prefix">{prefix}</span>}
      {input}
      {allowClear && String(currentValue).length > 0 && <button aria-label="清空输入" className="ui-input-clear" onClick={clear} type="button"><X aria-hidden size={13} /></button>}
      {suffix && <span className="ui-input-suffix">{suffix}</span>}
    </span>;
  },
);

const TextArea = React.forwardRef<HTMLTextAreaElement, AnyProps>(
  ({ autoSize, onInput, onKeyDown, onPressEnter, rows, size, ...props }, ref) =>
    <textarea
      {...props}
      className={cx('ui-input', 'ui-textarea', size && `ui-input-${size}`, props.className)}
      onInput={(event) => {
        if (autoSize) {
          event.currentTarget.style.height = 'auto';
          event.currentTarget.style.height = `${event.currentTarget.scrollHeight}px`;
        }
        onInput?.(event);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter') onPressEnter?.(event);
        onKeyDown?.(event);
      }}
      ref={ref}
      rows={rows ?? (typeof autoSize === 'object' ? autoSize.minRows : undefined)}
    />,
);

const Search = ({ enterButton, onSearch, ...props }: AnyProps) => {
  const [internalValue, setInternalValue] = useState(props.defaultValue ?? '');
  const currentValue = props.value ?? internalValue;
  const search = () => onSearch?.(String(currentValue));
  return <span className={cx('ui-search', enterButton && 'has-button')}>
    <InputBase
      {...props}
      defaultValue={undefined}
      onChange={(event: React.ChangeEvent<HTMLInputElement>) => {
        if (props.value === undefined) setInternalValue(event.target.value);
        props.onChange?.(event);
      }}
      onKeyDown={(event: React.KeyboardEvent<HTMLInputElement>) => {
        if (event.key === 'Enter') onSearch?.(event.currentTarget.value);
        props.onKeyDown?.(event);
      }}
      prefix={props.prefix ?? <SearchIcon aria-hidden size={15} />}
      value={currentValue}
    />
    {enterButton && <button className="ui-search-button" onClick={search} type="button">
      {React.isValidElement(enterButton) ? enterButton : typeof enterButton === 'string' ? enterButton : <SearchIcon aria-hidden size={16} />}
    </button>}
  </span>;
};

const Password = React.forwardRef<HTMLInputElement, AnyProps>((props, ref) => <InputBase {...props} ref={ref} type="password" />);

export const Input = Object.assign(InputBase, { Password, Search, TextArea });

export const InputNumber = React.forwardRef<HTMLInputElement, AnyProps>(
  ({ max, min, onChange, value, ...props }, ref) =>
    <input
      {...props}
      className={cx('ui-input', 'ui-number-field', props.className)}
      max={max}
      min={min}
      onChange={(event) => onChange?.(event.target.value === '' ? null : Number(event.target.value))}
      ref={ref}
      type="number"
      value={value ?? ''}
    />,
);

const optionText = (value: React.ReactNode): string => {
  if (value === null || value === undefined || typeof value === 'boolean') return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(optionText).join('');
  if (React.isValidElement(value)) return optionText((value.props as AnyProps).children);
  return String(value);
};

export const Select = ({ allowClear, loading, mode, onChange, options = [], value, ...props }: AnyProps) => {
  const rootRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const multiple = mode === 'multiple';
  const uniqueOptions = [...new Map<string, AnyProps>(
    options.map((option: AnyProps) => [String(option.value), option]),
  ).values()];
  const selectedValues = multiple ? (Array.isArray(value) ? value.map(String) : []) : value == null || value === '' ? [] : [String(value)];
  const selectedOptions = uniqueOptions.filter((option) => selectedValues.includes(String(option.value)));
  const filteredOptions = uniqueOptions.filter((option) => optionText(option.label ?? option.value).toLowerCase().includes(query.trim().toLowerCase()));

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!rootRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    const viewportChange = (event: Event) => {
      const target = event.target;
      if (target instanceof Node
          && (rootRef.current?.contains(target) || menuRef.current?.contains(target))) {
        return;
      }
      setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    window.addEventListener('resize', viewportChange);
    window.addEventListener('scroll', viewportChange, true);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('resize', viewportChange);
      window.removeEventListener('scroll', viewportChange, true);
    };
  }, [open]);

  const selectValue = (nextValue: string) => {
    if (multiple) {
      const next = selectedValues.includes(nextValue)
        ? selectedValues.filter((item) => item !== nextValue)
        : [...selectedValues, nextValue];
      onChange?.(next);
      return;
    }
    onChange?.(nextValue);
    setOpen(false);
  };
  const rect = open ? rootRef.current?.getBoundingClientRect() : undefined;
  const openAbove = rect ? window.innerHeight - rect.bottom < 320 && rect.top > window.innerHeight - rect.bottom : false;
  const menu = open && rect && createPortal(
    <div
      className="ui-select-popover"
      ref={menuRef}
      role="listbox"
      style={{
        bottom: openAbove ? window.innerHeight - rect.top + 5 : undefined,
        left: Math.min(rect.left, window.innerWidth - Math.max(rect.width, 220) - 12),
        minWidth: rect.width,
        top: openAbove ? undefined : rect.bottom + 5,
        width: Math.max(rect.width, 220),
      }}
    >
      {(props.showSearch || uniqueOptions.length > 8) && <div className="ui-select-search">
        <SearchIcon aria-hidden size={15} />
        <input aria-label="筛选选项" autoFocus onChange={(event) => setQuery(event.target.value)} placeholder="搜索选项" value={query} />
      </div>}
      <div className="ui-select-options">
        {filteredOptions.map((option) => {
          const optionValue = String(option.value);
          const selected = selectedValues.includes(optionValue);
          return <button
            aria-selected={selected}
            className={cx('ui-select-option', selected && 'is-selected')}
            disabled={option.disabled}
            key={optionValue}
            onClick={() => selectValue(optionValue)}
            role="option"
            type="button"
          >
            <span>{option.label ?? option.value}</span>
            {selected && <Check aria-hidden size={15} strokeWidth={2.5} />}
          </button>;
        })}
        {filteredOptions.length === 0 && <div className="ui-select-empty">没有匹配选项</div>}
      </div>
    </div>,
    document.body,
  );

  return <div className={cx('ui-select', multiple && 'ui-select-multiple', props.className)} ref={rootRef} style={props.style}>
    <button
      aria-expanded={open}
      aria-haspopup="listbox"
      aria-label={props['aria-label'] ?? props.placeholder}
      className="ui-select-trigger"
      disabled={props.disabled || loading}
      id={props.id}
      onClick={() => {
        setQuery('');
        setOpen((current) => !current);
      }}
      type="button"
    >
      <span className={cx('ui-select-value', selectedOptions.length === 0 && 'is-placeholder')}>
        {selectedOptions.length === 0
          ? props.placeholder ?? '请选择'
          : multiple
            ? selectedOptions.length <= 2
              ? selectedOptions.map((option) => option.label ?? option.value).map(optionText).join('、')
              : `已选择 ${selectedOptions.length} 项`
            : selectedOptions[0]?.label ?? selectedOptions[0]?.value}
      </span>
      {allowClear && selectedValues.length > 0 && <span
        aria-label="清除选择"
        className="ui-select-clear"
        onClick={(event) => {
          event.stopPropagation();
          onChange?.(multiple ? [] : undefined);
        }}
        role="button"
      >
        <X aria-hidden size={14} />
      </span>}
      <ChevronDown aria-hidden className={cx('ui-select-chevron', open && 'is-open')} size={15} />
    </button>
    {menu}
  </div>;
};

export const Switch = ({ checked, onChange, ...props }: AnyProps) =>
  <button
    {...props}
    aria-checked={Boolean(checked)}
    className={cx('ui-switch', checked && 'is-checked', props.className)}
    disabled={props.disabled}
    onClick={() => onChange?.(!checked)}
    role="switch"
    type="button"
  >
    <span />
  </button>;

export const Segmented = ({ onChange, options = [], value, ...props }: AnyProps) =>
  <div {...props} className={cx('ui-segmented', props.className)}>
    {options.map((item: AnyProps | string) => {
      const option = typeof item === 'object' ? item : { label: item, value: item };
      return <button className={cx(value === option.value && 'is-active')} key={String(option.value)} onClick={() => onChange?.(option.value)} type="button">{option.label}</button>;
    })}
  </div>;

const RadioContext = createContext<{ name: string; onChange?: (value: string) => void; value?: string } | null>(null);

const RadioBase = ({ children, value, ...props }: AnyProps) => {
  const group = useContext(RadioContext);
  const checked = group ? String(group.value) === String(value) : Boolean(props.checked);
  return <label className={cx('ui-radio', props.disabled && 'is-disabled', props.className)}>
    <input
      checked={checked}
      disabled={props.disabled}
      name={group?.name ?? props.name}
      onChange={() => group ? group.onChange?.(value) : props.onChange?.(value)}
      type="radio"
      value={value}
    />
    <span aria-hidden className="ui-radio-control" />
    <span>{children}</span>
  </label>;
};

const RadioGroup = ({ children, onChange, options = [], value, ...props }: AnyProps) => {
  const name = React.useId();
  return <RadioContext.Provider value={{ name, onChange, value }}>
    <div {...props} className={cx('ui-radio-group', props.className)} role="radiogroup">
      {options.length
        ? options.map((item: AnyProps) => <RadioBase disabled={item.disabled} key={item.value} value={item.value}>{item.label ?? item.value}</RadioBase>)
        : children}
    </div>
  </RadioContext.Provider>;
};

export const Radio = Object.assign(RadioBase, { Group: RadioGroup });
