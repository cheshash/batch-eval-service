FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY MockPromptServer.java .
RUN javac MockPromptServer.java
EXPOSE 9000
CMD ["java", "MockPromptServer"]
