import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Button, Card, Spin, Tag, Typography } from 'antd';
import { LoginOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';

const { Paragraph, Text, Title } = Typography;

interface Identity {
  accessToken: string;
  displayName: string;
  roles: string[];
  userId: string;
  logout: () => Promise<void>;
}

interface AuthGateProps {
  children: (identity: Identity) => ReactNode;
}

const devInsecure = import.meta.env.VITE_DEV_INSECURE === 'true';

function AuthGate({ children }: AuthGateProps) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(!devInsecure);
  const [error, setError] = useState<string | null>(null);

  const manager = useMemo(
    () =>
      new UserManager({
        authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8083/realms/ontology',
        client_id: 'ontology-portal',
        post_logout_redirect_uri: window.location.origin,
        redirect_uri: `${window.location.origin}/auth/callback`,
        response_type: 'code',
        scope: 'openid profile email',
        automaticSilentRenew: true,
        monitorSession: false,
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      }),
    [],
  );

  useEffect(() => {
    if (devInsecure) {
      return;
    }

    const loadIdentity = async () => {
      try {
        if (window.location.pathname === '/auth/callback') {
          const callbackUser = await manager.signinRedirectCallback();
          setUser(callbackUser);
          const realmAccess = decodeAccessToken(callbackUser.access_token).realm_access as { roles?: string[] } | undefined;
          const canBuild = realmAccess?.roles?.some((role) => role === 'Builder' || role === 'Admin');
          window.history.replaceState({}, '', canBuild ? '/data/connections' : '/ontology/explorer');
        } else {
          const storedUser = await manager.getUser();
          setUser(storedUser?.expired ? await manager.signinSilent() : storedUser);
        }
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : '无法完成身份验证');
      } finally {
        setLoading(false);
      }
    };

    const handleUserLoaded = (loadedUser: User) => setUser(loadedUser);
    const handleUserUnloaded = () => setUser(null);
    const handleSilentRenewError = () => {
      void manager.removeUser().finally(() => setUser(null));
    };

    manager.events.addUserLoaded(handleUserLoaded);
    manager.events.addUserUnloaded(handleUserUnloaded);
    manager.events.addSilentRenewError(handleSilentRenewError);

    void loadIdentity();

    return () => {
      manager.events.removeUserLoaded(handleUserLoaded);
      manager.events.removeUserUnloaded(handleUserUnloaded);
      manager.events.removeSilentRenewError(handleSilentRenewError);
    };
  }, [manager]);

  if (devInsecure) {
    return (
      <>
        <div className="insecure-banner">非生产身份模式：仅用于显式 dev-insecure 调试</div>
        {children({ accessToken: 'dev-insecure', displayName: '开发管理员', roles: ['Admin', 'Builder'], userId: 'dev-admin', logout: async () => undefined })}
      </>
    );
  }

  if (loading) {
    return (
      <div className="auth-page">
        <Spin size="large" />
        <Text>正在验证登录状态…</Text>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="auth-page">
        <Card className="login-card" variant="borderless">
          <div className="login-mark"><SafetyCertificateOutlined /></div>
          <Tag color="blue">OIDC + PKCE</Tag>
          <Title level={2}>登录 Ontology Platform</Title>
          <Paragraph>使用平台身份继续。浏览器只执行 Authorization Code + PKCE，不保存用户密码。</Paragraph>
          {error && <Paragraph type="danger">登录失败：{error}</Paragraph>}
          <Button
            block
            icon={<LoginOutlined />}
            onClick={() => void manager.signinRedirect()}
            size="large"
            type="primary"
          >
            使用 Keycloak 登录
          </Button>
        </Card>
      </div>
    );
  }

  const profileRealmAccess = user.profile.realm_access as { roles?: string[] } | undefined;
  const tokenRealmAccess = decodeAccessToken(user.access_token).realm_access as { roles?: string[] } | undefined;
  return children({
    accessToken: user.access_token,
    displayName: user.profile.name ?? user.profile.preferred_username ?? user.profile.sub,
    roles: tokenRealmAccess?.roles ?? profileRealmAccess?.roles ?? [],
    userId: user.profile.sub,
    logout: () => manager.signoutRedirect(),
  });
}

function decodeAccessToken(token: string): Record<string, unknown> {
  try {
    const payload = token.split('.')[1];
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export default AuthGate;
