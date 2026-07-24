# Dragon VPN

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

![Dragon Logo](dragon.jpg)

Dragon VPN (powered by sing-box) / universal proxy toolchain for Android.
Dragon VPN (на базе sing-box) / универсальный клиент и набор прокси-инструментов для Android.

## Subscription / Подписка

Copy this link for automatic server download:
Скопируйте эту ссылку для автоматической загрузки серверов в приложение:

`https://raw.githubusercontent.com/anonymouskeys/Vpn/main/subscription.txt`

## Telegram Channel / Наш Telegram-канал

Subscribe to our Telegram channel for updates, news, and the best private configs:
Подпишитесь на наш Telegram-канал, чтобы получать обновления, новости и лучшие приватные конфиги:

https://t.me/anonymouskeys

## Donate / Поддержать проект

If this project is helpful to you, please support us by gifting **Telegram Stars** directly in our channel!
Если этот проект вам полезен, пожалуйста, поддержите нас, подарив **Звёзды (Telegram Stars)** прямо в нашем канале!

## Supported Proxy Protocols / Поддерживаемые прокси-протоколы

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Trojan-Go (trojan-go-plugin)
* NaïveProxy (naive-plugin)

## Credits / Благодарности

Core / Ядро:
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

Android GUI / Интерфейс:
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)


## Development builds / Тестовые сборки

Every push to `main` builds one installable universal F-Droid test APK in GitHub Actions. The test APK uses the standard Android debug signature and does not require release signing secrets.

Каждый push в `main` собирает один устанавливаемый universal F-Droid test APK в GitHub Actions. Тестовый APK подписывается стандартным Android debug-ключом и не требует релизных секретов.

TLS fragmentation is disabled by default and can be enabled by the user in **Settings → Anti-DPI**. Dragon VPN does not silently overwrite user profiles or subscription URLs.

Фрагментация TLS по умолчанию выключена и включается пользователем в **Настройки → Обход DPI**. Dragon VPN не подменяет профили и URL пользовательских подписок.
