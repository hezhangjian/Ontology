ARG NGINX_IMAGE=nginx:1.27.0-alpine3.19
ARG NODE_IMAGE=node:22.22.0-alpine3.23
FROM ${NODE_IMAGE} AS build

WORKDIR /workspace
COPY portal/package.json portal/pnpm-lock.yaml portal/pnpm-workspace.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile
COPY portal/ ./
RUN pnpm build

FROM ${NGINX_IMAGE}
COPY portal/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
