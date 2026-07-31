import {
  Button as HeroButton,
  Drawer as HeroDrawer,
  Dropdown as HeroDropdown,
  Modal as HeroModal,
  Separator,
  Tooltip as HeroTooltip,
  useOverlayState,
} from '@heroui/react';
import { X } from 'lucide-react';
import React, { useSyncExternalStore } from 'react';
import { cx } from './common';
import type { AnyProps } from './common';

const dropdownPlacement = (placement?: string) => ({
  bottomLeft: 'bottom start',
  bottomRight: 'bottom end',
  topLeft: 'top start',
  topRight: 'top end',
}[placement ?? 'bottomLeft'] ?? placement) as AnyProps['placement'];

export const Dropdown = ({ children, menu, placement }: AnyProps) => {
  const items = (menu?.items ?? []).filter(Boolean);
  const triggerLabel = React.isValidElement(children)
    ? (children.props as AnyProps)['aria-label']
    : undefined;
  const activate = (key: React.Key) => {
    const item = items.find((candidate: AnyProps) => String(candidate.key) === String(key));
    const info = { domEvent: { stopPropagation() {} }, key: String(key) };
    item?.onClick?.(info);
    menu?.onClick?.(info);
  };

  return <HeroDropdown>
    {React.isValidElement(children)
      ? children
      : <HeroDropdown.Trigger>{children}</HeroDropdown.Trigger>}
    <HeroDropdown.Popover
      className="action-menu-popover"
      onClick={(event) => event.stopPropagation()}
      placement={dropdownPlacement(placement)}
    >
      <HeroDropdown.Menu aria-label={triggerLabel ?? '操作菜单'} onAction={activate}>
        {items.map((item: AnyProps, index: number) =>
          item.type === 'divider'
            ? <Separator key={`divider-${index}`} />
            : <HeroDropdown.Item
              id={String(item.key)}
              isDisabled={item.disabled}
              key={item.key}
              variant={item.danger ? 'danger' : 'default'}
            >
              {item.icon}{item.label}
            </HeroDropdown.Item>)}
      </HeroDropdown.Menu>
    </HeroDropdown.Popover>
  </HeroDropdown>;
};

export const Tooltip = ({ children, placement = 'top', title, ...props }: AnyProps) => {
  const trigger = React.isValidElement(children) ? children : <span tabIndex={0}>{children}</span>;
  return <HeroTooltip {...props}>
    <HeroTooltip.Trigger className={cx('help-tooltip-trigger', props.className)}>
      {trigger}
    </HeroTooltip.Trigger>
    <HeroTooltip.Content className="help-tooltip-content" placement={placement} showArrow>
      {title}
    </HeroTooltip.Content>
  </HeroTooltip>;
};

export const Drawer = ({ children, extra, onClose, open, placement = 'right', title, width, ...props }: AnyProps) => {
  const state = useOverlayState({
    isOpen: open,
    onOpenChange: (isOpen) => { if (!isOpen) onClose?.(); },
  });
  if (!open) return null;
  return <HeroDrawer state={state}>
    <HeroDrawer.Trigger aria-hidden className="pointer-events-none fixed size-px overflow-hidden opacity-0 [clip-path:inset(50%)]" />
    <HeroDrawer.Backdrop variant="opaque">
      <HeroDrawer.Content placement={placement}>
        <HeroDrawer.Dialog {...props} className={cx('h-full max-w-[92vw] overflow-auto', props.className)} style={{ ...props.style, width }}>
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
    <HeroModal.Trigger aria-hidden className="pointer-events-none fixed size-px overflow-hidden opacity-0 [clip-path:inset(50%)]" />
    <HeroModal.Backdrop>
      <HeroModal.Container>
        <HeroModal.Dialog className={cx('max-h-[86vh] max-w-[min(620px,92vw)] overflow-auto', props.className)} style={{ ...props.style, width }}>
          <HeroModal.Header><HeroModal.Heading>{title}</HeroModal.Heading></HeroModal.Header>
          <HeroModal.Body>{children}</HeroModal.Body>
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
