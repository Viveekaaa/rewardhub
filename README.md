# RewardHub

A configurable, white-label loyalty and rewards platform built with Spring Boot.

## Overview

RewardHub is a generic rewards engine that any business can plug into. Points earning rules, tier thresholds, reward catalogs, and redemption logic are all config-driven — no hardcoding.

## Tech Stack

- Java 17
- Spring Boot 4.0.5
- Spring Security (JWT Authentication)
- Spring Data JPA
- MySQL
- Gradle
- Swagger/OpenAPI

## Features (Planned)

- [ ] User registration & JWT authentication
- [ ] Config-driven points engine (earn/redeem/expire)
- [ ] Multi-tenant business support
- [ ] Rewards catalog management
- [ ] Tier system (Silver/Gold/Platinum)
- [ ] Transaction history & audit trail
- [ ] Leaderboard & user dashboard
- [ ] Admin panel APIs

## Getting Started

### Prerequisites

- Java 17+
- MySQL 8+
- Gradle (wrapper included)

### Setup

1. Clone the repo
```bash
   git clone https://github.com/Viveekaaa/rewardhub.git
   cd rewardhub
```

2. Create the database
```bash
   mysql -u root -p -e "CREATE DATABASE rewardhub;"
```

3. Create src/main/resources/application-local.properties
```properties
   spring.datasource.password=your_mysql_password
```

4. Run the application
```bash
   ./gradlew bootRun
```

5. Access API docs at http://localhost:8080/swagger-ui.html

## Project Structure
```
com.rewardhub.rewardhub
├── config/        # App & security configuration
├── controller/    # REST API endpoints
├── dto/           # Request/Response objects
├── exception/     # Custom exception handling
├── model/         # JPA entities
├── repository/    # Database repositories
├── security/      # JWT & auth logic
├── service/       # Business logic
└── util/          # Helper utilities
```

## Contributing

This is a portfolio project. Feedback and suggestions are welcome!
