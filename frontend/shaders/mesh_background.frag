// SPDX-FileCopyrightText: 2026 luminescq
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

#version 460 core

#include <flutter/runtime_effect.glsl>

// ЭТАЛОННЫЙ ШЕЙДЕР (в рантайме больше не выполняется).
// Тот же логистический профиль теперь запекается в три спрайта-пятна в
// mesh_background.dart, а в кадре спрайты только сдвигаются. Файл оставлен
// как эталон математики и вида для сверки.
uniform vec2  uSize;
uniform vec2  uC1;
uniform vec2  uC2;
uniform vec2  uC3;
uniform float uR1;
uniform float uR2;
uniform float uR3;
uniform float uSigma;
uniform vec3  uBase;

out vec4 fragColor;


const float OVERLAY_DARKNESS  = 0.48;

const vec3 BLOB1_COLOR = vec3(250.0, 240.0, 228.0) / 255.0;
const vec3 BLOB2_COLOR = vec3(1.0, 1.0, 1.0);
const vec3 BLOB3_COLOR = vec3(247.0, 233.0, 210.0) / 255.0;

const float BLOB1_ALPHA = 0.75;
const float BLOB2_ALPHA = 0.65;
const float BLOB3_ALPHA = 0.35;

// ----------------------------------------------------------------------------
// МОДЕЛЬ РАЗМЫТОГО ДИСКА (2D GAUSSIAN BLUR DISK CONVOLUTION)
// ----------------------------------------------------------------------------
// Коэффициент наклона 1.50 делает форму свечения аккуратной и собранной,
// не заливая светом всё центральное пространство окна.
float getBlurredDiscProfile(float dist, float radius, float sigma) {
    float x = (dist - radius) / max(sigma, 0.001);
    return 1.0 / (1.0 + exp(1.50 * x));
}

// ----------------------------------------------------------------------------
// ОСНОВНОЙ РАСЧЁТ
// ----------------------------------------------------------------------------
void main() {
    // В Flutter Skia (Windows) координата Y идёт снизу вверх, разворачиваем
    // в систему Flutter (y-down), в которой посчитаны центры блобов.
    vec2 fc = vec2(FlutterFragCoord().x, uSize.y - FlutterFragCoord().y);

    float mask1 = getBlurredDiscProfile(length(fc - uC1), uR1, uSigma) * BLOB1_ALPHA;
    float mask2 = getBlurredDiscProfile(length(fc - uC2), uR2, uSigma) * BLOB2_ALPHA;
    float mask3 = getBlurredDiscProfile(length(fc - uC3), uR3, uSigma) * BLOB3_ALPHA;

    // Слоистое смешивание (предотвращает пересвет при пересечении пятен).
    vec3 col = uBase;
    col = mix(col, BLOB1_COLOR, mask1);
    col = mix(col, BLOB2_COLOR, mask2);
    col = mix(col, BLOB3_COLOR, mask3);

    // Затемняющий слой.
    col *= (1.0 - OVERLAY_DARKNESS);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
