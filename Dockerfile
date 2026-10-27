FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN javac -encoding UTF-8 -d out App.java && echo "Main-Class: App" > manifest.txt && jar cfm app.jar manifest.txt -C out .

FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /src/app.jar .
COPY quotes.txt .
COPY static ./static
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
