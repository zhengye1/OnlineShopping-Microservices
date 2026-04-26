# OnlineShopping Microservices

Hands-on microservices course building on the [OnlineShopping monolith](../OnlineShopping). 25 lessons covering service decomposition, inter-service communication, observability, AWS deployment, and load testing.

## Stack

| Layer | Tech |
|-------|------|
| Backend | Spring Boot 3.5.x + Java 21 |
| Storage | MySQL (RDS), DynamoDB, ElastiCache Redis |
| Messaging | Kafka (local) / SNS+SQS (AWS prod) |
| Resilience | Resilience4j |
| Edge | ALB -> Spring Cloud Gateway, OpenFeign |
| Observability | OpenTelemetry + Jaeger, Prometheus + Grafana, Loki |
| Infrastructure | Terraform, ECS Fargate -> EKS, GitHub Actions |
| Load Testing | k6 |

## Services (final state at L25)

```
api-gateway / user-service / product-service / inventory-service /
cart-service / order-service / payment-service / notification-service
```

## Lessons

See `docs/lessons/` for individual lesson markdown.

| Status | Lesson |
|--------|--------|
| in progress | L01 - Bounded Contexts and Service Boundaries |
| planned | L02 - L25 |

## Branch Workflow

Each lesson lives on its own branch: `lesson-XX-<topic>`. Merge to `main` after self-review.

## Frontend

The Next.js frontend stays in the monolith repo and is **not** modified by this course.
Test microservices via **Postman / k6 / curl**.
