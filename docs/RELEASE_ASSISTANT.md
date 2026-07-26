# DragonVPN Release Assistant

Помощник выпускает подписанный релиз через уже настроенные GitHub Actions и **не создаёт, не заменяет и не читает значения ключей подписи**.

## Что делает

1. Проверяет, что `origin` указывает на `anonymouskeys/Dragon-vpn`.
2. Проверяет чистоту и синхронизацию текущей ветки.
3. Проверяет наличие workflow и имён четырёх signing secrets.
4. Повышает `versionCode` на 1 и устанавливает новый `versionName`.
5. Создаёт release-коммит и аннотированный тег.
6. Отправляет коммит и тег одним атомарным push.
7. Запускает GitHub Actions на теге и ждёт результат.
8. Скачивает APK и отчёты, сверяет SHA-256.
9. При наличии `apksigner` дополнительно проверяет подпись локально.
10. Показывает путь к APK и ссылку на опубликованный GitHub Release.

## Требования

```bash
git --version
gh --version
python3 --version
gh auth status
```

Секреты в репозитории должны уже существовать:

```text
APP_KEYSTORE_BASE64
APP_KEYSTORE_PASSWORD
APP_KEYSTORE_ALIAS
APP_KEY_PASSWORD
```

Помощник проверяет только их имена. GitHub не позволяет прочитать сохранённые значения секретов.

## Запуск

Интерактивно:

```bash
./scripts/release-assistant.sh
```

С указанием версии:

```bash
./scripts/release-assistant.sh 2.2.9
```

С собственными release notes для аннотированного тега:

```bash
./scripts/release-assistant.sh 2.2.9 CHANGELOG.md
```

Старый вход также работает:

```bash
./release.sh 2.2.9
```

На Android/Termux удобно сохранять артефакты сразу в память телефона:

```bash
RELEASE_DOWNLOAD_DIR=/storage/emulated/0/Download/DragonVPN-Releases \
  ./scripts/release-assistant.sh 2.2.9
```

## Безопасность

Помощник никогда не запускает `setup-release-signing.sh`, не вызывает `gh secret set` и не меняет keystore. Сборка использует существующие секреты GitHub Actions.

Перед push скрипт повторно показывает проверенный remote. При любой ошибке он не удаляет локальный коммит или тег автоматически, чтобы состояние можно было проверить вручную.
