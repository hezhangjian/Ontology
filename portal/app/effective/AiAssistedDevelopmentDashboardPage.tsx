import { Result } from '@/shared/components/feedback';

export default function AssistedDevelopmentDashboardPage() {
  return (
    <Result
      status="info"
      subTitle="前端开发入口位于 portal/app/effective，后端能力位于 com.hezhangjian.ontology.app.effective。"
      title="辅助研发看板待开发"
    />
  );
}
