# Paper-trading service image. Build from the trading/ directory:
#   docker build -f deploy/Dockerfile -t aitrader .
FROM python:3.13-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY config ./config
RUN pip install --no-cache-dir -e ".[infra]" \
    && useradd --create-home --uid 10001 aitrader \
    && mkdir -p /app/data /app/run \
    && chown -R aitrader /app/data /app/run
USER aitrader
ENV PYTHONUNBUFFERED=1
CMD ["python", "-m", "aitrader.paper", "daemon"]
