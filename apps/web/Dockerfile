# 说明：FROM 使用国内镜像前缀；若网络可直连 Docker Hub，可改回 docker.io/library/...。
# build stage
FROM docker.1panel.live/library/node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com
COPY . .
RUN npm run build

# serve stage
FROM docker.1panel.live/library/nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
