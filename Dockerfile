# image ของ an22-api — `docker build -t an22-api:$(git rev-parse --short HEAD) .`
# รันบน production เป็น container (Azure) — ค่าทั้งหมดจาก env (.env.example), ไม่มีความลับใน image

# ---------- build ----------
FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
WORKDIR /src
# dependency ก่อน (cache layer จนกว่า pom เปลี่ยน)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
# test รันที่ ./mvnw verify (ต้องมี docker สำหรับ Testcontainers) ไม่ใช่ตอน build image
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/an22-api-*.jar extract --layers --launcher --destination /extracted

# ---------- runtime ----------
FROM eclipse-temurin:25.0.4.1_1-jre-noble

# เวลาท้องถิ่นของทั้ง container (OS + JVM) — log / session DB เป็น +07:00; JSON ของ api ยังเป็น …Z (Instant)
ENV TZ=Asia/Bangkok \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Bangkok -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo "$TZ" > /etc/timezone \
 && groupadd --system --gid 10001 an22 \
 && useradd --system --uid 10001 --gid an22 --no-create-home --shell /usr/sbin/nologin an22

WORKDIR /app
# Flyway อ่าน filesystem:db/migration (relative กับ WORKDIR) — ไม่มี db/seed: ข้อมูล DEMO ไม่ลง production
COPY --chown=root:root db/migration/ db/migration/
# layer ของ Spring Boot เรียงจากเปลี่ยนน้อย → บ่อย
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER an22:an22
EXPOSE 8080
# ไม่มี HEALTHCHECK (image ไม่มี curl) — ตั้ง probe ที่ platform: liveness GET /api/healthz, readiness GET /api/readyz
# exec form: java เป็น PID 1 ได้ SIGTERM ตรง → server.shutdown=graceful รอ request ที่ค้าง
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
