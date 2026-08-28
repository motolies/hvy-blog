# syntax=docker/dockerfile:1
### STAGE 1: Build ###
FROM gradle:9.7.1-jdk25-alpine AS builder
WORKDIR /home/gradle/project

# Build time에 사용할 환경 변수 지정 (default: prod)
ARG ENV_TYPE=prod
ENV ENV_TYPE=${ENV_TYPE}

# GH Packages 조회용 사용자명 (토큰은 secret mount로 주입되어 레이어에 남지 않음)
ARG GHP_USER=motolies

# Gradle 빌드 스크립트 및 Wrapper 파일 복사
# ⚠️ 루트에 있는 빌드 설정 파일은 빠짐없이 여기 나열한다 — 이 목록에 없으면 빌드 컨텍스트에 들어오지 않는다.
#    lombok.config 가 누락되면 Lombok 이 Jackson2/3 을 구분하지 못하고(Jackson 3 은 databind 만
#    tools.jackson 으로 옮기고 애노테이션은 com.fasterxml 좌표를 유지해 모호해진다)
#    @Jacksonized 가 존재하지 않는 com.fasterxml.jackson.databind.annotation 을 참조해 컴파일이 깨진다.
#    로컬 Gradle 빌드는 항상 이 파일을 읽으므로 이 부류의 누락은 Docker 빌드에서만 드러난다.
COPY build.gradle settings.gradle gradlew lombok.config ./
COPY gradle gradle
RUN chmod +x gradlew

# 의존성 미리 다운로드
# 캐시 마운트(--mount=type=cache)를 제거해 다운로드 결과가 레이어에 남도록 한다.
# 이래야 build.gradle 불변 시 gha 레이어 캐시가 이 레이어를 재사용하여 의존성 재다운로드를 막는다.
# secret이 없으면 GHP_TOKEN이 비어 build.gradle의 조건부 로직이 mavenCentral로 폴백한다.
RUN --mount=type=secret,id=ghp_token \
    GHP_TOKEN=$(cat /run/secrets/ghp_token 2>/dev/null || true) GHP_USER=${GHP_USER} \
    ./gradlew --no-daemon dependencies -Penv=${ENV_TYPE}

# 소스 코드 복사 후 실행 가능한 부트 jar만 빌드 (테스트/플레인 jar 제외)
COPY src src
RUN --mount=type=secret,id=ghp_token \
    GHP_TOKEN=$(cat /run/secrets/ghp_token 2>/dev/null || true) GHP_USER=${GHP_USER} \
    ./gradlew --no-daemon bootJar -Penv=${ENV_TYPE}


### STAGE 2: Production Environment ###
FROM amazoncorretto:25-al2023

# 필요시 비루트 사용자 생성 (원래 Dockerfile의 주석 부분 참조)
# RUN addgroup -g 1001 -S spring
# RUN adduser -S boot -u 1001

ARG JAR_FILE=build/libs/*.jar
COPY --from=builder /home/gradle/project/${JAR_FILE} ./app.jar

# USER boot

# 빌드 아규먼트 설정
ARG VERSION
ENV VERSION $VERSION
ARG BUILD_TIMESTAMP
ENV BUILD_TIMESTAMP $BUILD_TIMESTAMP

ENV DB_URL mariadb:3306
ENV DB_USER skyscape
ENV DB_PASS skyscape!!
ENV JWT_SECRET jwt_secret_key!!@@
ENV PORT 8080
ENV SPRING_PROFILES_ACTIVE=default

EXPOSE ${PORT}
# JVM 타임존을 UTC로 명시 고정 — 베이스 이미지 기본값에 대한 암묵 의존 제거
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}", "-jar", "/app.jar"]