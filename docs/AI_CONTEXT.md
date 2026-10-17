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
- Миграция V2 создаёт таблицу users (без данных):
  уникальный индекс users_email_lower_uk по lower(email),
  CHECK users_role_check (role IN ('USER','ADMIN')),
  SQL DEFAULT для role, email_verified, enabled, created_at.
- JPA-слой пользователей в com.reservation.users:
  сущность User, enum UserRole, UserRepository.
  Роль хранится строкой (EnumType.STRING), время — Instant.
  Значения по умолчанию заданы в полях Java, created_at — в @PrePersist.
  toString() не выводит passwordHash.
- Проверено: применение V2, SQL-ограничения (уникальность lower(email),
  CHECK роли, DEFAULT) и запуск backend с ddl-auto=validate.
- Ещё не проверено интеграционно: сохранение User через UserRepository,
  работа @PrePersist и Java-значения по умолчанию.
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
- Email уникален без учёта регистра: это проверяет БД по lower(email);
  исходное написание email сохраняется.
- Добавлено поле deleted_at для будущей мягкой деактивации; сама операция пока не реализована.
- Тестовые пользователи и администраторы миграциями не создаются.

## Особенности окружения
- Docker: 4 CPU, 3 ГБ RAM, 3 ГБ swap, диск 64 ГБ.
- Импорт Maven в IntelliJ вызывал ошибку ArtifactHandlerManager.
  Для обхода использован встроенный Maven 3.
- Полный запуск всех шести сервисов ещё не реализован.

## Следующие задачи
- Подключить Spring Security и Bean Validation.
- Реализовать регистрацию с BCrypt и проверкой уникальности email.
  Вместе с ней добавить интеграционный тест сохранения User через UserRepository
  (@PrePersist, Java-значения по умолчанию) с откатом транзакции.
- Затем подключить Redis, Java Worker и Resend для подтверждения email.

## Правила обучения
Небольшая задача → объяснение → реализация → проверка.
Не считать функцию готовой без проверки.