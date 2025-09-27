### STAGE 1: Build ###
FROM gradle:8.12.1-jdk21-alpine AS builder
WORKDIR /home/gradle/project

# Build time에 사용할 환경 변수 지정 (default: prod)
ARG ENV_TYPE=prod
ENV ENV_TYPE=${ENV_TYPE}

# Gradle 빌드 스크립트 및 Wrapper 파일 복사
COPY build.gradle settings.gradle gradlew ./
COPY gradle gradle
RUN chmod +x gradlew

# 의존성 미리 다운로드 (캐시 활용)
RUN echo Download Start: $(date +%F_%T)
# gradlew 실행 시 -Penv 환경 변수를 전달합니다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies -Penv=${ENV_TYPE}
RUN echo Download End: $(date +%F_%T)

# 소스 코드 복사 및 빌드 (테스트는 제외)
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon build -x test -Penv=${ENV_TYPE}
RUN echo BuildEnd: $(date +%F_%T)


### STAGE 2: Production Environment ###
FROM openjdk:21-jdk-slim

# 필요시 비루트 사용자 생성 (원래 Dockerfile의 주석 부분 참조)
# RUN addgroup -g 1001 -S spring
# RUN adduser -S boot -u 1001

ARG JAR_FILE=build/libs/*.jar
COPY --from=builder --chown=gradle:gradle /home/gradle/project/${JAR_FILE} ./app.jar

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
ENTRYPOINT ["java", "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}", "-jar", "/app.jar"]