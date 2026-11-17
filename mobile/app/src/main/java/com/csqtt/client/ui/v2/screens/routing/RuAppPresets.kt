package com.csqtt.client.ui.v2.screens.routing

/**
 * Popular Russian services routed outside the tunnel.
 * VK packages are intentionally absent: the tunnel itself relies on VK Calls.
 */
internal object RuAppPresets {
    val packages: Set<String> = setOf(
        // Яндекс
        "ru.yandex.searchplugin",
        "ru.yandex.yandexmaps",
        "ru.yandex.taxi",
        "ru.yandex.music",
        "ru.yandex.market",
        "ru.yandex.disk",
        "ru.yandex.weatherplugin",
        "ru.yandex.metro",
        "ru.yandex.translate",
        // Mail.ru / Одноклассники
        "ru.mail.mailapp",
        "ru.mail.cloud",
        "ru.ok.android",
        // Банки и госуслуги
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android",
        "ru.gosuslugi",
        "ru.mos.app",
        // Маркетплейсы
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "com.avito.android",
        // Карты, медиа
        "ru.dublgis.dgismobile",
        "ru.kinopoisk",
        "ru.ivi",
        // Операторы связи
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2",
    )
}
