import { I18nProvider, Toast } from '@heroui/react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { DialogHost } from './shared/components/overlays';
import '@heroui/styles/css';
import './shared/styles/global.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <I18nProvider locale="zh-CN">
    <App />
    <DialogHost />
    <Toast.Provider placement="top" />
  </I18nProvider>,
);
