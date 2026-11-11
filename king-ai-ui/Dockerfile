FROM node:26-alpine AS build
WORKDIR /app

COPY king-ai-ui/package.json king-ai-ui/package-lock.json ./
RUN npm ci

COPY king-ai-ui/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine

# envsubst template: the stock unprivileged entrypoint renders this into
# /etc/nginx/conf.d/default.conf at container start. Only env-DEFINED
# variables (${API_UPSTREAM}) are substituted; nginx $host/$remote_addr
# variables are preserved.
COPY king-ai-ui/nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

ENV API_UPSTREAM=api:8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
