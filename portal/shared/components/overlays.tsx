import {
  Button as HeroButton,
  Drawer as HeroDrawer,
  Modal as HeroModal,
  Separator,
  useOverlayState,
} from '@heroui/react';
import { X } from 'lucide-react';
import React, { useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { cx } from './common';
import type { AnyProps } from './common';

export const Dropdown = ({ children, menu, placement }: AnyProps) => {
  const [open, setOpen] = React.useState(false);
  const menuRef = React.useRef<HTMLDivElement>(null);
  const rootRef = React.useRef<HTMLDivElement>(null);
  const triggerProps = React.isValidElement(children) ? (children.props as AnyProps) : {};
  React.useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!rootRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    const closeOnViewportChange = (event: Event) => {
      const target = event.target;
      if (target instanceof Node
          && (rootRef.current?.contains(target) || menuRef.current?.contains(target))) {
        return;
      }
      setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    window.addEventListener('resize', closeOnViewportChange);
    window.addEventListener('scroll', closeOnViewportChange, true);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('resize', closeOnViewportChange);
      window.removeEventListener('scroll', closeOnViewportChange, true);
    };
  }, [open]);

  const trigger = React.isValidElement(children)
    ? React.cloneElement(children as React.ReactElement<AnyProps>, {
      'aria-expanded': open,
      'aria-haspopup': 'menu',
      onClick: (event: React.MouseEvent) => {
        triggerProps.onClick?.(event);
        if (!event.defaultPrevented && !triggerProps.disabled) setOpen((current) => !current);
      },
    })
    : <button aria-expanded={open} aria-haspopup="menu" onClick={() => setOpen((current) => !current)} type="button">{children}</button>;

  const rect = open ? rootRef.current?.getBoundingClientRect() : undefined;
  const alignLeft = placement === 'bottomLeft' || placement === 'topLeft';
  const alignTop = placement === 'topLeft' || placement === 'topRight';
  const menuStyle: React.CSSProperties | undefined = rect ? {
    bottom: alignTop ? window.innerHeight - rect.top + 4 : undefined,
    left: alignLeft ? rect.left : undefined,
    right: alignLeft ? undefined : window.innerWidth - rect.right,
    top: alignTop ? undefined : rect.bottom + 4,
  } : undefined;
  const popup = open && rect && createPortal(
    <div
      aria-label={triggerProps['aria-label'] ?? '操作菜单'}
      className="ui-dropdown-menu"
      ref={menuRef}
      role="menu"
      style={menuStyle}
    >
      {(menu?.items ?? []).filter(Boolean).map((item: AnyProps, index: number) =>
        item.type === 'divider'
          ? <Separator key={`divider-${index}`} />
          : <button
            className={cx(item.danger && 'is-danger')}
            disabled={item.disabled}
            key={item.key}
            onClick={(event) => {
              event.stopPropagation();
              const info = { domEvent: event, key: String(item.key) };
              item.onClick?.(info);
              menu?.onClick?.(info);
              setOpen(false);
            }}
            role="menuitem"
            type="button"
          >
            {item.icon}{item.label}
          </button>)}
    </div>,
    document.body,
  );

  return <div className="ui-dropdown" ref={rootRef}>
    {trigger}
    {popup}
  </div>;
};

export const Tooltip = ({ children, placement = 'top', title, ...props }: AnyProps) => {
  const id = React.useId();
  const trigger = React.isValidElement(children)
    ? React.cloneElement(children as React.ReactElement<AnyProps>, { 'aria-describedby': id })
    : <span aria-describedby={id} tabIndex={0}>{children}</span>;
  return <span {...props} className={cx('ui-tooltip-trigger', props.className)} data-placement={placement}>
    {trigger}
    <span className="ui-tooltip-content" id={id} role="tooltip">{title}</span>
  </span>;
};

export const Drawer = ({ children, extra, onClose, open, placement = 'right', title, width, ...props }: AnyProps) => {
  const state = useOverlayState({
    isOpen: open,
    onOpenChange: (isOpen) => { if (!isOpen) onClose?.(); },
  });
  if (!open) return null;
  return <HeroDrawer state={state}>
    <HeroDrawer.Trigger aria-hidden className="ui-controlled-overlay-trigger" />
    <HeroDrawer.Backdrop variant="opaque">
      <HeroDrawer.Content placement={placement}>
        <HeroDrawer.Dialog {...props} className={cx('ui-drawer', props.className)} style={{ ...props.style, width }}>
          <HeroDrawer.Header>
            <HeroDrawer.Heading>{title}</HeroDrawer.Heading>
            {extra}
            <HeroDrawer.CloseTrigger aria-label="关闭"><X aria-hidden size={18} /></HeroDrawer.CloseTrigger>
          </HeroDrawer.Header>
          <HeroDrawer.Body>{children}</HeroDrawer.Body>
        </HeroDrawer.Dialog>
      </HeroDrawer.Content>
    </HeroDrawer.Backdrop>
  </HeroDrawer>;
};

const ModalBase = ({
  cancelText = '取消',
  children,
  confirmLoading,
  footer,
  okButtonProps,
  okText = '确定',
  onCancel,
  onOk,
  open,
  showCancel = true,
  title,
  width,
  ...props
}: AnyProps) => {
  const state = useOverlayState({
    isOpen: open,
    onOpenChange: (isOpen) => { if (!isOpen) onCancel?.(); },
  });
  if (!open) return null;
  return <HeroModal state={state}>
    <HeroModal.Trigger aria-hidden className="ui-controlled-overlay-trigger" />
    <HeroModal.Backdrop>
      <HeroModal.Container>
        <HeroModal.Dialog className={cx('ui-modal', props.className)} style={{ ...props.style, width }}>
          <HeroModal.Header><HeroModal.Heading>{title}</HeroModal.Heading></HeroModal.Header>
          <HeroModal.Body className="ui-modal-body">{children}</HeroModal.Body>
          {footer !== null && <>
            <Separator />
            <HeroModal.Footer>
              {showCancel && <HeroButton onPress={onCancel} variant="outline">{cancelText}</HeroButton>}
              <HeroButton
                isPending={confirmLoading}
                onPress={onOk}
                variant={okButtonProps?.danger ? 'danger' : 'primary'}
              >
                {okText}
              </HeroButton>
            </HeroModal.Footer>
          </>}
        </HeroModal.Dialog>
      </HeroModal.Container>
    </HeroModal.Backdrop>
  </HeroModal>;
};

type DialogRequest = {
  cancelText?: string;
  content?: React.ReactNode;
  id: number;
  kind: 'confirm' | 'info';
  okButtonProps?: AnyProps;
  okText?: string;
  onOk?: () => unknown;
  title?: React.ReactNode;
  width?: number | string;
};

let dialogSequence = 0;
let dialogQueue: DialogRequest[] = [];
const dialogListeners = new Set<() => void>();
const emitDialogs = () => dialogListeners.forEach((listener) => listener());
const subscribeDialogs = (listener: () => void) => {
  dialogListeners.add(listener);
  return () => dialogListeners.delete(listener);
};
const getDialogs = () => dialogQueue;
const enqueueDialog = (request: Omit<DialogRequest, 'id'>) => {
  dialogQueue = [...dialogQueue, { ...request, id: ++dialogSequence }];
  emitDialogs();
};
const dismissDialog = (id: number) => {
  dialogQueue = dialogQueue.filter((request) => request.id !== id);
  emitDialogs();
};

export function DialogHost() {
  const dialogs = useSyncExternalStore(subscribeDialogs, getDialogs, getDialogs);
  const current = dialogs[0];
  const [pending, setPending] = React.useState(false);

  React.useEffect(() => setPending(false), [current?.id]);
  if (!current) return null;

  const confirm = async () => {
    setPending(true);
    try {
      await current.onOk?.();
      dismissDialog(current.id);
    } catch {
      setPending(false);
    }
  };

  return <ModalBase
    cancelText={current.cancelText}
    confirmLoading={pending}
    footer={current.kind === 'info' ? undefined : true}
    okText={current.okText ?? (current.kind === 'info' ? '知道了' : '确定')}
    onCancel={() => dismissDialog(current.id)}
    onOk={current.kind === 'info' ? () => dismissDialog(current.id) : confirm}
    open
    showCancel={current.kind === 'confirm'}
    title={current.title}
    width={current.width}
    okButtonProps={current.okButtonProps}
  >
    {current.content}
  </ModalBase>;
}

export const Modal = Object.assign(ModalBase, {
  confirm: ({ cancelText, content, okButtonProps, okText, onOk, title, width }: AnyProps) =>
    enqueueDialog({ cancelText, content, kind: 'confirm', okButtonProps, okText, onOk, title, width }),
  info: ({ content, title, width }: AnyProps) =>
    enqueueDialog({ content, kind: 'info', title, width }),
});
