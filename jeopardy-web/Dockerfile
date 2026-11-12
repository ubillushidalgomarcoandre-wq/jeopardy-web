# Etapa 1: compilar el WAR con Maven
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests clean package

# Etapa 2: ejecutar en Tomcat 9 (usa javax.websocket)
FROM tomcat:9.0-jdk11-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
# Se despliega como ROOT para que el enlace sea https://tu-app.onrender.com/
COPY --from=build /app/target/jeopardy.war /usr/local/tomcat/webapps/ROOT.war
# Render indica el puerto en la variable PORT
CMD sed -i "s/Connector port=\"8080\"/Connector port=\"${PORT:-8080}\"/" conf/server.xml && exec catalina.sh run
