# 운영 이미지(#386). 빌드 컨텍스트는 저장소 루트다(문구표 copy/copy.ko.yml을 같이 넣는다):
#   docker build --platform linux/amd64 -f backend/Dockerfile -t moeun-backend .
# 들어가는 파일은 Dockerfile.dockerignore가 고른다(.env, data/ 같은 비밀값·로컬 데이터 제외)
# 테스트는 여기서 돌리지 않는다(Testcontainers가 Docker를 써야 해서). 배포 전 테스트 게이트는 #387

# 1) 빌드 스테이지
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 의존성 층을 먼저 받아 두면 소스만 바뀐 빌드는 이 층을 다시 쓴다
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null
COPY backend/ .
RUN ./gradlew bootJar --no-daemon -x test && cp $(ls build/libs/*.jar | grep -v plain) app.jar

# 2) 실행 스테이지 (JDK 없이 JRE만 담아서 이미지를 작게)
FROM eclipse-temurin:21-jre
# compose 헬스 체크(/actuator/health)에 curl을 쓴다
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 moeun
WORKDIR /app
# 작업 디렉터리 기준 상대 경로: data/seed(ETF 가격 파일, 읽기 전용 마운트), logs(로그 파일)
RUN mkdir -p data/seed logs && chown -R moeun /app
COPY --from=build /src/app.jar app.jar
# 문구표(CP-05). 기본값 ../copy/copy.ko.yml은 backend/에서 실행할 때의 경로라 이미지에서는 절대경로로 준다
COPY copy/copy.ko.yml /app/copy/copy.ko.yml
ENV MOEUN_COPY_PATH=/app/copy/copy.ko.yml
USER moeun
EXPOSE 8080
# t3.small(2GB)에 ai-server·nginx와 같이 뜬다. 힙은 컨테이너 메모리 제한(compose)의 비율로 잡는다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
