import DashboardEditorShell from '../editor/DashboardEditorShell';
export default function DashboardEditorPage(props: { accessToken: string; dashboardId: string; navigate: (path: string) => void }) { return <DashboardEditorShell {...props} />; }
