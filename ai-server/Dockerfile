# 운영 이미지(#386). 빌드 컨텍스트는 저장소 루트다:
#   docker build --platform linux/amd64 -f ai-server/Dockerfile -t moeun-ai-server .
# 문구표(copy/copy.ko.yml)와 적재 허락 목록(scripts/ingest/allowlist.yml)을 저장소 루트 기준 경로로 읽기 때문에
# 이미지 안에도 /repo 아래에 저장소와 같은 배치로 둔다. 들어가는 파일은 Dockerfile.dockerignore가 고른다
FROM ghcr.io/astral-sh/uv:0.9.7-python3.12-bookworm-slim

# 헬스 체크와 RAG 적재 스크립트(scripts/ingest/ingest.sh)가 curl을 쓴다
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home moeun

# UV_NO_CACHE: uv 캐시를 이미지 층에 남기지 않는다
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_NO_SYNC=1 \
    UV_NO_CACHE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /repo/ai-server

# 의존성 층을 먼저 받아 두면 코드만 바뀐 빌드는 이 층을 다시 쓴다
COPY ai-server/pyproject.toml ai-server/uv.lock ai-server/.python-version ./
RUN uv sync --frozen --no-dev --no-install-project
COPY ai-server/app app
RUN uv sync --frozen --no-dev
COPY copy/copy.ko.yml /repo/copy/copy.ko.yml
COPY scripts/ingest /repo/scripts/ingest

# logs: 로그 파일, docs/raw: RAG 적재 때 내려받는 원본 PDF. 쓰는 폴더만 넘긴다(.venv까지 chown하면 층이 수백 MB 늘어난다)
RUN mkdir -p logs docs/raw && chown moeun logs docs/raw
USER moeun
EXPOSE 8000
ENV PATH="/repo/ai-server/.venv/bin:$PATH"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
