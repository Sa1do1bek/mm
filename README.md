# Materials Management Backend

A Spring Boot backend for enterprise materials management. It supports procurement, warehouses, inventory, stock movements, approvals and reporting, with integration points for external ERP, treasury, messaging and AI services.

## Technology

- Java 21 and Maven
- Spring Boot, Spring Security and Spring Data JPA
- PostgreSQL and Flyway migrations
- RabbitMQ, Firebase and email integrations
- OpenAPI/Swagger UI, JUnit and Testcontainers

## Getting started

Install JDK 21, Maven and PostgreSQL. A RabbitMQ instance and credentials for external integrations may also be needed for the features you enable. Create a PostgreSQL database and user, then configure the application using environment variables or an external Spring configuration file.

For a local profile, set at least `SPRING_PROFILES_ACTIVE=local`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `RFQ_MAGIC_LINK_PEPPER` and `RFQ_SMTP_PASSWORD`. See `src/main/resources/application-local.yml` for the complete set of options and defaults. The JWT secret and other credentials must be generated for your own environment and kept private. Firebase is disabled by default; if enabled, provide credentials externally using the `APP_FIREBASE_*` variables.

```sh
mvn clean package -DskipTests
mvn spring-boot:run
```

The server defaults to port 8080. API documentation is available at `/swagger-ui.html` when enabled. Flyway manages database migrations from `src/main/resources/db/migration`.

## Tests

```sh
mvn test
```

Some integration tests require Docker and PostgreSQL through Testcontainers; service-dependent tests may need additional configuration. To run a single test class, use `mvn -Dtest=ClassName test`.

## Docker

Build the application JAR with Maven first, then run `docker build -t mm-backend .`. Pass configuration to the container at runtime; do not bake credentials into the image. The Dockerfile exposes port 8080.

## Project layout

- `src/main/java`: controllers, domain logic, persistence and integrations
- `src/main/resources`: application configuration and database migrations
- `src/test`: unit and integration tests
- `docs`: integration contracts and implementation notes
- `scripts`: operational and migration helpers

## Security and deployment

The checked-in configuration uses placeholders for sensitive values. Configure unique secrets, restrict access to integrations and review the security and deployment configuration before using real data. This repository does not include a license; contact the owner before reusing or redistributing the code.
