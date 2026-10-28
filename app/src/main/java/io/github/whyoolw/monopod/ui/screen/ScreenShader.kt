package io.github.whyoolw.monopod.ui.screen

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import io.github.whyoolw.monopod.ScreenEffect

private const val SHADER = """
uniform shader content;
uniform float2 size;
uniform float time;
uniform float strength;
uniform float mode;
uniform float density;

half4 main(float2 p) {
    float2 uv = p / size;
    float2 c = uv - 0.5;
    half4 col;

    if (mode < 1.5) {
        // Color LCD: a fine dark line every 3 px and faint RGB subpixel stripes
        col = content.eval(p);
        float s = 0.5 + 0.5 * cos(6.2831853 * p.y / 3.0);
        col.rgb *= half(1.0 - 0.22 * strength * (1.0 - s));
        float sub = mod(floor(p.x), 3.0);
        half3 tint = sub < 0.5 ? half3(1.0, 0.93, 0.93) : (sub < 1.5 ? half3(0.93, 1.0, 0.93) : half3(0.93, 0.93, 1.0));
        col.rgb *= mix(half3(1.0), tint, half(strength));
    } else if (mode < 2.5) {
        // CRT: bulging glass, color fringes, thick lines, a rolling bright band, flicker, dark corners
        float k = 0.22 * strength;
        float2 uv2 = 0.5 + c * (1.0 + k * dot(c, c));
        if (uv2.x < 0.0 || uv2.x > 1.0 || uv2.y < 0.0 || uv2.y > 1.0) return half4(0.0, 0.0, 0.0, 1.0);
        float2 q = uv2 * size;
        float ca = 1.2 * density * strength;
        col = content.eval(q);
        col.r = content.eval(q + float2(ca, 0.0)).r;
        col.b = content.eval(q - float2(ca, 0.0)).b;
        float period = max(3.0, 2.0 * density);
        float s = 0.5 + 0.5 * cos(6.2831853 * q.y / period);
        col.rgb *= half(1.0 - 0.45 * strength * (1.0 - s));
        float band = (fract(uv2.y - time * 0.15) - 0.5) * 10.0;
        col.rgb += half(0.07 * strength * exp(-band * band));
        col.rgb *= half(1.0 + 0.03 * strength * sin(time * 47.0));
        float v = smoothstep(0.75, 0.25, length(c));
        col.rgb *= half(mix(1.0, v, 0.55 * strength));
    } else {
        // Monochrome LCD: luminance mapped onto grey-blue, plus the pixel grid
        col = content.eval(p);
        float l = dot(float3(col.rgb), float3(0.299, 0.587, 0.114));
        col.rgb = mix(half3(0.12, 0.15, 0.17), half3(0.74, 0.80, 0.82), half(l));
        // Whole-pixel period, or the lines land unevenly and beat into a plaid
        float period = max(3.0, floor(1.5 * density + 0.5));
        float2 cell = mod(floor(p), period);
        float grid = (cell.x < 0.5 || cell.y < 0.5) ? 1.0 : 0.0;
        col.rgb *= half(1.0 - 0.2 * strength * grid);
    }
    return col;
}
"""

/** Draws the screen through the chosen retro effect; [intensity] is 0..10. */
@Composable
fun Modifier.screenEffect(effect: ScreenEffect, intensity: Int, crtSpeed: Float = 1f): Modifier {
    if (effect == ScreenEffect.Off) return this
    val shader = remember { RuntimeShader(SHADER) }
    val density = LocalDensity.current.density
    val time = remember { mutableFloatStateOf(0f) }
    val speed = remember { mutableFloatStateOf(crtSpeed) }
    speed.floatValue = crtSpeed
    // Only the CRT moves; the others stay static and cost nothing between frames
    if (effect == ScreenEffect.Crt) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f * speed.floatValue }
        }
    }
    val mode = when (effect) {
        ScreenEffect.Lcd -> 1f
        ScreenEffect.Crt -> 2f
        else -> 3f
    }
    return graphicsLayer {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", time.floatValue)
        shader.setFloatUniform("strength", intensity / 10f)
        shader.setFloatUniform("mode", mode)
        shader.setFloatUniform("density", density)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        clip = true
    }
}
