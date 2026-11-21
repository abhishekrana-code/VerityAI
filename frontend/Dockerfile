# ---- build ---------------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# VITE_API_BASE_URL is intentionally unset: nginx proxies /api on the same
# origin, which is what the production build in docs/deployment.md does too.
RUN npm run build

# ---- runtime -------------------------------------------------------------
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
