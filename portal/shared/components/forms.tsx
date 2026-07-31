import {
  Button as HeroButton,
  Checkbox as HeroCheckbox,
  ComboBox,
  Input as HeroInput,
  ListBox,
  Popover,
  Radio as HeroRadio,
  RadioGroup as HeroRadioGroup,
  SearchField as HeroSearchField,
  Switch as HeroSwitch,
  TextArea as HeroTextArea,
  ToggleButton,
  ToggleButtonGroup,
} from '@heroui/react';
import { Check, ChevronDown, Search as SearchIcon, X } from 'lucide-react';
import React, { useEffect, useState } from 'react';
import { cx } from './common';
import type { AnyProps } from './common';
export { Form } from './form';

export const Checkbox = React.forwardRef<HTMLInputElement, AnyProps>(
  ({ children, checked, className, disabled, indeterminate, onChange, ...props }, ref) =>
    <HeroCheckbox
      {...props}
      aria-label={props['aria-label'] ?? (typeof children === 'string' ? children : undefined)}
      className={cx('form-checkbox', className)}
      isDisabled={Boolean(disabled)}
      isIndeterminate={Boolean(indeterminate)}
      isSelected={Boolean(checked)}
      name={props.name}
      onChange={(selected) => onChange?.({ target: { checked: selected }, currentTarget: { checked: selected } })}
      ref={ref as never}
    >
      <HeroCheckbox.Content>
        <HeroCheckbox.Control className="form-checkbox-control">
          <HeroCheckbox.Indicator>
            <Check aria-hidden size={13} strokeWidth={3} />
          </HeroCheckbox.Indicator>
        </HeroCheckbox.Control>
        {children && <span className="form-checkbox-label">{children}</span>}
      </HeroCheckbox.Content>
    </HeroCheckbox>,
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
    const input = <HeroInput
      {...props}
      className={cx('form-input', size && `form-input-${size}`, bordered === false && 'is-borderless', (prefix || suffix || allowClear) && 'flex-1', !prefix && !suffix && !allowClear && props.className)}
      fullWidth
      onChange={change}
      onKeyDown={keyDown}
      ref={ref}
      type={type}
      value={currentValue}
    />;
    if (!prefix && !suffix && !allowClear) return input;
    return <span className={cx('form-input-affix flex w-full items-center gap-2', size && `form-input-${size}`, bordered === false && 'is-borderless', props.className)}>
      {prefix && <span className="form-input-prefix inline-flex shrink-0 text-gray-500">{prefix}</span>}
      {input}
      {allowClear && String(currentValue).length > 0 && <HeroButton aria-label="清空输入" isIconOnly onPress={clear} size="sm" variant="tertiary"><X aria-hidden size={13} /></HeroButton>}
      {suffix && <span className="form-input-suffix inline-flex shrink-0 text-gray-500">{suffix}</span>}
    </span>;
  },
);

const TextArea = React.forwardRef<HTMLTextAreaElement, AnyProps>(
  ({ autoSize, onInput, onKeyDown, onPressEnter, rows, size, ...props }, ref) =>
    <HeroTextArea
      {...props}
      className={cx('form-input', 'form-textarea', size && `form-input-${size}`, props.className)}
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

const Search = ({ allowClear, className, defaultValue, disabled, enterButton, onChange, onSearch, prefix, size, value, ...props }: AnyProps) => {
  const [internalValue, setInternalValue] = useState(defaultValue ?? '');
  const currentValue = value ?? internalValue;
  const search = () => onSearch?.(String(currentValue));
  const buttonContent = React.isValidElement(enterButton)
    ? enterButton
    : typeof enterButton === 'string'
      ? enterButton
      : <SearchIcon aria-hidden size={16} />;
  return <HeroSearchField
    aria-label={props['aria-label'] ?? props.placeholder ?? '搜索'}
    className={cx('search-field flex w-full min-w-0 items-stretch gap-2', className)}
    fullWidth
    isDisabled={Boolean(disabled)}
    onChange={(nextValue) => {
      if (value === undefined) setInternalValue(nextValue);
      onChange?.({ target: { value: nextValue }, currentTarget: { value: nextValue } });
    }}
    onSubmit={onSearch}
    value={String(currentValue)}
  >
    <HeroSearchField.Group className="w-full min-w-0 shadow-none">
      <HeroSearchField.SearchIcon>{prefix}</HeroSearchField.SearchIcon>
      <HeroSearchField.Input
        aria-describedby={props['aria-describedby']}
        aria-invalid={props['aria-invalid']}
        className="min-w-0"
        id={props.id}
        onKeyDown={(event) => {
          if (event.key === 'Enter') props.onPressEnter?.(event);
          props.onKeyDown?.(event);
        }}
        placeholder={props.placeholder}
      />
      {allowClear && <HeroSearchField.ClearButton />}
      {enterButton && <HeroButton
        aria-label={typeof enterButton === 'string' ? undefined : '搜索'}
        className="search-field-button h-full shrink-0 rounded-l-none"
        isIconOnly={typeof enterButton !== 'string'}
        onPress={search}
        size={size === 'large' ? 'lg' : size === 'small' ? 'sm' : 'md'}
        variant="primary"
      >
        {buttonContent}
      </HeroButton>}
    </HeroSearchField.Group>
  </HeroSearchField>;
};

const Password = React.forwardRef<HTMLInputElement, AnyProps>((props, ref) => <InputBase {...props} ref={ref} type="password" />);

export const Input = Object.assign(InputBase, { Password, Search, TextArea });

export const InputNumber = React.forwardRef<HTMLInputElement, AnyProps>(
  ({ max, min, onChange, value, ...props }, ref) =>
    <HeroInput
      {...props}
      className={cx('form-input', 'form-number-field', props.className)}
      fullWidth
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
  const [query, setQuery] = useState('');
  const multiple = mode === 'multiple';
  const uniqueOptions = [...new Map<string, AnyProps>(
    options.map((option: AnyProps) => [String(option.value), option]),
  ).values()];
  const selectedValues = multiple ? (Array.isArray(value) ? value.map(String) : []) : value == null || value === '' ? [] : [String(value)];
  const selectedOptions = uniqueOptions.filter((option) => selectedValues.includes(String(option.value)));
  const filteredOptions = uniqueOptions.filter((option) => optionText(option.label ?? option.value).toLowerCase().includes(query.trim().toLowerCase()));

  if (multiple) {
    const displayValue = selectedOptions.length === 0
      ? props.placeholder ?? '请选择'
      : selectedOptions.length <= 2
        ? selectedOptions.map((option) => optionText(option.label ?? option.value)).join('、')
        : `已选择 ${selectedOptions.length} 项`;
    return <Popover
      onOpenChange={(open) => {
        if (open) setQuery('');
        props.onOpenChange?.(open);
      }}
    >
      <Popover.Trigger
        aria-describedby={props['aria-describedby']}
        aria-disabled={props.disabled || loading}
        aria-invalid={props['aria-invalid']}
        aria-label={props['aria-label'] ?? props.placeholder}
        className={cx('form-select form-select-multiple form-select-trigger flex min-h-9 w-full items-center gap-2 rounded-md border border-gray-300 bg-white px-3 text-sm', (props.disabled || loading) && 'pointer-events-none opacity-50', props.className)}
        id={props.id}
        style={props.style}
      >
        <span className={cx('form-select-value min-w-0 flex-1 truncate text-left', selectedOptions.length === 0 && 'text-gray-400')}>{displayValue}</span>
        <ChevronDown aria-hidden className="form-select-chevron" size={15} />
      </Popover.Trigger>
      <Popover.Content className="form-select-popover min-w-56" placement="bottom">
        <Popover.Dialog className="grid gap-2 p-2">
          {allowClear && selectedValues.length > 0 && <HeroButton
            className="justify-start"
            onPress={() => onChange?.([])}
            size="sm"
            variant="tertiary"
          >
            <X aria-hidden size={14} />清除选择
          </HeroButton>}
          {(props.showSearch || uniqueOptions.length > 8) && <HeroInput
            aria-label="筛选选项"
            autoFocus
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索选项"
            value={query}
          />}
          <ListBox
            aria-label={props['aria-label'] ?? props.placeholder ?? '选项'}
            items={filteredOptions}
            onSelectionChange={(keys) => onChange?.(keys === 'all' ? uniqueOptions.map((option) => String(option.value)) : [...keys].map(String))}
            selectedKeys={new Set(selectedValues)}
            selectionMode="multiple"
          >
            {(option: AnyProps) => <ListBox.Item
              id={String(option.value)}
              isDisabled={option.disabled}
              textValue={optionText(option.label ?? option.value)}
            >
              {option.label ?? option.value}
              <ListBox.ItemIndicator><Check aria-hidden size={15} /></ListBox.ItemIndicator>
            </ListBox.Item>}
          </ListBox>
        </Popover.Dialog>
      </Popover.Content>
    </Popover>;
  }

  return <ComboBox
    aria-describedby={props['aria-describedby']}
    aria-invalid={props['aria-invalid']}
    aria-label={props['aria-label'] ?? props.placeholder ?? '选项'}
    className={cx('form-select', props.className)}
    fullWidth
    isDisabled={Boolean(props.disabled || loading)}
    items={uniqueOptions}
    onOpenChange={props.onOpenChange}
    onSelectionChange={(key) => onChange?.(key == null ? undefined : String(key))}
    selectedKey={selectedValues[0] ?? null}
    style={props.style}
  >
    <ComboBox.InputGroup>
      <HeroInput
        aria-describedby={props['aria-describedby']}
        aria-invalid={props['aria-invalid']}
        id={props.id}
        placeholder={props.placeholder ?? '请选择'}
      />
      {allowClear && selectedValues.length > 0 && <button
        aria-label="清除选择"
        className="form-select-clear"
        onClick={() => onChange?.(undefined)}
        type="button"
      >
        <X aria-hidden size={14} />
      </button>}
      <ComboBox.Trigger aria-label="打开选项"><ChevronDown aria-hidden size={15} /></ComboBox.Trigger>
    </ComboBox.InputGroup>
    <ComboBox.Popover>
      <ListBox items={uniqueOptions}>
        {(option: AnyProps) => <ListBox.Item
          id={String(option.value)}
          isDisabled={option.disabled}
          textValue={optionText(option.label ?? option.value)}
        >
          {option.label ?? option.value}
          <ListBox.ItemIndicator><Check aria-hidden size={15} /></ListBox.ItemIndicator>
        </ListBox.Item>}
      </ListBox>
    </ComboBox.Popover>
  </ComboBox>;
};

export const Switch = ({ checked, className, disabled, onChange, ...props }: AnyProps) =>
  <HeroSwitch
    {...props}
    aria-label={props['aria-label'] ?? '开关'}
    className={cx('toggle-switch', className)}
    isDisabled={Boolean(disabled)}
    isSelected={Boolean(checked)}
    onChange={(selected) => onChange?.(selected)}
  >
    <HeroSwitch.Content>
      <HeroSwitch.Control>
        <HeroSwitch.Thumb />
      </HeroSwitch.Control>
    </HeroSwitch.Content>
  </HeroSwitch>;

export const Segmented = ({ onChange, options = [], value, ...props }: AnyProps) =>
  <ToggleButtonGroup
    {...props}
    className={cx('segmented-control', props.className)}
    onSelectionChange={(keys) => {
      const selected = [...keys][0];
      if (selected !== undefined) onChange?.(String(selected));
    }}
    selectedKeys={new Set(value == null ? [] : [String(value)])}
    selectionMode="single"
    size={props.size === 'small' ? 'sm' : props.size === 'large' ? 'lg' : 'md'}
  >
    {options.map((item: AnyProps | string) => {
      const option = typeof item === 'object' ? item : { label: item, value: item };
      return <ToggleButton id={String(option.value)} key={String(option.value)}>{option.label}</ToggleButton>;
    })}
  </ToggleButtonGroup>;

const RadioBase = ({ children, className, disabled, value, ...props }: AnyProps) => {
  return <HeroRadio
    {...props}
    className={cx('form-radio', className)}
    isDisabled={Boolean(disabled)}
    name={props.name}
    value={String(value)}
  >
    <HeroRadio.Content>
      <HeroRadio.Control className="form-radio-control"><HeroRadio.Indicator /></HeroRadio.Control>
      <span>{children}</span>
    </HeroRadio.Content>
  </HeroRadio>;
};

const RadioGroup = ({ children, onChange, options = [], value, ...props }: AnyProps) => {
  return <HeroRadioGroup
      {...props}
      className={cx('form-radio-group', props.className)}
      onChange={(nextValue) => onChange?.(nextValue)}
      value={value == null ? undefined : String(value)}
    >
      {options.length
        ? options.map((item: AnyProps) => <RadioBase disabled={item.disabled} key={item.value} value={item.value}>{item.label ?? item.value}</RadioBase>)
        : children}
    </HeroRadioGroup>;
};

export const Radio = Object.assign(RadioBase, { Group: RadioGroup });
