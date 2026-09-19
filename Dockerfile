FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY FoodDonationServer.java .
COPY share-table-logo.svg .

RUN javac FoodDonationServer.java

EXPOSE 8080

CMD ["java", "FoodDonationServer"]
