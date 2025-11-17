FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Copy source code
COPY . .

# Build the project
RUN ./mvnw clean package -DskipTests

# Run the JAR
CMD ["java", "-jar", "target/trendywear-0.0.1-SNAPSHOT.jar"]
