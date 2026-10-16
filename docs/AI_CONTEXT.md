# Reservation — состояние проекта

Дедлайн MVP: вечер 20 октября 2026 года по времени Казахстана.

## Стек
- Java 21, Spring Boot 4.1.1, Maven.
- Spring Data JPA / Hibernate, Flyway.
- PostgreSQL 17 в Docker Compose.
- React, TypeScript, Vite.

## Реализовано и проверено
- GET /api/health возвращает {"status":"UP"}.
- Миграция V1 создаёт property_types и шесть типов недвижимости.
- GET /api/property-types читает данные из PostgreSQL.
- React показывает типы недвижимости через API.
- npm run build и npm run lint проходят.
- Репозиторий отправлен на GitHub.

## Локальный запуск
1. Создать .env по образцу .env.example и задать пароль.
2. Из корня: docker compose up -d postgres.
3. Из backend: .\mvnw.cmd spring-boot:run.
4. Из frontend: npm.cmd install, затем npm.cmd run dev.
5. Открыть адрес, указанный Vite.

PostgreSQL с Windows доступен на localhost:15432.
Backend работает на localhost:8080.
Vite перенаправляет запросы /api на backend.
Backend читает ../.env относительно рабочей папки backend.

## Принятые решения
- Структуру БД меняет Flyway.
- Hibernate настроен на ddl-auto=validate.
- API возвращает DTO.
- Секреты находятся в .env и исключены из Git.

## Особенности окружения
- Docker: 4 CPU, 3 ГБ RAM, 3 ГБ swap, диск 64 ГБ.
- Импорт Maven в IntelliJ вызывал ошибку ArtifactHandlerManager.
  Для обхода использован встроенный Maven 3.
- Полный запуск всех шести сервисов ещё не реализован.

## Следующие задачи
- Спроектировать users и миграцию V2.
- Подключить Spring Security и Bean Validation.
- Реализовать регистрацию с BCrypt и проверкой уникальности email.
- Затем подключить Redis, Java Worker и Resend для подтверждения email.

## Правила обучения
Небольшая задача → объяснение → реализация → проверка.
Не считать функцию готовой без проверки.