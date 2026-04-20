FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY default.conf.template /etc/nginx/templates/default.conf.template

ENV PORT=80
# Railway: set BACKEND_URL env var on the frontend service
# pointing to the backend's private networking URL and port
# Example: BACKEND_URL=http://backend.railway.internal:8080
ENV BACKEND_URL=${BACKEND_URL:-http://localhost:8080}

CMD ["nginx", "-g", "daemon off;"]
