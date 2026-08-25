# ---------------------------------------------------------------- build
FROM node:24-alpine AS build
WORKDIR /build

# Lockfile first: dependencies change far less often than source.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ------------------------------------------------------------------ run
# nginx rather than a Node server: the output is static files, and nginx also
# does the API and WebSocket proxying that keeps everything same-origin.
FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# 127.0.0.1, not localhost. Inside the container localhost resolves to ::1
# first, and nginx listens on IPv4 only - so the check gets "connection
# refused" from a perfectly healthy server.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD ["sh", "-c", "wget -q --spider http://127.0.0.1/ || exit 1"]
