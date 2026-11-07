# Для хостинга (Render, Railway и т.п.): сервер Rocket Visuals (косметика, ключи, версии мода, панель).
FROM eclipse-temurin:21-jdk
WORKDIR /app
# Драйвер PostgreSQL для базы ключей (Neon)
ADD https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar postgresql.jar
COPY RocketSyncServer.java admin.html ./
EXPOSE 8765
CMD ["java", "-cp", "postgresql.jar", "RocketSyncServer.java"]
