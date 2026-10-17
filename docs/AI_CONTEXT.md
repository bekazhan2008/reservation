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
- POST /api/auth/register (пакет com.reservation.authentication):
  201 и DTO {id, email, firstName, lastName, emailVerified};
  Bean Validation, BCrypt через PasswordEncoder (spring-security-crypto),
  409 при повторном email, 400 в едином формате ApiError
  {status, error, message, fieldErrors[{field, message}]}.
  Сессия не создаётся, пользователь не авторизуется.
- Интеграционные тесты на настоящем PostgreSQL (база reservation_test) проверяют:
  нормализацию и сохранение через UserRepository, @PrePersist (created_at),
  role=USER / email_verified=false / enabled=true / deleted_at=null,
  BCrypt-хеш и PasswordEncoder.matches, 409 для дубликата с другим регистром и пробелами,
  гонку параллельных регистраций (ровно один 201), 400 для пустых/некорректных полей,
  слишком длинных значений, пароля > 72 байт UTF-8 и битого JSON,
  игнорирование role/emailVerified/enabled из JSON, отсутствие пароля и хеша в ответе,
  работу GET /api/property-types.
- React показывает типы недвижимости через API (форма регистрации ещё не сделана).
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

## Тесты backend
- Тесты используют профиль test (src/test/resources/application-test.properties)
  и отдельную базу reservation_test в том же контейнере; рабочая база не затрагивается.
- Логин и пароль берутся из того же .env; имя базы можно переопределить POSTGRES_TEST_DB.
- Один раз создать базу (из корня):
  docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE DATABASE reservation_test"'
- Запуск: из backend .\mvnw.cmd test. Flyway сам применяет миграции к reservation_test.
- Тесты проверяют, что имя базы оканчивается на _test, и удаляют только свои строки
  (email на уникальном домене *.example.test).

## Принятые решения
- Структуру БД меняет Flyway.
- Hibernate настроен на ddl-auto=validate.
- API возвращает DTO.
- Секреты находятся в .env и исключены из Git.
- Email уникален без учёта регистра: это проверяет БД по lower(email);
  исходное написание email сохраняется.
- Добавлено поле deleted_at для будущей мягкой деактивации; сама операция пока не реализована.
- Тестовые пользователи и администраторы миграциями не создаются.
- Нормализация email при регистрации: strip() + toLowerCase(Locale.ROOT)
  в компактном конструкторе RegistrationRequest, то есть до валидации и сохранения.
  Имя и фамилия очищаются от пробелов по краям. Пароль не изменяется.
- Пароль: минимум 8 символов и максимум 72 байта UTF-8 (предел BCrypt);
  длиннее — 400, без усечения.
- Дубликат email: предварительная проверка в сервисе + saveAndFlush;
  окончательная защита — индекс users_email_lower_uk. В 409 превращается
  только нарушение этого индекса, прочие ошибки целостности — нет.
- Логгер org.hibernate.orm.jdbc.error понижен до ERROR, чтобы текст ошибок
  PostgreSQL (с email) не попадал в WARN-логи.

## Особенности окружения
- Docker: 4 CPU, 3 ГБ RAM, 3 ГБ swap, диск 64 ГБ.
- Импорт Maven в IntelliJ вызывал ошибку ArtifactHandlerManager.
  Для обхода использован встроенный Maven 3.
- Полный запуск всех шести сервисов ещё не реализован.

## Следующие задачи
- Подключить полную HTTP-конфигурацию Spring Security (сейчас есть только spring-security-crypto).
- Реализовать вход (login) и JWT.
- Форма регистрации во frontend.
- Затем подключить Redis, Java Worker и Resend для подтверждения email.

## Правила обучения
Небольшая задача → объяснение → реализация → проверка.
Не считать функцию готовой без проверки.