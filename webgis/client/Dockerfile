# Multi-Stage: Angular-Build in Node 24, Auslieferung über nginx.
# nginx proxied /geoserver an den Compose-Service — Client und OGC-Dienste
# sind damit same-origin (kein CORS nötig).
FROM node:24-alpine AS build
WORKDIR /build
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/client/browser /usr/share/nginx/html
# Schreibt beim Containerstart stack.json (gewollte Module) ins Web-Root.
COPY docker/10-stack-config.sh /docker-entrypoint.d/10-stack-config.sh
# CRLF-Schutz (Windows-Checkouts) + ausführbar machen.
RUN sed -i 's/\r$//' /docker-entrypoint.d/10-stack-config.sh \
 && chmod +x /docker-entrypoint.d/10-stack-config.sh
