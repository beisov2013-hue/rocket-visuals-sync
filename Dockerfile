# Для хостинга (Render, Railway и т.п.): запускает сервер синхронизации косметики Rocket Visuals.
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY RocketSyncServer.java .
EXPOSE 8765
CMD ["java", "RocketSyncServer.java"]
