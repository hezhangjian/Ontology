import type { ReactNode } from 'react';

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

export default function AuthGate({ children }: AuthGateProps) {
  return children({
    accessToken: '',
    displayName: '本地用户',
    roles: ['Admin', 'Builder', 'Viewer'],
    userId: 'local-user',
    logout: async () => undefined,
  });
}
