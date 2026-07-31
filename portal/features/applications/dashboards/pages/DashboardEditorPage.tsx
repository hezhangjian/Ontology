import DashboardEditorShell from '../editor/DashboardEditorShell';
export default function DashboardEditorPage(props: { dashboardId: string; navigate: (path: string) => void }) { return <DashboardEditorShell {...props} />; }
