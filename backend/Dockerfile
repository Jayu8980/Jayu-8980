FROM node:22-slim
WORKDIR /app
COPY backend/package*.json backend/
RUN cd backend && npm ci --omit=dev
COPY backend backend
COPY data data
ENV DATA_DIR=/app/data RUNS_DIR=/app/runs PORT=8080
EXPOSE 8080
CMD ["node", "backend/src/server.js"]
