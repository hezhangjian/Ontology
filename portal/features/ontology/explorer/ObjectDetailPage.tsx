import { ArrowLeftOutlined, CopyOutlined } from "@/shared/icons";
import { Button, Tag } from "@/shared/components/actions";
import { Descriptions } from "@/shared/components/data";
import { Alert, Skeleton } from "@/shared/components/feedback";
import { Card, Space } from "@/shared/components/layout";
import { Typography } from "@/shared/components/typography";
import { useEffect, useMemo, useState } from "react";
import { ExplorerApi } from "./explorer.service";
import type { ObjectDetail } from "./explorer.types";

export default function ObjectDetailPage({
  navigate,
  objectId,
  objectTypeId,
}: {
  navigate: (path: string) => void;
  objectId: string;
  objectTypeId: string;
}) {
  const api = useMemo(() => new ExplorerApi(), []);
  const [detail, setDetail] = useState<ObjectDetail>();
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    setError("");
    setDetail(undefined);
    void api
      .object(objectTypeId, objectId)
      .then((object) => {
        if (active) setDetail(object);
      })
      .catch((cause: Error) => {
        if (active) setError(cause.message);
      });
    return () => {
      active = false;
    };
  }, [api, objectId, objectTypeId]);

  if (error) return <Alert message={error} showIcon type="error" />;
  if (!detail) return <Skeleton active />;

  const visibleProperties = detail.objectType.properties.filter(
    (property) => !property.sensitive,
  );

  return (
    <div className="object-detail-page">
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate(`/ontology/explorer/${objectTypeId}`)}
        type="text"
      >
        返回探索
      </Button>
      <div className="object-detail-header">
        <div>
          <Space>
            <Typography.Title level={2}>{detail.title}</Typography.Title>
            <Tag>{detail.objectType.displayName}</Tag>
            <Tag color="success">质量通过</Tag>
          </Space>
          <Typography.Text type="secondary">
            更新 {new Date(detail.updatedAt).toLocaleString()}
          </Typography.Text>
        </div>
        <Button
          icon={<CopyOutlined />}
          onClick={() => void navigator.clipboard.writeText(window.location.href)}
        >
          复制链接
        </Button>
      </div>
      <div className="object-detail-overview">
        <Card className="object-detail-properties" title="对象属性">
          {visibleProperties.length > 0 ? (
            <Descriptions
              bordered
              column={1}
              items={visibleProperties.map((property) => ({
                key: property.id,
                label: property.displayName,
                children: format(detail.properties[property.displayName]),
              }))}
            />
          ) : (
            <Alert message="当前对象没有可见属性" type="info" />
          )}
        </Card>
        <Card className="object-detail-metadata" title="对象信息">
          <Descriptions
            column={1}
            items={[
              {
                key: "type",
                label: "对象类型",
                children: detail.objectType.displayName,
              },
              {
                key: "updated",
                label: "更新时间",
                children: new Date(detail.updatedAt).toLocaleString(),
              },
              {
                key: "properties",
                label: "可见属性",
                children: `${visibleProperties.length} 个`,
              },
            ]}
          />
          {detail.redactedFields.length > 0 && (
            <Alert
              message={`${detail.redactedFields.length} 个字段已按权限隐藏`}
              type="info"
            />
          )}
        </Card>
      </div>
    </div>
  );
}

function format(input: unknown) {
  if (input == null)
    return <Typography.Text type="secondary">未设置</Typography.Text>;
  return typeof input === "object" ? JSON.stringify(input) : String(input);
}
