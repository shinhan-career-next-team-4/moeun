import os

# 테스트가 lifespan을 띄워도 저장소 안 logs/에 파일 로그를 쓰지 않는다(#316). 파일 로그 테스트는 tmp 경로를 직접 넘긴다
os.environ.setdefault("LOG_RETENTION_DAYS", "0")
