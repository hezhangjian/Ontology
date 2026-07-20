import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './app/App';
import AuthGate from './app/AuthGate';
import './shared/styles/global.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          borderRadius: 10,
          colorPrimary: '#3157d5',
          colorBgLayout: '#f4f6fa',
          colorText: '#172033',
          fontFamily:
            "Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
        },
      }}
    >
      <AuthGate>
        {(identity) => <App accessToken={identity.accessToken} displayName={identity.displayName} roles={identity.roles} userId={identity.userId} onLogout={identity.logout} />}
      </AuthGate>
    </ConfigProvider>
  </React.StrictMode>,
);
