# 운영 배포 (#386)

```
브라우저 ── https://<프론트>.vercel.app ── Vercel(frontend/, 정적 파일)
                     │ /api/* (vercel.json rewrites)
                     ▼
            https://api.example.com ── EC2 t3.small (ap-northeast-2, Docker Compose, 프로젝트 이름 moeun)
                                         ├─ nginx      :80 → 443, :443 /api/**·/actuator/health만 공개
                                         ├─ backend    :8080 (내부망)
                                         └─ ai-server  :8000 (내부망, Spring만 부른다)
                                                   │
                                                   ▼
                                         RDS PostgreSQL 16 + pgvector
```

- 브라우저는 `/api` 상대 경로만 부른다. Vercel이 백엔드로 넘기므로 브라우저 입장에서는 같은 출처다. 로그인 쿠키는 Domain 없이 `Secure; SameSite=Lax; HttpOnly`로 프론트 도메인에 붙는다(코드 그대로).
- Vercel은 브라우저의 `Origin`(Vercel 주소)을 그대로 넘긴다. 그래서 `CORS_ALLOWED_ORIGINS`에 Vercel 주소가 있어야 POST가 403이 아니다. **Preview 배포 주소는 매번 달라서 Preview에서는 로그인이 안 된다.** 필요하면 그 주소를 쉼표로 더한다.
- 배포: `main`에 push(아래 경로가 바뀐 경우) → GitHub Actions가 OIDC로 AWS 역할을 받아 이미지 두 개를 ECR에 `<git sha>` 태그로 push → SSM으로 EC2에서 `git fetch --all` → `git checkout <sha>` → `deploy/deploy.sh <sha>`.
  - 배포 워크플로가 도는 경로: `backend/**`, `ai-server/**`, `copy/**`, `scripts/ingest/**`, `deploy/**`, `.github/workflows/deploy.yml`. `frontend/`만 바뀐 push로는 돌지 않는다. Actions 탭에서 수동 실행(workflow_dispatch)도 된다.
  - 배포 전에 테스트를 돌리지 않는다. 테스트 게이트는 #387.
- 운영 값은 Parameter Store에만 둔다. 저장소에는 `backend/.env.example`, `ai-server/.env.example`, `frontend/.env.example`만 있다. GitHub에는 Secrets를 쓰지 않고 Variables만 쓴다.

## 채울 값

| 값 | 어디에 | 지금 |
|---|---|---|
| 백엔드 도메인 | `deploy/nginx/default.conf`(server_name 2곳, 인증서 경로 2곳), `frontend/vercel.json`(destination) | `api.example.com` |
| Vercel 프로덕션 주소 | Parameter Store `/moeun/prod/backend/CORS_ALLOWED_ORIGINS` | 없음 |
| 배포 역할 ARN | GitHub Variables `AWS_DEPLOY_ROLE_ARN` | 없음 |
| EC2 인스턴스 ID | GitHub Variables `EC2_INSTANCE_ID` | 없음 |
| AWS 계정 ID | 아래 IAM 정책의 `<계정 ID>` | 없음 |
| 인증서 알림 메일 | certbot 첫 발급 명령의 `-m` | 없음 |
| 비밀값 | Parameter Store(아래 키 목록) | 없음 |

도메인은 바꾼 뒤 커밋해서 배포한다. 비밀값이 아니다.

## 1. ECR

리전 `ap-northeast-2`에 private 리포지토리 두 개를 만든다.

- `moeun-backend`
- `moeun-ai-server`

태그는 git sha다. 이미지가 쌓이므로 수명 주기 규칙(예: "이미지 30개 초과분 만료")을 하나 두면 좋다. 되돌리기는 이전 태그로 하므로 너무 적게 남기지 않는다.

## 2. RDS (PostgreSQL + pgvector)

- 엔진 PostgreSQL 16, DB 이름 `moeun`. 퍼블릭 액세스 끔.
- 보안 그룹: 인바운드 5432를 **EC2 보안 그룹에서만** 연다.
- pgvector: `db/schema.sql`의 `CREATE EXTENSION IF NOT EXISTS vector;`를 backend가 기동할 때 실행한다(`SQL_INIT_MODE=always`). 마스터 사용자로 접속하면 따로 할 일이 없다. 다른 사용자를 쓰면 먼저 마스터로 `CREATE EXTENSION vector;`를 한 번 실행한다.
- 접속 문자열은 TLS를 쓴다:
  - backend `DB_URL`: `jdbc:postgresql://<RDS 엔드포인트>:5432/moeun?sslmode=require`
  - ai-server `DATABASE_URL`: `postgresql://<사용자>:<URL 인코딩한 비밀번호>@<RDS 엔드포인트>:5432/moeun?sslmode=require`

## 3. IAM

### GitHub Actions 배포 역할(OIDC)

1. IAM → ID 제공업체 → OpenID Connect 추가: URL `https://token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com`.
2. 역할을 만들고 신뢰 정책을 아래처럼 둔다. `main` 브랜치에서 실행된 워크플로만 역할을 받는다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<계정 ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:shinhan-career-next-team-4/moeun:ref:refs/heads/main"
      }
    }
  }]
}
```

3. 권한 정책:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    {
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
        "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"
      ],
      "Resource": [
        "arn:aws:ecr:ap-northeast-2:<계정 ID>:repository/moeun-backend",
        "arn:aws:ecr:ap-northeast-2:<계정 ID>:repository/moeun-ai-server"
      ]
    },
    {
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ec2:ap-northeast-2:<계정 ID>:instance/<인스턴스 ID>",
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"
      ]
    },
    { "Effect": "Allow", "Action": "ssm:GetCommandInvocation", "Resource": "*" }
  ]
}
```

4. 역할 ARN을 GitHub 저장소 Settings → Secrets and variables → Actions → **Variables**에 `AWS_DEPLOY_ROLE_ARN`으로 넣는다. 인스턴스 ID는 `EC2_INSTANCE_ID`로 넣는다.

### EC2 인스턴스 역할

- 관리형 정책 `AmazonSSMManagedInstanceCore`(SSM 명령 받기), `AmazonEC2ContainerRegistryReadOnly`(이미지 받기)
- 인라인 정책(Parameter Store 읽기):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "ssm:GetParametersByPath",
    "Resource": "arn:aws:ssm:ap-northeast-2:<계정 ID>:parameter/moeun/prod/*"
  }]
}
```

SecureString을 기본 키(`aws/ssm`)로 암호화하면 KMS 권한을 따로 주지 않아도 된다. 고객 관리형 키를 쓰면 그 키의 `kms:Decrypt`를 더한다.

## 4. Parameter Store

모두 `ap-northeast-2`, 이름의 마지막 부분이 환경변수 이름이 된다(`/moeun/prod/backend/DB_URL` → `DB_URL`). 비밀값은 SecureString으로 둔다. **값은 한 줄이어야 한다**(deploy.sh가 줄 단위로 읽는다). 하위 경로(`/moeun/prod/backend/x/KEY`)는 읽지 않는다. 같은 키를 shared와 서비스 양쪽에 두면 deploy.sh가 배포를 멈춘다.

| 경로 | 키 | 종류 | 필수 | 메모 |
|---|---|---|---|---|
| `/moeun/prod/shared` | `INTERNAL_API_KEY` | SecureString | ✓ | Spring ↔ ai-server 내부 호출 키. 예: `openssl rand -hex 32` |
| `/moeun/prod/backend` | `DB_URL` | String | ✓ | 위 RDS 형식 |
| | `DB_USERNAME` | String | ✓ | |
| | `DB_PASSWORD` | SecureString | ✓ | |
| | `JWT_SECRET` | SecureString | ✓ | 32바이트 이상. 예: `openssl rand -base64 48` |
| | `CORS_ALLOWED_ORIGINS` | String | ✓ | Vercel 프로덕션 주소(`https://...`, 끝 `/` 없이) |
| | `DEMO_ACCOUNTS` | SecureString | | `id:비밀번호,id:비밀번호` |
| | `SQL_INIT_MODE`, `LOG_RETENTION_DAYS` | String | | 기본 `always`, `7` |
| `/moeun/prod/ai` | `DATABASE_URL` | SecureString | ✓ | 비밀번호가 들어 있다 |
| | `ANTHROPIC_API_KEY` | SecureString | ✓ | |
| | `OPENAI_API_KEY` | SecureString | ✓ | 임베딩 |
| | `ANTHROPIC_MODEL`, `ANTHROPIC_EFFORT`, `EMBEDDING_MODEL`, `RAG_TOP_K`, `LOG_RETENTION_DAYS` | String | | 기본값은 `ai-server/.env.example` |

- 두지 않는다: `SPRING_PROFILES_ACTIVE`·`SPRING_PROFILES_DEFAULT`·`SPRING_PROFILES_INCLUDE`에 `local`, `JWT_COOKIE_SECURE`에 `false`·`off`·`no`·`0`(대소문자 무관). 있으면 deploy.sh가 배포를 멈춘다.
- compose가 정하므로 두지 않는다: `AI_SERVER_URL`, `SPRING_BASE_URL`, `LOG_DIR`.
- 값을 바꾼 뒤에는 다시 배포해야 반영된다(Actions에서 수동 실행).

## 5. EC2

- t3.small(x86), ap-northeast-2. 아래 명령은 **Amazon Linux 2023** 기준이다.
- 루트 볼륨(EBS gp3)은 **30GiB**로 둔다. 기본 8GiB는 이미지·스왑·로그로 금방 찬다.
- 보안 그룹 인바운드: 80, 443(0.0.0.0/0). SSH는 열지 않고 접속은 SSM Session Manager로 한다.
- 탄력적 IP를 붙이고, 도메인의 A 레코드를 그 IP로 둔다(certbot 전에).
- 위 인스턴스 역할을 붙인다.

Session Manager로 접속해 root로 한 번 실행한다.

```bash
sudo -i

# Docker, git
dnf install -y docker git
systemctl enable --now docker

# Docker Compose 플러그인(2.30 이상. compose 파일이 env_file format: raw를 쓴다)
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

# 메모리 2GB에 backend(1g)·ai-server(512m)·OS가 같이 돈다. 스왑 2GB를 꼭 둔다
dd if=/dev/zero of=/swapfile bs=1M count=2048 && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile swap swap defaults 0 0' >> /etc/fstab

# 저장소(공개라 인증 없이 받는다). 실행 중 생기는 파일은 저장소 밖 /opt/moeun/runtime에 둔다
git clone https://github.com/shinhan-career-next-team-4/moeun.git /opt/moeun/repo
mkdir -p /opt/moeun/runtime/data/seed

# SSM 명령은 root로, HOME 없이 돈다. 소유자가 달라도 git이 거부하지 않게 시스템 설정에 둔다
git config --system --add safe.directory /opt/moeun/repo
```

### 실행 중 파일 배치

| 경로 | 만드는 쪽 | 내용 |
|---|---|---|
| `/opt/moeun/repo` | 사람(clone), 배포 때 SSM이 checkout | 저장소. 손으로 고치지 않는다 |
| `/opt/moeun/runtime/backend.env`, `ai.env` | deploy.sh(배포 때마다) | Parameter Store 값. 권한 600 |
| `/opt/moeun/runtime/compose.env` | deploy.sh | 지금 띄운 이미지 주소 |
| `/opt/moeun/runtime/data/seed/` | 사람 | ETF 가격 CSV(아래 7) |
| `/opt/moeun/runtime/logs/{backend,ai-server}/` | 컨테이너 | 날짜별 로그 |
| `/etc/letsencrypt/` | certbot | 인증서 |

## 6. HTTPS 인증서 (호스트 certbot)

certbot은 EC2 호스트에 설치하고 `--standalone`으로 80 포트를 직접 쓴다. 그동안은 nginx 컨테이너를 멈췄다가 다시 띄운다(hook). 그래서 첫 발급 때 nginx가 없어도 되고, 갱신 뒤 nginx가 새 인증서를 읽도록 따로 reload할 필요도 없다. 컨테이너가 없어도 hook이 실패하지 않게 `|| true`를 붙인다.

```bash
sudo -i

# certbot 설치(공식 pip 방식)
dnf install -y python3.12
python3.12 -m venv /opt/certbot
/opt/certbot/bin/pip install --upgrade pip certbot
ln -sf /opt/certbot/bin/certbot /usr/bin/certbot

# 첫 발급. DNS A 레코드가 이 EC2를 가리키고, 80 포트가 열려 있어야 한다
certbot certonly --standalone \
  -d api.example.com \
  -m <인증서 알림 메일> --agree-tos --no-eff-email --non-interactive \
  --pre-hook "docker stop moeun-nginx-1 || true" \
  --post-hook "docker start moeun-nginx-1 || true"
```

hook은 `/etc/letsencrypt/renewal/api.example.com.conf`에 저장되어 갱신 때도 그대로 쓰인다. 자동 갱신은 systemd 타이머로 둔다(하루 두 번 확인, 만료 30일 전부터 실제 갱신).

```bash
cat > /etc/systemd/system/certbot-renew.service <<'EOF'
[Unit]
Description=certbot renew

[Service]
Type=oneshot
ExecStart=/usr/bin/certbot renew --quiet
EOF

cat > /etc/systemd/system/certbot-renew.timer <<'EOF'
[Unit]
Description=certbot renew twice a day

[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now certbot-renew.timer

# 갱신 흐름 시험(실제 발급 없음, nginx가 몇 초 멈췄다 다시 뜬다)
certbot renew --dry-run
```

갱신하는 동안 nginx가 몇 초 멈춘다.

## 7. ETF 가격 파일

가격 데이터는 이용 조건 때문에 저장소·이미지에 넣지 않는다(TBD-08, `backend/src/main/resources/seed/README.md`). 사람이 EC2의 `/opt/moeun/runtime/data/seed/`에 올린다. 이 폴더는 backend 컨테이너의 `/app/data/seed`에 **읽기 전용**으로 마운트된다.

파일 이름은 `backend/src/main/resources/application.yml`의 기본값과 같아야 한다.

| 종목 | 파일 |
|---|---|
| S&P500 추종(대표) | `sp500-monthly.csv` |
| 코스피200 추종 | `etf-069500-monthly.csv` |
| 나스닥100 추종 | `etf-133690-monthly.csv` |

- SSH를 열지 않으므로 Session Manager 셸에서 붙여 넣거나(`cat > 파일 <<'EOF'`), 미리 서명한 URL(presigned URL)을 `curl -o`로 받는다. 인스턴스 역할에는 S3 권한이 없다. 올린 뒤 `chmod 644`.
- backend는 기동할 때 읽는다. 올린 뒤 `docker restart moeun-backend-1`. 같은 `version-label`은 다시 넣지 않는다.
- **S&P500 파일이 없으면 ETF가 들어간 계산을 할 수 없다.** 없으면 deploy.sh가 경고를 남긴다.

## 8. RAG 근거 적재

처음 한 번, 그리고 `scripts/ingest/allowlist.yml`이 바뀌었을 때 EC2에서 실행한다. ai-server 이미지에 적재 스크립트와 허락 목록이 들어 있고, 컨테이너의 `DATABASE_URL`·`OPENAI_API_KEY`로 RDS에 넣는다. 여러 번 돌려도 중복 행이 생기지 않는다.

```bash
docker exec moeun-ai-server-1 bash /repo/scripts/ingest/ingest.sh
```

## 9. Vercel

- New Project → 이 저장소. **Root Directory: `frontend`**, Framework Preset: Vite. Production Branch: `main`.
- 환경 변수는 두지 않는다. 특히 `VITE_API_MODE=mock`을 두지 않는다.
- `frontend/vercel.json`이 `/api/*`를 `https://api.example.com/api/*`로 넘기고, 나머지 경로는 `index.html`로 보낸다(BrowserRouter).
- **frontend/가 바뀌지 않으면 빌드를 건너뛴다**: Settings → Git → Ignored Build Step → Custom에 아래 명령을 넣는다. Root Directory가 `frontend`라서 명령은 `frontend/`에서 돈다. 종료 코드 0이면 건너뛰고 1이면 빌드한다.

  ```bash
  git diff --quiet HEAD^ HEAD -- .
  ```

  이 명령은 push의 **마지막 커밋**만 본다. develop → main은 **merge commit**으로 머지한다(rebase·squash로 여러 커밋을 넣으면 frontend 변경이 마지막 커밋에 없을 때 빌드를 건너뛴다).

## 10. 첫 배포 순서

1. ECR, RDS, IAM(OIDC 역할·인스턴스 역할), Parameter Store를 만든다.
2. EC2를 준비하고(5) 도메인 A 레코드를 둔 뒤 인증서를 발급한다(6). ETF 가격 파일을 올린다(7).
3. GitHub Variables(`AWS_DEPLOY_ROLE_ARN`, `EC2_INSTANCE_ID`)를 넣는다. 다음 단계의 머지가 배포 워크플로를 바로 돌리므로 먼저 넣는다.
4. 도메인을 바꾼 커밋(`deploy/nginx/default.conf`, `frontend/vercel.json`)을 main까지 머지한다. 배포가 자동으로 돈다(안 돌면 Actions에서 `deploy`를 수동 실행).
5. RAG를 적재한다(8). Vercel을 연결한다(9).
6. 확인: `curl https://api.example.com/actuator/health` → `{"status":"UP"}`. 프론트에서 로그인 → 계획 → 대화.

## 운영

```bash
# 상태·로그 (EC2, root)
cd /opt/moeun/repo/deploy
docker compose --env-file /opt/moeun/runtime/compose.env ps
docker compose --env-file /opt/moeun/runtime/compose.env logs -f --tail 100 backend
tail -f /opt/moeun/runtime/logs/backend/backend.log
```

- 되돌리기: 이전 커밋으로 배포한다. EC2에서 `cd /opt/moeun/repo && git checkout <이전 sha> && deploy/deploy.sh <이전 sha>`(그 sha의 이미지가 ECR에 있어야 한다).
- deploy.sh가 하는 일: Parameter Store → env 파일, 필수 키·중복 키·금지 값 검사, 인증서 확인, ECR 로그인, `pull`·`up -d`, 헬스 체크(서비스마다 최대 5분), `nginx -t` 뒤 reload, 안 쓰는 이미지 삭제. 실패하면 이유가 SSM 출력과 Actions 로그에 남는다. 컨테이너 로그에는 사용자 발화가 있어 공개 Actions 로그로 보내지 않는다. EC2에서 `docker logs moeun-backend-1`로 본다.
- 컨테이너 stdout 로그는 서비스마다 10MB × 3개까지만 남는다. 날짜별 파일 로그는 `/opt/moeun/runtime/logs/`에 보관 기간(기본 7일)만큼 남는다.
- 공개 경로는 `/api/**`와 `/actuator/health`뿐이다. `/internal/**`, `/docs/**`(Swagger), `/webjars/**`, 나머지 actuator는 nginx가 404로 막는다.
