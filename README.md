<div align="center">

# Kestrel

**Open-source прокси-клиент для Android (SDK 26–35)** с высокопроизводительным ядром на **Rust** (`tokio` + `smoltcp`) и интерфейсом на **Kotlin / Jetpack Compose** (Material 3 / Material You).

<br>

[![Скачать Kestrel APK](https://img.shields.io/badge/%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C_APK-Kestrel_Android-006A5E?style=for-the-badge&logo=android&logoColor=white)](https://github.com/RoMaSHia/KestrelVpnService/releases/latest/download/Kestrel-universal.apk)

[![Все релизы](https://img.shields.io/github/v/release/RoMaSHia/KestrelVpnService?label=%D0%92%D0%B5%D1%80%D1%81%D0%B8%D1%8F&style=flat-square&color=006A5E)](https://github.com/RoMaSHia/KestrelVpnService/releases)
[![Сборка APK](https://img.shields.io/github/actions/workflow/status/RoMaSHia/KestrelVpnService/build-apk.yml?label=Build%20APK&style=flat-square)](https://github.com/RoMaSHia/KestrelVpnService/actions)
[![Лицензия](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

> **Как установить:** нажмите кнопку **«Скачать APK»** — браузер сразу загрузит `Kestrel-universal.apk` (Android 8.0+, `arm64-v8a` и `x86_64`). Откройте файл на телефоне и при запросе разрешите установку.  
> [Все версии (Releases)](https://github.com/RoMaSHia/KestrelVpnService/releases) · [Сборки GitHub Actions](https://github.com/RoMaSHia/KestrelVpnService/actions)

</div>

---

## Возможности

### Сетевое ядро на Rust (`rust-core`)
- **Userspace TCP/IP стек (`smoltcp`):** принимает файловый дескриптор TUN-интерфейса (`tun_fd`) от Android `VpnService` без root-прав.
- **Протоколы исходящих прокси:**
  - **SOCKS5** (TCP + UDP Associate, с авторизацией и без).
  - **SOCKS4 / SOCKS4a** (IPv4 и удалённое разрешение имён).
  - **HTTP / HTTPS CONNECT** (включая `Proxy-Authorization: Basic`).
- **Защита от петли маршрутизации:** каждый исходящий TCP и UDP сокет в Rust перед `connect()`/`bind()` вызывает JNI-колбэк `VpnService.protect(raw_fd)`.
- **Защита от DNS-утечек:** перехват UDP/53 запросов внутри TUN, маршрутизация DNS по правилам (через прокси по DNS-over-TCP, напрямую или блокировка NXDOMAIN), кастомный DNS (`1.1.1.1` / `8.8.8.8`) и кэш `IP -> Домен`.

### Двухуровневая маршрутизация
1. **Уровень ОС (Per-App Split Tunneling):**
   - `ALL` — весь трафик устройства.
   - `ONLY_SELECTED` — только выбранные приложения (`addAllowedApplication`).
   - `BYPASS_SELECTED` — всё, кроме выбранных (`addDisallowedApplication`, удобно для банков и навигаторов).
   - Приложение **Kestrel** автоматически исключено из туннеля.
2. **Уровень Rust-ядра (Rule-based Routing):**
   - Действия: `PROXY`, `DIRECT`, `BLOCK`.
   - Условия: точный домен (`full:`), суффикс домена (`domain:`), ключевое слово (`keyword:`), IPv4/IPv6 подсеть (`ip:` / CIDR через бинарный radix-trie), целевой порт (`port:`).
   - **Sniffing без расшифровки:** парсинг TLS `ClientHello` SNI и HTTP `Host` заголовка для точной маршрутизации по доменам.
   - Встроенный **LAN Bypass** (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `fc00::/7`, `fe80::/10` → `DIRECT`).
   - Импорт и экспорт правил в формате JSON и текстовых списков доменов.

### Интерфейс и системная интеграция (Kotlin / Jetpack Compose)
- **Material 3 + Dynamic Color (Material You)**, Edge-to-Edge, светлая и AMOLED-тёмная темы, тактильный отклик (Haptic Feedback).
- **4 вкладки:** **Главная**, **Прокси**, **Маршруты** (*Приложения* и *Правила*), **Логи**.
- Встроенная визуальная справка (`?` → `ModalBottomSheet`) по порядку работы правил, быстрые пресеты-тумблеры и бесшовный перезапуск VPN при изменении списка приложений.
- **Quick Settings Tile** (`TileService`) в шторке быстрых настроек и информативное `ForegroundService`-уведомление со скоростью и кнопкой «Отключить».

---

## Архитектура

```text
Proxy_mobail/
├── .github/workflows/build-apk.yml   # GitHub Actions: тесты + сборка APK + Releases
├── rust-core/
│   ├── .cargo/config.toml            # 16 KB page-size linker flags для Android 15+
│   ├── Cargo.toml                    # Release-профиль: opt-level="z", lto=true, strip=true
│   └── src/
│       ├── lib.rs                    # Экспорт модулей и метаданные ядра
│       ├── config.rs                 # Схема JSON-конфигурации (CoreConfig)
│       ├── jni_bridge.rs             # JNI-экспорты (nativeStart/Stop/UpdateConfig/Stats/Logs)
│       ├── packet.rs                 # Парсер и сериализатор IPv4/IPv6 + TCP/UDP
│       ├── tun_engine.rs             # Асинхронный event-loop Tokio + smoltcp tun2socks
│       ├── dns/mod.rs                # Перехват DNS, NXDOMAIN, DNS-кэш
│       ├── proxy/                    # Protected sockets, SOCKS5 (TCP+UDP), SOCKS4/4a, HTTP CONNECT
│       ├── router/                   # Radix-trie для IPv4/IPv6 + DomainMatcher
│       ├── sniff/                    # TLS SNI & HTTP Host sniffing
│       └── stats/mod.rs              # Счётчики скорости и ring-buffer журнала подключений
└── app/
    └── src/main/java/dev/kestrel/proxy/
        ├── MainActivity.kt           # Edge-to-Edge Activity, VPN и Notification permissions
        ├── core/NativeBridge.kt      # JNI-обёртка над libproxy_core.so и SocketProtector
        ├── data/                     # Models, DataStore KestrelRepository, ProxyUriParser, RuleSerializer
        ├── service/                  # KestrelVpnService, KestrelTileService, VpnStateHolder
        └── ui/                       # KestrelViewModel, Theme, CommonComponents, 4 экрана Compose
```

---

## Локальная сборка

### Требования
- JDK 17
- Android SDK (`platforms;android-35`, `build-tools;35.0.0`, `ndk;27.2.12479018`)
- Rust (`stable`) с таргетами `aarch64-linux-android` и `x86_64-linux-android`
- `cargo-ndk` (`cargo install cargo-ndk --locked`)

### Команды сборки и тестирования

```bash
# 1. Тесты Rust-ядра
cargo test --manifest-path rust-core/Cargo.toml

# 2. Юнит-тесты Android
./gradlew testDebugUnitTest

# 3. Сборка Debug APK (автоматически вызывает cargo-ndk для arm64-v8a и x86_64)
./gradlew assembleDebug

# 4. Сборка оптимизированного Release APK
./gradlew assembleRelease
```

Собранные APK будут находиться в:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

---

## CI/CD на GitHub Actions

В репозитории настроен workflow [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml):
- При каждом `push` / `pull_request` прогоняются тесты Rust + Kotlin, собираются Debug и Release APK и сохраняются как **Artifacts**.
- При пуше тега вида `v1.0.0` автоматически создаётся **GitHub Release** с прикреплёнными APK-файлами.
