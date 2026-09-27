FROM amazoncorretto:21

WORKDIR /app

COPY target/webframework-lab3-jar-with-dependencies.jar app.jar
COPY src/main/resources/webroot ./webroot

ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]