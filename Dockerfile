# Dockerfile
FROM openjdk:21-jdk-slim

# App jar name (nee project lo build ayina jar name)
ARG JAR_FILE=build/libs/*.jar

# Copy jar
COPY ${JAR_FILE} app.jar

# Timezone India ki set chey (optional, but good)
ENV TZ=Asia/Kolkata
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Expose port
EXPOSE 8080

# Run
ENTRYPOINT ["java", "-jar", "/app.jar"]