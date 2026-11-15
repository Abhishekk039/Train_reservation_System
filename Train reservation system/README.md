# Online Reservation System (Java Swing + JDBC + PostgreSQL)

Default login: `admin` / `09022003`

## Setup PostgreSQL
Create a database named `reservation_db` and set these environment variables before running the app:

    set DB_URL=jdbc:postgresql://localhost:5432/reservation_db
    set DB_USER=postgres
    set DB_PASSWORD=postgres

## Run with Maven (Java 17+)
    mvn compile exec:java

## Run without Maven
Compile and run directly:
    javac -d out ReservationSystem.java
    java -cp "out;postgresql-42.7.4.jar" ReservationSystem

Sample train numbers: 12001, 12301, 12431, 12951, 16650, 16346
