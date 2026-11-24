#!/usr/bin/env bash
# 운영 EC2에서 배포한다(#386). GitHub Actions가 SSM으로 root 권한으로 부른다:
#   cd /opt/moeun/repo && git fetch --all && git checkout <sha> && deploy/deploy.sh <sha>
# 1) Parameter Store에서 env 파일을 만든다  2) 금지 값을 검사한다  3) ECR에서 <sha> 이미지를 받아 띄운다  4) 헬스 체크
# 운영 값은 Parameter Store(/moeun/prod/shared, /moeun/prod/backend, /moeun/prod/ai)에만 있다. 키 목록은 docs/deploy.md
set -euo pipefail

TAG="${1:?사용법: deploy.sh <이미지 태그(git sha)>}"
REGION=ap-northeast-2
RUNTIME="${MOEUN_RUNTIME:-/opt/moeun/runtime}"   # 바꾸지 않는다(compose가 절대경로로 참조). 검사 스크립트용
DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"
CONTAINER_UID=10001   # 두 Dockerfile의 moeun 사용자. 로그 디렉터리를 쓸 수 있어야 한다

log() { echo "[deploy] $*"; }
fail() { echo "[deploy] 실패: $*" >&2; exit 1; }

# Parameter Store 경로 아래 값을 "이름=값" 줄로 적는다. 이름은 경로의 마지막 부분(/moeun/prod/backend/DB_URL → DB_URL)
params() {
  aws ssm get-parameters-by-path --region "$REGION" --path "$1" --with-decryption \
    --query 'Parameters[].[Name,Value]' --output text |
    while IFS=$'\t' read -r name value; do printf '%s=%s\n' "${name##*/}" "$value"; done
}

# 파일에서 키 값을 읽는다(없으면 빈 문자열)
value_of() { grep -m1 "^$2=" "$1" | cut -d= -f2- || true; }

require() {
  local file="$1"; shift
  for key in "$@"; do
    [ -n "$(value_of "$file" "$key")" ] || fail "$(basename "$file")에 ${key}가 없다. Parameter Store를 확인한다"
  done
}

log "Parameter Store에서 env 파일을 만든다"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
shared="$(params /moeun/prod/shared)"
touch "$tmp/backend.env" "$tmp/ai.env" && chmod 600 "$tmp/backend.env" "$tmp/ai.env"   # 비밀값: root만 읽는다
{ echo "$shared"; params /moeun/prod/backend; } > "$tmp/backend.env"
{ echo "$shared"; params /moeun/prod/ai; } > "$tmp/ai.env"

require "$tmp/backend.env" DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET INTERNAL_API_KEY CORS_ALLOWED_ORIGINS
require "$tmp/ai.env" DATABASE_URL INTERNAL_API_KEY ANTHROPIC_API_KEY OPENAI_API_KEY

# 같은 키가 shared와 서비스 양쪽에 있으면 검사한 값과 compose가 쓰는 값(마지막 줄)이 달라질 수 있다
for f in "$tmp/backend.env" "$tmp/ai.env"; do
  dup="$(cut -d= -f1 "$f" | sort | uniq -d | tr '\n' ' ')"
  [ -z "$dup" ] || fail "$(basename "$f")에 같은 키가 두 번 있다: $dup(shared와 서비스 경로를 확인한다)"
done

# 운영 금지 값: local 프로필(Secure 쿠키가 꺼진다), Secure 끈 쿠키. Spring이 받아들이는 표기를 모두 막는다
lower() { tr '[:upper:]' '[:lower:]' | tr -d ' '; }
for f in "$tmp/backend.env" "$tmp/ai.env"; do
  for key in SPRING_PROFILES_ACTIVE SPRING_PROFILES_DEFAULT SPRING_PROFILES_INCLUDE; do
    case ",$(value_of "$f" "$key" | lower)," in
      *,local,*) fail "운영에서 ${key}에 local을 쓸 수 없다" ;;
    esac
  done
done
case "$(value_of "$tmp/backend.env" JWT_COOKIE_SECURE | lower)" in
  false|off|no|0) fail "운영에서 JWT_COOKIE_SECURE를 끌 수 없다" ;;
esac

mkdir -p "$RUNTIME/data/seed" "$RUNTIME/logs/backend" "$RUNTIME/logs/ai-server"
chown "$CONTAINER_UID" "$RUNTIME/logs/backend" "$RUNTIME/logs/ai-server"
mv "$tmp/backend.env" "$RUNTIME/backend.env"
mv "$tmp/ai.env" "$RUNTIME/ai.env"

# nginx가 읽을 인증서가 없으면 nginx가 뜨지 않는다. 먼저 호스트 certbot으로 발급한다(docs/deploy.md)
cert="$(sed -n 's/^[[:space:]]*ssl_certificate[[:space:]][[:space:]]*\([^;]*\);.*/\1/p' "$DEPLOY_DIR/nginx/default.conf" | head -1)"
[ -f "$cert" ] || fail "인증서($cert)가 없다. docs/deploy.md의 certbot 첫 발급을 먼저 한다"

ls "$RUNTIME"/data/seed/*.csv > /dev/null 2>&1 || log "경고: $RUNTIME/data/seed에 ETF 가격 파일이 없다. ETF가 들어간 계산을 할 수 없다"

account="$(aws sts get-caller-identity --query Account --output text)"
registry="$account.dkr.ecr.$REGION.amazonaws.com"
cat > "$RUNTIME/compose.env" <<ENV
BACKEND_IMAGE=$registry/moeun-backend:$TAG
AI_IMAGE=$registry/moeun-ai-server:$TAG
ENV

log "ECR 로그인, 이미지 받기($TAG)"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$registry" > /dev/null
compose() { docker compose --env-file "$RUNTIME/compose.env" -f "$COMPOSE_FILE" "$@"; }
compose pull
compose up -d --remove-orphans

log "헬스 체크를 기다린다"
for service in backend ai-server; do
  for _ in $(seq 1 60); do
    status="$(docker inspect --format '{{.State.Health.Status}}' "moeun-$service-1" 2>/dev/null || echo missing)"
    [ "$status" = healthy ] && break
    sleep 5
  done
  # 컨테이너 로그에는 사용자 발화가 있다. 저장소가 공개라 Actions 로그(SSM 출력)로 보내지 않는다
  [ "$status" = healthy ] || fail "${service}가 healthy가 되지 않았다($status). EC2에서 docker logs moeun-$service-1로 확인한다"
  log "$service healthy"
done

# compose는 nginx 정의가 같으면 컨테이너를 그대로 둔다. checkout으로 바뀐 설정을 검사하고 다시 읽힌다
docker exec moeun-nginx-1 nginx -t -q || fail "nginx 설정 오류. EC2에서 docker exec moeun-nginx-1 nginx -t로 확인한다"
docker exec moeun-nginx-1 nginx -s reload
log "nginx 설정 다시 읽음"

# 디스크(t3.small)를 아끼려고 지금 쓰지 않는 이미지를 모두 지운다. 되돌리기는 ECR의 이전 태그로 한다
docker image prune -af > /dev/null
log "완료: $TAG"
