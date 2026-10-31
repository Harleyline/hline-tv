package app.orchard.tv.ui.components

import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Wallpapers
import kotlin.math.roundToInt

/**
 * Liquid Glass, the material of tvOS 26's panels, without a live blur: the TVs here mostly run
 * Android 9–11 (no RenderEffect) and their GPUs could not afford one. The surface shows the part
 * of the frosted wallpaper (Wallpapers.frosted, baked small) that lies behind it, dimmed so white
 * text reads on any picture, and light catches its rim. The frosted picture is stretched over the
 * screen through a shader, so a panel costs one fill of its own area, as a flat color did. In
 * "Follow App" mode there is no frosted wallpaper and the panel is the plain dark glass color.
 *
 * Replaces clip + background + border; [bottomCorners] false for a sheet rising from the bottom.
 * [deeper]: a shade darker than the material every other panel uses, for the Personalize sheet,
 * whose rows of small cards read better on it (the owner, 1.81).
 * The continuous outline is a baked mask (see SmoothCorners.kt); children are clipped to a plain
 * rounded rect, which holds it.
 */
fun Modifier.liquidGlass(corner: Dp, bottomCorners: Boolean = true, deeper: Boolean = false, rim: Boolean = true): Modifier = composed {
    // The glass follows the wallpaper, not the moment the panel opened (1.98): it used to be held
    // from the opening, and a wallpaper chosen just before left every panel wearing the previous
    // wallpaper's frost for as long as it stood — the Personalize sheet over the underwater film
    // in the dunes' beige. The frost swap below fades a new picture in, so following costs no cut.
    // The film's own frame while one plays (Wallpapers.filmFrost), else the picture's frosted copy.
    // No guard on the backdrop either: while a film plays there is none, and the glass fell to the
    // plain Follow-App slab for every aerial — the panels read as flat paint, not glass (1.96).
    val latest = Wallpapers.filmFrost ?: Wallpapers.frosted
    val (frost, under, swap) = rememberFrostSwap(latest)
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(IntSize.Zero) }
    val tint = if (deeper) PanelTintDeep else PanelTint
    val shape = if (bottomCorners) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
    this
        .onGloballyPositioned { c ->
            at = c.positionInRoot()
            var r = c
            while (true) r = r.parentLayoutCoordinates ?: break
            root = r.size
        }
        .clip(shape)
        .drawWithCache {
            val shader = frost?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val underShader = under?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val radius = corner.toPx()
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val mask = smoothMask(w, h, radius, if (bottomCorners) radius else 0f)
            val brush = shader?.let { ShaderBrush(it) }
            val flat = mask.asImageBitmap()
            // The glass in a layer the GPU keeps: drawn again only when it moves or its picture
            // fades, not with every frame of what lies on it. Drawn each frame, the Personalize
            // sheet cost MiTV-MFTR0's GPU about 29 ms a frame while focus moved along its cards.
            val glass = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
            var drawnAt: Offset? = null
            var drawnRoot = IntSize.Zero
            var drawnT = -1f
            onDrawBehind {
                if (shader == null || brush == null || root == IntSize.Zero) {
                    // Follow App: there is no picture to dim, so the deeper sheet is a black veil
                    // under the plain glass, for the same even dark the frosted sheet gets.
                    if (deeper) drawRect(Color.Black.copy(alpha = 0.5f))
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(Palette.Glass))
                    if (rim) drawGlassEdge(size, radius, bottomCorners)
                } else if (AerialSettings.lite) {
                    // Lite (AerialSettings.lite): one solid color, the frosted wallpaper's average as
                    // the glass would dim it, without the layer the glass needs every frame, which
                    // weak GPUs feel most while a panel slides in. Solid: the plain glass color is
                    // see-through and showed the tiles under the Personalize sheet.
                    val solid = liteGlass(frost)
                    drawImage(
                        flat, dstSize = IntSize(w, h),
                        colorFilter = ColorFilter.tint(
                            if (deeper) Color(solid.red * 0.72f, solid.green * 0.72f, solid.blue * 0.72f, solid.alpha) else solid
                        ),
                    )
                    if (rim) drawGlassEdge(size, radius, bottomCorners)
                } else {
                    val t = swap()
                    if (drawnAt != at || drawnRoot != root || drawnT != t) {
                        drawnAt = at
                        drawnRoot = root
                        drawnT = t
                        fun place(s: BitmapShader, image: ImageBitmap) = s.setLocalMatrix(Matrix().apply {
                            setScale(root.width.toFloat() / image.width, root.height.toFloat() / image.height)
                            postTranslate(-at.x, -at.y)
                        })
                        place(shader, frost)
                        // The outline is drawn first in the layer and the glass kept where it lies.
                        // One shader per draw: a ComposeShader of the glass and the mask (1.27-1.30)
                        // came out an opaque dark slab on Xiaomi MiTV-ASTP0 (Android 9, Mali-G31),
                        // and before Android 9 hardware drawing cannot compose two bitmaps.
                        glass.record(this, layoutDirection, IntSize(w, h)) {
                            drawImage(flat, dstSize = IntSize(w, h))
                            if (underShader != null && under != null && t < 1f) {
                                // A new frosted picture fades in over the old within the outline.
                                place(underShader, under)
                                drawRect(ShaderBrush(underShader), colorFilter = tint, blendMode = BlendMode.SrcIn)
                                drawRect(brush, alpha = t, colorFilter = tint, blendMode = BlendMode.SrcAtop)
                            } else {
                                drawRect(brush, colorFilter = tint, blendMode = BlendMode.SrcIn)
                                // No refraction band here: the magnified strip along the rim read
                                // as a ring of another colour against the plain middle (the owner's
                                // Control Center), so the panel is one even sheet of glass.
                            }
                            // The deeper sheet (the owner, 1.82): a black veil over the glass, so
                            // its rows read on any picture and every part of the glass settles to
                            // one even shade — frost of a dark moment showed as darker patches the
                            // small white labels drowned in. The frost keeps a quarter of the look.
                            if (deeper) drawRect(Color.Black.copy(alpha = 0.62f), blendMode = BlendMode.SrcAtop)
                            if (rim) drawGlassEdge(size, radius, bottomCorners)
                        }
                    }
                    drawLayer(glass)
                }
            }
        }
}

/**
 * The Personalize sheet's own background (1.83): the wallpaper that lies behind it, blurred, filled
 * opaquely inside the sheet's rounded top corners.
 *
 * It replaces the screen-wide frosted glass there. That glass samples the frosted wallpaper at the
 * sheet's place on screen — the frost of a moment that is no longer there — and its darker patches
 * left a dark band at the sheet's head and a dark column behind the phone card, with the window's
 * black showing through where it was dim: a black rectangle with square corners behind the panel
 * (the owner, 1.83). Painted from the picture Home draws now ([blur], made by Images.frost), opaque,
 * clipped to the sheet's outline, there is nothing left to show through — and what is behind the
 * sheet reads as colour and shape only, as a real blur would.
 */
fun Modifier.blurredGlass(
    corner: Dp,
    blur: ImageBitmap?,
    /** The picture fading out under [blur] while a new wallpaper comes in ([rememberFrostSwap]). */
    under: ImageBitmap? = null,
    swap: (() -> Float)? = null,
    veil: Float = 0.42f,
    bottomCorners: Boolean = false,
): Modifier = composed {
    val shape = if (bottomCorners) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
    this
        .clip(shape)
        .drawWithCache {
            val radius = corner.toPx()
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val screen = Wallpapers.screenH.toFloat().coerceAtLeast(1f)
            onDrawBehind {
                // Opaque beneath: no window black, no home shading, can show at the sheet's corners.
                drawRect(Color.Black)
                // The picture's bottom, which is what a sheet rising from the bottom covers.
                fun picture(image: ImageBitmap, alpha: Float) {
                    val srcH = (image.height * (h / screen)).roundToInt().coerceIn(1, image.height)
                    drawImage(
                        image,
                        srcOffset = IntOffset(0, image.height - srcH),
                        srcSize = IntSize(image.width, srcH),
                        dstSize = IntSize(w, h),
                        alpha = alpha,
                        filterQuality = FilterQuality.Medium,
                    )
                }
                val t = swap?.invoke() ?: 1f
                if (under != null && t < 1f) picture(under, 1f)
                if (blur != null) picture(blur, if (under != null) t else 1f)
                // The veil that makes white rows read on a bright picture, a shade deeper over
                // the sheet's head: the blurred picture's own sky sits there, and read as a band
                // brighter than the rows below it (the owner, 1.90).
                drawRect(Color.Black.copy(alpha = if (blur != null) veil else 0.72f))
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.26f), Color.Transparent),
                        startY = 0f, endY = size.height * 0.42f,
                    ),
                )
                // No rim here: the sheet's lit edge read as a bright band along its sides and
                // corners against the dark blurred picture (the owner, 1.92). Other panels keep it.
            }
        }
}

/**
 * The frosted wallpaper as glass and backdrops show it: a new one fades in over the last
 * ([FROST_SWAP_MS]) rather than cutting to it. A moving film that stops gives Home its frosted
 * picture in two or three steps (a small frame, then the exact one, LiveWallpaper), and with a
 * panel up each cut was a jump in its glass (Xiaomi MiTV-MFTR0, TCL MT5879). Returns the picture,
 * the one under it while it fades in, and how far it has (0 to 1).
 */
@Composable
internal fun rememberFrostSwap(frost: ImageBitmap?, ms: Long = FROST_SWAP_MS): Triple<ImageBitmap?, ImageBitmap?, () -> Float> {
    var shown by remember { mutableStateOf(frost) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(frost, ms) {
        if (frost === shown) return@LaunchedEffect
        val old = shown
        shown = frost
        // From nothing, or to nothing: it simply takes over.
        if (old == null || frost == null) {
            under = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }
        // Mid-fade, the one further in stays under the next.
        under = if (swap.value > 0.5f) old else under ?: old
        swap.snapTo(0f)
        swap.animateTo(1f, tween(ms.toInt(), easing = Motion.EaseOut))
        under = null
    }
    return Triple(shown, under, { swap.value })
}

private const val FROST_SWAP_MS = 450L

/**
 * The lit edge of a piece of glass: a sheen inside the top, and a rim bright where light falls on
 * it from the top left, faint along the sides, and catching again at the far corner.
 */
private const val REFRACTION_INSET_DP = 7f

/** Panels smaller than this (a pill, a button) draw flat glass: a bend band would not fit. */
private const val REFRACTION_MIN_HEIGHT_DP = 96f

/** How much the picture behind is magnified where the rim bends it (1 = straight through). */
private const val REFRACTION_ZOOM = 1.06f

/**
 * The bend at the rim of real glass: inside a band along the edge the picture behind is drawn
 * again, magnified about the centre, so it stretches outwards the way a lens displaces whatever
 * lies behind its curved edge. Only that band is filled, so a panel still costs about one fill
 * of its own area (a thin strip more), which is what the weak GPUs here can afford.
 */
private fun DrawScope.drawRefraction(brush: Brush, size: Size, radius: Float, bottomCorners: Boolean) {
    val inset = REFRACTION_INSET_DP.dp.toPx()
    val bottom = if (bottomCorners) size.height else size.height + radius
    if (size.width <= inset * 2f || bottom <= inset * 2f) return
    // Small controls (a status pill, a button) have no room for a band: the magnified strip and
    // the plain middle would meet and read as two different colours. Only panels bend.
    if (bottom < REFRACTION_MIN_HEIGHT_DP.dp.toPx() || size.width < REFRACTION_MIN_HEIGHT_DP.dp.toPx()) return
    val ring = Path.combine(
        PathOperation.Difference,
        smoothRectPath(Rect(0f, 0f, size.width, bottom), radius),
        smoothRectPath(Rect(inset, inset, size.width - inset, bottom - inset), (radius - inset).coerceAtLeast(0f)),
    )
    val cx = size.width / 2f
    val cy = bottom / 2f
    clipPath(ring) {
        withTransform({
            translate(cx, cy)
            scale(REFRACTION_ZOOM, REFRACTION_ZOOM)
            translate(-cx, -cy)
        }) {
            drawRect(brush, colorFilter = PanelTint, blendMode = BlendMode.SrcIn)
        }
    }
}

private fun DrawScope.drawGlassEdge(size: Size, radius: Float, bottomCorners: Boolean, strength: Float = 1f) {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w <= 0 || h <= 0) return
    drawImage(glassEdge(w, h, radius, 1.6.dp.toPx(), bottomCorners), dstSize = IntSize(w, h), alpha = strength)
}

/** The lit edge of a [w]×[h] piece of glass at full strength, white with alpha; made once per size. */
fun glassEdge(w: Int, h: Int, radius: Float, rim: Float, bottomCorners: Boolean = true): ImageBitmap =
    baked(listOf("glass-edge", w, h, radius, rim, bottomCorners)) {
        val bitmap = createBitmap(w, h)
        val canvas = android.graphics.Canvas(bitmap)
        // A sheet's rim runs off the bottom with its corners.
        val bottom = if (bottomCorners) h.toFloat() else h + radius
        fun white(alpha: Float) = Color.White.copy(alpha = alpha).toArgb()
        // No sheen across the top, and the rim keeps to a whisper: at full strength the lit top
        // edge read as a white bar laid across the panel's head (the owner, 1.74).
        canvas.drawPath(
            smoothRectPath(Rect(rim / 2, rim / 2, w - rim / 2, bottom - rim / 2), radius - rim / 2).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rim
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), bottom,
                    intArrayOf(white(0.16f), white(0.08f), white(0.06f), white(0.24f)),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        bitmap.frozen().asImageBitmap()
    }

/** Panels: the frosted picture at half its light, lifted a little, so white text reads on snow. */
/** Lite's panel color for [frost]: its average color, dimmed as [PanelTint] dims the glass. Made once per picture. */
private fun liteGlass(frost: ImageBitmap): Color = synchronized(liteColors) {
    liteColors.getOrPut(frost) {
        val small = android.graphics.Bitmap.createScaledBitmap(frost.asAndroidBitmap(), 16, 9, true)
        var r = 0f; var g = 0f; var b = 0f
        for (y in 0 until 9) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c)
        }
        fun dim(sum: Float) = ((sum / 144f) * 0.48f + 14f).coerceIn(0f, 255f) / 255f
        Color(dim(r), dim(g), dim(b))
    }
}

private val liteColors = java.util.WeakHashMap<ImageBitmap, Color>()

private fun panelTint(alpha: Float) = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, alpha, 0f,
)))

private val PanelTint = panelTint(0.66f)

/** The Personalize sheet's glass, a shade darker than the rest (1.81): 0.66 -> 0.79. */
private val PanelTintDeep = panelTint(0.79f)

// Two thirds: the wallpaper behind still shows as colour and shape, but readably — at 0.38 a
// bright sky came straight through the Personalize sheet and the white rows drowned in it (the
// owner, 1.74). Every panel shares the material; the Personalize sheet takes one shade darker.
