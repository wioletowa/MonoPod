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
/** 1 when the screen is dark with light text (dark screen, Material dark). */
uniform float darkContent;

// Brightness for one-color screens, with colored parts (the blue selection bar, the accent)
// counted as dark: the bar becomes solid and its white text stays readable on it
float tone(half4 c) {
    float3 rgb = float3(c.rgb);
    float l = dot(rgb, float3(0.299, 0.587, 0.114));
    float sat = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
    return clamp(l - 0.9 * sat, 0.0, 1.0);
}

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
    } else if (mode < 3.5) {
        // First iPod: luminance pushed to two tones on grey-blue (the blue selection bar turns
        // into a dark bar with light text), plus the pixel grid
        col = content.eval(p);
        float l = tone(col);
        // Two tones: anything clearly darker than the light screen (grey values and chevrons
        // too) turns dark, the selection bar's white text stays light
        // (on a dark screen the other way round: anything clearly lighter turns light)
        float two = darkContent > 0.5 ? smoothstep(0.18, 0.38, l) : smoothstep(0.62, 0.82, l);
        l = mix(l, two, 0.5 + 0.5 * strength);
        col.rgb = mix(half3(0.12, 0.15, 0.17), half3(0.74, 0.80, 0.82), half(l));
        // Whole-pixel period, or the lines land unevenly and beat into a plaid
        float period = max(3.0, floor(1.5 * density + 0.5));
        float2 cell = mod(floor(p), period);
        float grid = (cell.x < 0.5 || cell.y < 0.5) ? 1.0 : 0.0;
        col.rgb *= half(1.0 - 0.2 * strength * grid);
    } else {
        // Handheld: four shades of green, every pixel on its own (thin grey text stays sharp),
        // with a dp-sized pixel grid drawn over it
        float cellSize = max(2.0, floor(density + 0.5));
        col = content.eval(p);
        float l = tone(col);
        // Text, grey values and the selection bar go to the two dark shades, the screen and
        // the text on the bar to the two light ones
        l = darkContent > 0.5 ? smoothstep(0.12, 0.5, l) : smoothstep(0.45, 0.85, l);
        float shade = floor(clamp(l, 0.0, 0.999) * 4.0);
        half3 g = shade < 0.5 ? half3(0.06, 0.22, 0.06) : (shade < 1.5 ? half3(0.19, 0.38, 0.19)
            : (shade < 2.5 ? half3(0.55, 0.67, 0.06) : half3(0.61, 0.74, 0.06)));
        // A thin gap between the pixels
        float2 in_cell = mod(p, cellSize);
        float gap = (in_cell.x < 1.0 || in_cell.y < 1.0) ? 1.0 : 0.0;
        col = half4(g * half(1.0 - 0.12 * strength * gap), 1.0);
    }
    return col;
}
"""

/** Draws the screen through the chosen retro effect; [intensity] is 0..10. */
@Composable
fun Modifier.screenEffect(effect: ScreenEffect, intensity: Int, crtSpeed: Float = 1f, darkContent: Boolean = false): Modifier {
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
        ScreenEffect.Mono -> 3f
        else -> 4f
    }
    return graphicsLayer {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", time.floatValue)
        shader.setFloatUniform("strength", intensity / 10f)
        shader.setFloatUniform("mode", mode)
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("darkContent", if (darkContent) 1f else 0f)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        clip = true
    }
}
