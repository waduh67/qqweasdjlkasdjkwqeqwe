
# ============================================================================
# ftth-web — SPA React di-bundel Vite, disajikan Nginx.
# Konteks build = folder web/.
#   docker build -t ftth-web ./web
# ============================================================================

# ---- Tahap build: bundel SPA dengan Vite ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Tahap runtime: Nginx menyajikan file statis ----
FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
