FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    NASGAME_DATA_DIR=/data \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /app

# --- Install runtime dependencies ---
RUN apt-get update && apt-get install -y --no-install-recommends \
    libmagic1 \
    file \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# --- Install Python dependencies ---
# Prefer the pre-bundled offline venv if present (faster, no network during build).
# Otherwise fall back to pip install.
COPY server/requirements.txt /app/requirements.txt
COPY venv.tar* /tmp/venv.tar

RUN if [ -f /tmp/venv.tar ]; then \
      mkdir -p /opt/nasgame-venv \
      && tar -xf /tmp/venv.tar -C /opt/nasgame-venv \
      && rm /tmp/venv.tar; \
    else \
      python -m pip install --no-cache-dir -r /app/requirements.txt; \
    fi

ENV PATH="/opt/nasgame-venv/bin:$PATH" \
    PYTHONPATH="/opt/nasgame-venv/lib/python3.11/site-packages"

# --- Copy application code ---
COPY server/app /app/app
COPY server/web /app/web

RUN mkdir -p /data

EXPOSE 14322

# Health check: hit the SPA's index page (no auth required, returns HTML).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request,sys; \
r=urllib.request.urlopen('http://localhost:14322/', timeout=3); \
sys.exit(0 if r.status==200 else 1)" || exit 1

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "14322", "--workers", "1"]