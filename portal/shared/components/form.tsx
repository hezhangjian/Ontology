import React, { createContext, useContext, useEffect, useState, useSyncExternalStore } from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

type FormRule = {
  max?: number;
  message?: string;
  pattern?: RegExp;
  required?: boolean;
  whitespace?: boolean;
};

type FormApi<T = any> = {
  getFieldValue: (name: string) => any;
  getFieldsValue: (names?: any) => T;
  resetFields: () => void;
  setFieldValue: (name: string, value: any) => void;
  setFieldsValue: (values: Partial<T>) => void;
  validateFields: (names?: any) => Promise<T>;
  __errors?: Map<string, string>;
  __initialValues?: AnyProps;
  __initialized?: boolean;
  __listeners?: Set<() => void>;
  __rules?: Map<string, FormRule[]>;
  __values?: AnyProps;
};

const createForm = <T = any,>(): FormApi<T> => {
  const values: AnyProps = {};
  const listeners = new Set<() => void>();
  const rules = new Map<string, FormRule[]>();
  const errors = new Map<string, string>();
  const notify = () => listeners.forEach((listener) => listener());
  const form: FormApi<T> = {
    getFieldValue: (name) => values[name],
    getFieldsValue: (names) => Array.isArray(names)
      ? Object.fromEntries(names.map((name) => [name, values[name]])) as T
      : { ...values } as T,
    resetFields: () => {
      Object.keys(values).forEach((key) => delete values[key]);
      Object.assign(values, form.__initialValues ?? {});
      errors.clear();
      notify();
    },
    setFieldValue: (name, value) => {
      values[name] = value;
      errors.delete(name);
      notify();
    },
    setFieldsValue: (next) => {
      Object.assign(values, next);
      Object.keys(next as AnyProps).forEach((name) => errors.delete(name));
      notify();
    },
    validateFields: async (names) => {
      errors.clear();
      const fields = Array.isArray(names) ? names : [...rules.keys()];
      fields.forEach((name) => {
        const value = values[name];
        for (const rule of rules.get(name) ?? []) {
          const empty = value === undefined || value === null || value === '' || (Array.isArray(value) && value.length === 0);
          if (rule.required && (empty || (rule.whitespace && String(value).trim() === ''))) {
            errors.set(name, rule.message ?? '此项为必填项');
            break;
          }
          if (!empty && rule.max !== undefined && String(value).length > rule.max) {
            errors.set(name, rule.message ?? `不能超过 ${rule.max} 个字符`);
            break;
          }
          if (!empty && rule.pattern && !rule.pattern.test(String(value))) {
            errors.set(name, rule.message ?? '格式不正确');
            break;
          }
        }
      });
      notify();
      if (errors.size) throw new Error(errors.values().next().value ?? '请检查表单');
      return { ...values } as T;
    },
    __errors: errors,
    __listeners: listeners,
    __rules: rules,
    __values: values,
  };
  return form;
};

const FormContext = createContext<{ form: FormApi; onValuesChange?: (changed: AnyProps, values: AnyProps) => void } | null>(null);

function FormBase<T>({ children, form = createForm(), initialValues = {}, layout, onFinish, onValuesChange, requiredMark, ...props }: AnyProps) {
  const [, redraw] = useState(0);
  if (!form.__initialized) {
    form.__initialValues = { ...initialValues };
    Object.assign(form.__values ?? {}, initialValues);
    form.__initialized = true;
  }
  useEffect(() => {
    const redrawForm = () => redraw((current) => current + 1);
    form.__listeners?.add(redrawForm);
    return () => { form.__listeners?.delete(redrawForm); };
  }, [form]);
  return <FormContext.Provider value={{ form, onValuesChange }}>
    <form {...props} onSubmit={(event) => { event.preventDefault(); void form.validateFields().then(onFinish); }}>{children}</form>
  </FormContext.Provider>;
}

function FormItem({ children, className, extra, hidden, initialValue, label, name, required, rules = [], style, valuePropName }: AnyProps) {
  const context = useContext(FormContext);
  const form = context?.form;
  if (name) {
    form?.__rules?.set(name, rules);
    if (form?.getFieldValue(name) === undefined && initialValue !== undefined) form?.setFieldValue(name, initialValue);
  }
  if (hidden) return null;
  const fieldId = name ? `field-${String(name).replaceAll(/[^A-Za-z0-9_-]/g, '-')}` : undefined;
  const child = React.isValidElement(children) && name ? React.cloneElement(children as React.ReactElement<any>, {
    'aria-label': (children as any).props['aria-label'] ?? (typeof label === 'string' ? label : undefined),
    id: (children as any).props.id ?? fieldId,
    [valuePropName ?? 'value']: form?.getFieldValue(name) ?? (children as any).props[valuePropName ?? 'value'] ?? (valuePropName === 'checked' ? false : ''),
    onChange: (event: any) => {
      const value = valuePropName === 'checked'
        ? event?.target ? event.target.checked : event
        : event?.target ? event.target.value : event;
      form?.setFieldValue(name, value);
      context?.onValuesChange?.({ [name]: value }, form?.getFieldsValue() ?? {});
      (children as any).props.onChange?.(event);
    },
  }) : children;
  const error = name ? form?.__errors?.get(name) : undefined;
  const requiredField = required ?? rules.some((rule: FormRule) => rule.required);
  return <div className={cx('ui-form-item', error && 'has-error', className)} style={style}>
    {label && <label htmlFor={fieldId}>{label}{requiredField && <span aria-hidden className="ui-form-required"> *</span>}</label>}
    {child}
    {extra && <small>{extra}</small>}
    {error && <small className="ui-form-error">{error}</small>}
  </div>;
}

export const Form = Object.assign(FormBase, {
  Item: FormItem,
  useForm: <T = any,>() => useState<FormApi<T>>(() => createForm<T>()),
  useWatch: <T = any,>(name: string, form: FormApi<any>) => useSyncExternalStore(
    (listener) => {
      form?.__listeners?.add(listener);
      return () => form?.__listeners?.delete(listener);
    },
    () => form?.getFieldValue(name) as T,
  ),
});
