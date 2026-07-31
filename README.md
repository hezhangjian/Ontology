# Ontology

Ontology 是一个以“数据、本体、应用、AIP”为闭环的平台。`dev` 是 `main` 的下游，使用相同的根目录结构。

## 技术基线

- Java 25 + Spring Boot 3.5 单体后端
- Java 17 + Flink 2.3 独立作业构建
- React 19 + TypeScript 7 + Vite 8
- HeroUI + ECharts + Lucide
- PostgreSQL、MinIO、Pulsar、Flink、HugeGraph、OpenSearch、SkyWalking

## 目录

- `src/main/`：唯一后端应用
- `src/flink/`：Flink 作业源码，使用 `flink-job` Maven profile 构建
- `src/test/`、`src/flink-test/`：后端与 Flink 测试
- `portal/`：仅前端业务源码
- `openapi/ontology.yaml`：REST API 唯一契约
- `docker/docker-compose.yml`：唯一 Compose 文件，支持基础设施和完整应用部署

前端的 `package.json`、`pnpm-lock.yaml`、TypeScript 和 Vite 配置均位于仓库根目录。

## 开发配置

整个项目只使用 `docker/.env`。Compose 和本地 Spring Boot 都会读取它。

首次克隆后执行一次：

```bash
cp docker/.env.example docker/.env
```

模板中已经提供固定开发值，无需执行 `openssl`。不要把
`CONNECTION_MASTER_KEY`、`FLINK_WORKLOAD_KEY` 或
`APPLICATION_TOKEN_SECRET` 改成 `1`：

- 两个 AES key 必须是 Base64 编码的 32 字节。
- application token secret 至少需要 24 字节。
- MinIO 密码应至少为 8 个字符。

`docker/.env` 被 Git 忽略，可以在其中填写本机的 `DEEPSEEK_API_KEY`。

## Mac 本地开发

保持以下配置：

```dotenv
PULSAR_ADVERTISED_HOST=localhost
PULSAR_LISTENER_NAME=external
```

后端各组件默认连接 `localhost`，本地开发无需配置组件地址。

本地开发时，终端一启动开源基础组件：

```bash
make compose-up
```

终端二构建 Flink 作业并启动 Java 25 后端：

```bash
./mvnw -Pflink-job package
./mvnw spring-boot:run
```

终端三安装依赖并启动前端：

```bash
pnpm install
pnpm dev
```

前端地址为 `http://localhost:5173`，后端地址为
`http://localhost:4242`。

需要构建并启动前端、后端及全部基础组件时执行：

```bash
make deploy
```

完整部署的前端地址为 `http://localhost:9080`。

## 公司 Linux + Windows

假设 Linux 服务器地址为 `100.xx.xx.xx`。

Linux 服务器的 `docker/.env`：

```dotenv
PULSAR_ADVERTISED_HOST=100.xx.xx.xx
PULSAR_LISTENER_NAME=external
```

Linux 完整部署前，确保已安装 Docker Engine、Docker Compose 插件和
GNU Make。然后准备配置并启动前端、后端和全部基础组件：

```bash
cp docker/.env.example docker/.env
# 编辑 docker/.env，将 PULSAR_ADVERTISED_HOST 改为 Linux 服务器地址
make deploy
```

默认访问地址：

- 前端：`http://100.xx.xx.xx:9080`
- 后端 API：由前端的 `/v1` 和 `/actuator` 路径反向代理，不需要开放
  `4242` 端口

`FRONTEND_PORT` 可在 `docker/.env` 中修改，默认值为 `9080`。部署使用多阶段
镜像构建，Linux 主机不需要单独安装 Java、Maven、Node.js 或 pnpm。重新部署
时再次执行 `make deploy`；停止完整环境执行 `make deploy-down`。

如果采用“Linux 只运行基础组件、Windows 运行前后端”的开发方式，
Linux 执行 `make compose-up`。Windows 开发机准备 `docker/.env`：

```powershell
Copy-Item docker/.env.example docker/.env
```

然后配置 Linux 服务器上的组件地址：

```dotenv
FLINK_URL=http://100.xx.xx.xx:8081
HUGEGRAPH_HOST=100.xx.xx.xx
MINIO_URL=http://100.xx.xx.xx:9000
OPENSEARCH_URL=http://100.xx.xx.xx:9200
POSTGRES_URL=jdbc:postgresql://100.xx.xx.xx:5432/ontology
PULSAR_ADVERTISED_HOST=100.xx.xx.xx
PULSAR_LISTENER_NAME=external
PULSAR_URL=pulsar://100.xx.xx.xx:6650
```

Windows 终端一构建 Flink 作业并启动本地 Java 25 后端：

```powershell
.\mvnw.cmd -Pflink-job package
.\mvnw.cmd spring-boot:run
```

Windows 终端二启动本地前端：

```powershell
pnpm install
pnpm dev
```

Windows 需要能够访问 Linux 的 `5432`、`6650`、`8080`、`8081`、
`8182`、`9000` 和 `9200` 端口。管理页面端口按需开放：
`5601`、`8082`、`8083`、`8084`、`8088` 和 `9001`。

## 常用命令

```bash
make compose-up
make compose-down
make compose-config
make deploy
make deploy-down
make test
pnpm build
```
