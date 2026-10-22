# Web Kiosko — Android-приложение для Google Play

Полная справка: **docs/HANDOFF.md** (прочитать в начале новой сессии).

Коротко:
- Универсальный киоск-браузер на основе «Saubot Киоск» (репозиторий Sauboter/saubot-kiosk-android).
- Java, без AndroidX/Kotlin; пакет `kz.saubot.webkiosko`; minSdk 26, compile/target 36.
- Интерфейс приложения — только английский. Общение с пользователем и комментарии в коде — по-русски.
- Собрать в среде Claude нельзя — только GitHub Actions (push в `main` → артефакты `webkiosko-apk`, `webkiosko-aab`).
  Проверка: `gh run list -R Sauboter/web-kiosko`, `gh run watch <id> --exit-status`.
- Перед пушем грубо проверить синтаксис `javac` (ошибки «cannot find symbol» по android.* ожидаемы).
- Пошагово; не делать неоговорённого; не просить пароли/ключи в чат.
- Коммиты: автор `Saubot <yana.chukalovskaya@gmail.com>`, подпись Co-Authored-By по правилам сессии.
