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
