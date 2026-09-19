FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml FoodDonationServer.java ./

RUN mvn -q dependency:copy-dependencies -DoutputDirectory=dependencies \
	&& javac -cp "dependencies/*" FoodDonationServer.java

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /build/FoodDonationServer*.class .
COPY --from=build /build/dependencies ./dependencies

EXPOSE 8080

CMD ["java", "-cp", ".:dependencies/*", "FoodDonationServer"]
