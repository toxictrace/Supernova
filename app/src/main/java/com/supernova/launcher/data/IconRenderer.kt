package com.supernova.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.roundToInt

// How raw legacy (non-adaptive, unthemed) icons are made to match the rest
// (reshapeLegacy pref). AUTO rescales their visible content to a uniform
// fraction of the canvas (the old iconNormalize=true behavior); ON also bakes
// a neutral plate behind them so they read like adaptive icons.
enum class ReshapeLegacy {
    OFF,
    AUTO,
    ON;

    companion object {
        // "" derives from the legacy iconNormalize flag.
        fun fromPrefs(prefs: LauncherPrefs): ReshapeLegacy =
            entries.firstOrNull { it.name.equals(prefs.reshapeLegacy, ignoreCase = true) }
                ?: if (prefs.iconNormalize) AUTO else OFF
    }
}

// Flattens any launcher icon (adaptive, legacy, or icon-pack themed) into a
// fixed-size bitmap. Icon-pack back/mask/upon layers are composited here, which
// is also where user-selectable icon shapes plug in.
class IconRenderer(context: Context) {

    private val resources = context.resources
    // How much larger than the base icon the biggest icon on screen is
    // drawn (the icon size settings go to 200%), so a big icon is rendered
    // at its own size rather than a 48dp bitmap stretched soft.
    var resolutionScale: Float = 1f

    // Read each time, from the density now: Display size changes it under a
    // view model that outlives the activity, and the icons stayed at the
    // old resolution.
    val iconSizePx: Int
        get() = (ICON_SIZE_DP * resources.displayMetrics.density * resolutionScale).roundToInt()

    // reshapeLegacy pref: legacy icons get rescaled so their visible content
    // occupies a uniform fraction of the canvas (and, when ON, a plate baked
    // behind them).
    var reshapeLegacy: ReshapeLegacy = ReshapeLegacy.OFF

    // iconNormalization pref (Normalize icon size, #332): with Reshape legacy
    // Off, a legacy icon or a pack's art drawn as it is shrinks to keep
    // within the Material icon guidelines (IconNormalizer); one already
    // inside them keeps its size. Reshape legacy's own resizing replaces it.
    var normalizeSize: Boolean = false

    // The scale a legacy icon or a pack's art drawn as it is gets from
    // Normalize icon size: at most its usual footprint, smaller for one that
    // covers more than the guidelines allow.
    private fun asIsFraction(source: Drawable): Float {
        val usual = 1f - LEGACY_INSET_FRACTION * 2
        if (!normalizeSize || reshapeLegacy != ReshapeLegacy.OFF) return usual
        return minOf(usual, IconNormalizer.scale(source, iconSizePx))
    }

    // iconShadow pref: the flattened icon gets a soft drop shadow baked into
    // the cached bitmap, so binds stay free of per-frame shadow work. The
    // shadow falls outside the icon (ShadowedIconDrawable), which keeps its
    // full size.
    var shadow: Boolean = false

    // iconGlass pref (IconGlass): adaptive icons, and the legacy plate, get a
    // glass pane in place of their background.
    var glass: IconGlass = IconGlass.OFF
    private val glassPainter = GlassPainter()

    fun render(
        source: Drawable,
        decoration: IconPackDecoration? = null,
        shape: IconShape = IconShape.SYSTEM,
        customShape: CustomShapeSpec? = null,
        glass: IconGlass = this.glass
    ): Drawable {
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // A user-chosen shape means every icon gets clipped to it, so legacy
        // icons must fill the frame rather than sit inset inside the mask.
        val shapePath = shape.pathFor(iconSizePx.toFloat(), customShape)

        // Only raw legacy icons need it: adaptive icons and pack plates are
        // already uniform, and shaped rendering deliberately overfills.
        val isRawLegacy = shapePath == null &&
            decoration?.back == null && source !is AdaptiveIconDrawable
        val shouldNormalize = reshapeLegacy != ReshapeLegacy.OFF && isRawLegacy
        // The pane's outline, when there is one: the shadow keeps clear of it.
        val glassMask = drawSource(canvas, source, decoration, shapePath, glass)

        // Glass sizes and places a legacy icon itself.
        var flat = if (shouldNormalize && glassMask == null) normalized(bitmap) else bitmap
        // ON: bake a plate behind the (normalized) legacy icon so it reads
        // like an adaptive icon.
        if (reshapeLegacy == ReshapeLegacy.ON && isRawLegacy && glassMask == null) flat = withPlate(flat)
        return if (shadow) withShadow(flat, glassMask) else BitmapDrawable(resources, flat)
    }


    // Applies the user's selected shape to the completed icon, after every
    // source and icon-pack layer has been composed. Existing shadows are
    // removed before clipping and rebuilt from the final silhouette.
    fun applyFinalShape(
        source: Drawable,
        shape: IconShape,
        customShape: CustomShapeSpec?,
        glass: IconGlass = IconGlass.OFF
    ): Drawable {
        val size = iconSizePx
        val shapePath = shape.pathFor(size.toFloat(), customShape) ?: return source
        val previousShadow = source as? ShadowedIconDrawable

        val content = createBitmap(size, size)
        val contentCanvas = Canvas(content)
        if (previousShadow != null) {
            val sourceSize = previousShadow.intrinsicWidth
            val sourceRect = android.graphics.Rect(
                previousShadow.margin,
                previousShadow.margin,
                previousShadow.margin + sourceSize,
                previousShadow.margin + sourceSize
            )
            contentCanvas.drawBitmap(
                previousShadow.bitmap,
                sourceRect,
                android.graphics.Rect(0, 0, size, size),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            source.setBounds(0, 0, size, size)
            source.draw(contentCanvas)
        }

        val shaped = createBitmap(size, size)
        val canvas = Canvas(shaped)
        canvas.drawBitmap(content, 0f, 0f, null)
        content.recycle()
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawPath(shapePath, maskPaint)

        return if (shadow || previousShadow != null) {
            withShadow(shaped, shapePath.takeIf { glass != IconGlass.OFF })
        } else {
            shaped.toDrawable(resources)
        }
    }

    // A picture an app made for its shortcut (a contact's photo) that fills
    // its frame: drawn whole across the icon's shape - the chosen one, or
    // the platform's mask with System - enlarged only as far as a round
    // picture needs to cover it. Glass and Reshape legacy's resizing and
    // plate read each photo differently (one filled the icon, the next sat
    // small on a pane, another lost its background to glass), so they leave
    // it alone, as they do a picked picture. With System and Reshape legacy
    // Off it is drawn as it is, like any legacy icon then. Null for one that
    // doesn't fill its frame (a glyph), which is drawn as a legacy icon.
    // With glass, the pane lies over the picture, tinted with its mean colour,
    // so the whole picture still shows through.
    fun renderPicture(
        picture: Bitmap,
        shape: IconShape,
        customShape: CustomShapeSpec?,
        glass: IconGlass = IconGlass.OFF
    ): Drawable? {
        val side = picture.width
        // One that isn't square stays clear here, so it is no picture.
        val pixels = IntArray(side * side)
        if (side == picture.height) picture.getPixels(pixels, 0, side, 0, 0, side, side)
        val box = PictureFrame.frame(pixels, side) ?: return null
        val size = iconSizePx
        val shapePath = shape.pathFor(size.toFloat(), customShape)
        return if (shapePath == null && reshapeLegacy == ReshapeLegacy.OFF && glass == IconGlass.OFF) {
            render(picture.toDrawable(resources), glass = IconGlass.OFF)
        } else {
            val mask = shapePath
                ?: glassPainter.systemMask(AdaptiveIconDrawable(Color.BLACK.toDrawable(), null), size)
            val cover = if (PictureFrame.square(pixels, side, box)) {
                1f
            } else {
                PictureFrame.reach(maskPixels(mask, size), size)
            }
            filledMask(picture, box, mask, cover, glass)
        }
    }

    // A folder's picked art under the icons' glass: a picture that fills its
    // frame shows through the pane, and one with a clear background sits on it
    // at its own size, never enlarged.
    fun renderFolderArt(source: Drawable, shape: IconShape, customShape: CustomShapeSpec?): Drawable {
        val size = iconSizePx
        val picture = createBitmap(size, size)
        source.setBounds(0, 0, size, size)
        source.draw(Canvas(picture))
        renderPicture(picture, shape, customShape, glass)?.let { return it }
        val mask = shape.pathFor(size.toFloat(), customShape)
            ?: glassPainter.systemMask(AdaptiveIconDrawable(Color.BLACK.toDrawable(), null), size)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.withClip(mask) { glassPainter.drawPane(this, glass, Color.WHITE, size, size) }
        glassPainter.drawEdge(canvas, mask, glass, size, size)
        canvas.drawBitmap(picture, 0f, 0f, null)
        return if (shadow) withShadow(bitmap, mask) else bitmap.toDrawable(resources)
    }

    // A missing icon-pack entry: square artwork is circularly cropped;
    // artwork with transparent corners keeps its original silhouette.
    fun renderMissingPackIcon(
        source: Drawable,
        shape: IconShape,
        customShape: CustomShapeSpec?,
        back: Drawable? = null,
        upon: Drawable? = null
    ): Drawable {
        val size = iconSizePx
        val shapePath = shape.pathFor(size.toFloat(), customShape)
            ?: glassPainter.systemMask(
                source as? AdaptiveIconDrawable ?: AdaptiveIconDrawable(Color.BLACK.toDrawable(), null),
                size
            )
        val artwork = createBitmap(size, size)
        val artworkCanvas = Canvas(artwork)
        if (source is AdaptiveIconDrawable) {
            val bleed = (size * ADAPTIVE_BLEED).roundToInt()
            val bounds = android.graphics.Rect(-bleed, -bleed, size + bleed, size + bleed)
            source.background?.let {
                it.setBounds(bounds)
                it.draw(artworkCanvas)
            }
            source.foreground?.let {
                it.setBounds(bounds)
                it.draw(artworkCanvas)
            }
        } else {
            source.setBounds(0, 0, size, size)
            source.draw(artworkCanvas)
        }
        val fallbackPlateColor = if (back == null) {
            DominantColor.of(artwork) ?: PLATE_COLOR
        } else {
            null
        }
        val artworkBounds = visibleArtworkBounds(artwork)
            ?: android.graphics.Rect(0, 0, size, size)
        val isAlreadyRound = hasTransparentCorners(artwork, artworkBounds)

        // The circular artwork occupies 75% of the selected plate.
        // Square artwork is enlarged by 21% before circular clipping.
        val radius = (size * 0.375f).roundToInt().coerceAtLeast(1)
        val diameter = radius * 2
        val sourceWidth = artworkBounds.width().coerceAtLeast(1)
        val sourceHeight = artworkBounds.height().coerceAtLeast(1)
        val center = size / 2f
        val scale = if (isAlreadyRound) {
            minOf(diameter.toFloat() / sourceWidth, diameter.toFloat() / sourceHeight)
        } else {
            val scaledArtwork = createBitmap(size, size)
            val scaledCanvas = Canvas(scaledArtwork)
            val scaledPixels = IntArray(size * size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val innerRadius = (radius - (size * 0.015f).coerceAtLeast(2f)).coerceAtLeast(0f)
            var ringPixels = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val dx = x + 0.5f - center
                    val dy = y + 0.5f - center
                    val distanceSquared = dx * dx + dy * dy
                    if (distanceSquared <= radius * radius &&
                        distanceSquared >= innerRadius * innerRadius
                    ) ringPixels++
                }
            }

            fun edgeCoverage(candidateScale: Float): Float {
                scaledArtwork.eraseColor(Color.TRANSPARENT)
                val width = sourceWidth * candidateScale
                val height = sourceHeight * candidateScale
                val target = RectF(
                    center - width / 2f,
                    center - height / 2f,
                    center + width / 2f,
                    center + height / 2f
                )
                scaledCanvas.drawBitmap(artwork, artworkBounds, target, paint)
                scaledArtwork.getPixels(scaledPixels, 0, size, 0, 0, size, size)
                var covered = 0
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val dx = x + 0.5f - center
                        val dy = y + 0.5f - center
                        val distanceSquared = dx * dx + dy * dy
                        if (distanceSquared <= radius * radius &&
                            distanceSquared >= innerRadius * innerRadius &&
                            Color.alpha(scaledPixels[y * size + x]) >= 128
                        ) covered++
                    }
                }
                return if (ringPixels == 0) 1f else covered.toFloat() / ringPixels
            }

            var low = maxOf(
                diameter.toFloat() / sourceWidth,
                diameter.toFloat() / sourceHeight
            )
            var high = low
            var coverage = edgeCoverage(high)
            var attempts = 0
            while (coverage < 0.995f && attempts < 12) {
                low = high
                high *= 1.5f
                coverage = edgeCoverage(high)
                attempts++
            }
            if (coverage >= 0.995f) {
                repeat(12) {
                    val middle = (low + high) / 2f
                    if (edgeCoverage(middle) >= 0.995f) high = middle else low = middle
                }
            }
            scaledArtwork.recycle()
            high
        }
        val targetWidth = sourceWidth * scale
        val targetHeight = sourceHeight * scale
        val artworkTarget = RectF(
            center - targetWidth / 2f,
            center - targetHeight / 2f,
            center + targetWidth / 2f,
            center + targetHeight / 2f
        )
        val circle = Path().apply {
            addCircle(center, center, radius.toFloat(), Path.Direction.CW)
        }

        val output = createBitmap(size, size)
        val canvas = Canvas(output)
        canvas.withClip(shapePath) {
            if (back == null) {
                drawPath(
                    shapePath,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = fallbackPlateColor ?: PLATE_COLOR
                    }
                )
            }
            back?.let { drawBackCoveringShape(this, it, shapePath, size) }
            if (isAlreadyRound) {
                drawBitmap(
                    artwork,
                    artworkBounds,
                    artworkTarget,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            } else {
                withClip(circle) {
                    drawBitmap(
                        artwork,
                        artworkBounds,
                        artworkTarget,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                }
            }
            upon?.let {
                it.setBounds(0, 0, size, size)
                it.draw(this)
            }
        }
        artwork.recycle()
        return output.toDrawable(resources)
    }

    private fun visibleArtworkBounds(bitmap: Bitmap): android.graphics.Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(pixels[y * width + x]) <= 8) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return null
        return android.graphics.Rect(left, top, right + 1, bottom + 1)
    }

    private fun hasTransparentCorners(bitmap: Bitmap, bounds: android.graphics.Rect): Boolean {
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val patchWidth = (width / 8).coerceAtLeast(2)
        val patchHeight = (height / 8).coerceAtLeast(2)

        fun cornerIsTransparent(left: Int, top: Int): Boolean {
            var transparent = 0
            var total = 0
            for (y in top until (top + patchHeight).coerceAtMost(bounds.bottom)) {
                for (x in left until (left + patchWidth).coerceAtMost(bounds.right)) {
                    total++
                    if (Color.alpha(bitmap.get(x, y)) <= 8) transparent++
                }
            }
            return total > 0 && transparent.toFloat() / total >= 0.75f
        }

        return cornerIsTransparent(bounds.left, bounds.top) &&
            cornerIsTransparent(bounds.right - patchWidth, bounds.top) &&
            cornerIsTransparent(bounds.left, bounds.bottom - patchHeight) &&
            cornerIsTransparent(bounds.right - patchWidth, bounds.bottom - patchHeight)
    }

    private fun drawBackCoveringShape(
        canvas: Canvas,
        back: Drawable,
        shapePath: Path,
        size: Int
    ) {
        val artwork = createBitmap(size, size)
        val artworkCanvas = Canvas(artwork)
        back.mutate().apply {
            setBounds(0, 0, size, size)
            draw(artworkCanvas)
        }

        val bounds = visibleArtworkBounds(artwork)
        if (bounds != null) {
            val shapeMask = createBitmap(size, size)
            Canvas(shapeMask).drawPath(
                shapePath,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            )
            val maskPixels = IntArray(size * size)
            shapeMask.getPixels(maskPixels, 0, size, 0, 0, size, size)
            val requiredPixels = maskPixels.count { Color.alpha(it) >= 128 }

            val scaledArtwork = createBitmap(size, size)
            val scaledCanvas = Canvas(scaledArtwork)
            val artworkPixels = IntArray(size * size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val center = size / 2f
            val baseScale = maxOf(
                size.toFloat() / bounds.width().coerceAtLeast(1),
                size.toFloat() / bounds.height().coerceAtLeast(1)
            )

            fun coverage(scale: Float): Float {
                scaledArtwork.eraseColor(Color.TRANSPARENT)
                val width = bounds.width() * scale
                val height = bounds.height() * scale
                val target = RectF(
                    center - width / 2f,
                    center - height / 2f,
                    center + width / 2f,
                    center + height / 2f
                )
                scaledCanvas.drawBitmap(artwork, bounds, target, paint)
                scaledArtwork.getPixels(artworkPixels, 0, size, 0, 0, size, size)
                var covered = 0
                for (index in maskPixels.indices) {
                    if (Color.alpha(maskPixels[index]) >= 128 &&
                        Color.alpha(artworkPixels[index]) >= 128
                    ) covered++
                }
                return if (requiredPixels == 0) 1f else covered.toFloat() / requiredPixels
            }

            var low = baseScale
            var high = baseScale
            var highCoverage = coverage(high)
            var attempts = 0
            while (highCoverage < 1f && attempts < 16) {
                low = high
                high *= 2f
                highCoverage = coverage(high)
                attempts++
            }
            if (highCoverage >= 1f) {
                repeat(10) {
                    val middle = (low + high) / 2f
                    if (coverage(middle) >= 1f) high = middle else low = middle
                }
            }

            val targetWidth = bounds.width() * high
            val targetHeight = bounds.height() * high
            val target = RectF(
                center - targetWidth / 2f,
                center - targetHeight / 2f,
                center + targetWidth / 2f,
                center + targetHeight / 2f
            )
            canvas.drawBitmap(artwork, bounds, target, paint)
            shapeMask.recycle()
            scaledArtwork.recycle()
        }
        artwork.recycle()
    }
    // An app's own icon, left as it comes. A legacy one that is a tile of its
    // own takes the icon shape at full size, as an adaptive icon does, and a
    // disc of its own is drawn whole at that size (see legacyDisc);
    // rescaling it as a glyph left it smaller than the icons around it, and
    // overfilling a chosen shape cropped its art. A legacy logo is kept
    // whole on a plate of that shape (see legacyLogo). A pack's art, a picked picture
    // and the Original variant don't come here.
    fun renderOwnIcon(source: Drawable, shape: IconShape, customShape: CustomShapeSpec?, glass: IconGlass): Drawable {
        val reshapes = source !is AdaptiveIconDrawable && reshapeLegacy != ReshapeLegacy.OFF && glass == IconGlass.OFF
        val reshaped = if (reshapes) {
            val size = iconSizePx
            val picture = createBitmap(size, size)
            source.setBounds(0, 0, size, size)
            source.draw(Canvas(picture))
            val shapePath = shape.pathFor(size.toFloat(), customShape)
            val mask = shapePath ?: glassPainter.systemMask(AdaptiveIconDrawable(Color.BLACK.toDrawable(), null), size)
            // Auto with the system shape only resizes, as render does.
            val plated = shapePath != null || reshapeLegacy == ReshapeLegacy.ON
            legacyTile(picture, mask) ?: legacyDisc(picture, mask)
                ?: if (plated) legacyLogo(picture, mask) else null
        } else {
            null
        }
        return reshaped ?: render(source, null, shape, customShape, glass)
    }

    // A pack's own art. It is finished as the pack made it, so Reshape legacy
    // leaves it alone: its resizing shrank it, and its light plate hid white
    // line art. In a chosen shape, art that is one solid body (a tile, a disc,
    // a plate with its logo) takes the shape as before; art drawn on nothing
    // (line art, a glyph) is drawn whole, since the shape's overfill and clip
    // cut its outer lines away.
    fun renderPackArt(source: Drawable, shape: IconShape, customShape: CustomShapeSpec?): Drawable {
        val shapePath = shape.pathFor(iconSizePx.toFloat(), customShape)
        val bitmap = createBitmap(iconSizePx, iconSizePx)
        drawBase(Canvas(bitmap), source, 1f, hasBack = false, hasPackDecoration = false, legacyFraction = asIsFraction(source))

        // A circular pack icon can contain transparent logo details. Detect
        // its outer disc independently of its interior, then enlarge it until
        // it covers the selected mask. This replaces the round silhouette.
        if (shapePath != null) {
            val pixels = IntArray(iconSizePx * iconSizePx)
            bitmap.getPixels(pixels, 0, iconSizePx, 0, 0, iconSizePx, iconSizePx)
            val disc = PictureFrame.discOutline(pixels, iconSizePx)
            if (disc != null) {
                val cover = PictureFrame.reach(maskPixels(shapePath, iconSizePx), iconSizePx) /
                    PictureFrame.boxReach(pixels, iconSizePx, disc)
                val shaped = filledMask(bitmap, disc, shapePath, cover)
                bitmap.recycle()
                return shaped
            }
        }

        val asMade = source !is AdaptiveIconDrawable && (shapePath == null || !solid(source))
        if (!asMade) {
            bitmap.recycle()
            return render(source, null, shape, customShape, IconGlass.OFF)
        }

        // Pack artwork without a solid background is a logo, not a tile.
        // Keep the complete artwork and put it on the user's selected shape.
        val flat = shapePath?.let { path ->
            withPlate(normalized(bitmap), path, SHAPED_PLATE_CONTENT)
        } ?: bitmap
        return if (shadow) withShadow(flat) else flat.toDrawable(resources)
    }

    // Whether the drawable, drawn at the icon's size, is one solid body.
    private fun solid(source: Drawable): Boolean {
        val size = iconSizePx
        val picture = createBitmap(size, size)
        source.setBounds(0, 0, size, size)
        source.draw(Canvas(picture))
        val pixels = IntArray(size * size)
        picture.getPixels(pixels, 0, size, 0, 0, size, size)
        picture.recycle()
        return PictureFrame.solid(pixels, size)
    }

    // The tile of a legacy icon cut to the icon's shape; null for one with no
    // tile, which stays a legacy icon.
    private fun legacyTile(picture: Bitmap, mask: Path): Drawable? {
        val size = iconSizePx
        val pixels = IntArray(size * size)
        picture.getPixels(pixels, 0, size, 0, 0, size, size)
        val box = PictureFrame.tile(pixels, size) ?: return null
        // Only as far as its own corners need to reach the mask's, so the art
        // on a rounder tile isn't cropped more than it has to be.
        val cover = PictureFrame.reach(maskPixels(mask, size), size) / PictureFrame.boxReach(pixels, size, box)
        return filledMask(picture, box, mask, cover.coerceAtLeast(1f))
    }

    // A legacy icon that is a disc of its own, drawn whole across the
    // icon's width inside its shape, as big as the icons beside it. As a
    // logo it sat at 72% of them, so at any icon size it looked a size
    // behind (#231). Null for one that isn't a disc.
    private fun legacyDisc(picture: Bitmap, mask: Path): Drawable? {
        val size = iconSizePx
        val pixels = IntArray(size * size)
        picture.getPixels(pixels, 0, size, 0, 0, size, size)
        val box = PictureFrame.discOutline(pixels, size) ?: return null
        val cover = PictureFrame.reach(maskPixels(mask, size), size) /
            PictureFrame.boxReach(pixels, size, box)
        return filledMask(picture, box, mask, cover)
    }

    // A legacy logo: whole, at the uniform size, on a plate cut to the
    // icon's shape - the chosen one, or the system's, so it matches the
    // icons beside it - (Reshape legacy On) or on nothing (Auto). Drawn
    // across the shape as a tile would be, it lost its edges to the clip.
    private fun legacyLogo(picture: Bitmap, shapePath: Path): Drawable {
        val logo = normalized(picture)
        val flat = if (reshapeLegacy == ReshapeLegacy.ON) withPlate(logo, shapePath, SHAPED_PLATE_CONTENT) else logo
        return if (shadow) withShadow(flat) else flat.toDrawable(resources)
    }

    // The picture's box drawn across the mask, enlarged by cover.
    private fun filledMask(
        picture: Bitmap,
        box: IntArray,
        mask: Path,
        cover: Float,
        glass: IconGlass = IconGlass.OFF
    ): Drawable {
        val size = iconSizePx
        val left = box[BakedBacking.LEFT]
        val top = box[BakedBacking.TOP]
        val right = box[BakedBacking.RIGHT]
        val bottom = box[BakedBacking.BOTTOM]
        val scale = size * cover / maxOf(right - left + 1, bottom - top + 1)
        val offsetX = size / 2f - (left + right + 1) / 2f * scale
        val offsetY = size / 2f - (top + bottom + 1) / 2f * scale
        val bitmap = createBitmap(size, size)
        Canvas(bitmap).withClip(mask) {
            withTranslation(offsetX, offsetY) {
                scale(scale, scale)
                drawBitmap(picture, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            if (glass != IconGlass.OFF) {
                val tint = picture.scale(1, 1)[0, 0]
                glassPainter.drawPane(this, glass, tint or Color.BLACK, size, size)
            }
        }
        if (glass != IconGlass.OFF) glassPainter.drawEdge(Canvas(bitmap), mask, glass, size, size)
        return if (shadow) withShadow(bitmap) else bitmap.toDrawable(resources)
    }

    // The icon onto the canvas: a pack's back, the source - as glass, shaped
    // or plain - then the pack's mask and upon. The glass pane's outline
    // when it drew one.
    private fun drawSource(
        canvas: Canvas,
        source: Drawable,
        decoration: IconPackDecoration?,
        shapePath: Path?,
        glass: IconGlass
    ): Path? {
        // The selected shape clips the complete fallback composition.
        val decorationClip = if (decoration != null && shapePath != null) {
            canvas.save().also { canvas.clipPath(shapePath) }
        } else {
            null
        }

        try {
            decoration?.back?.let { back ->
                back.setBounds(0, 0, iconSizePx, iconSizePx)
                back.draw(canvas)
            }

            val hasBack = decoration?.back != null
            val hasPackDecoration = decoration != null
            val packScale = decoration?.scale?.takeIf { it > 0f } ?: 1f

            val glassMask = if (glass != IconGlass.OFF && !hasBack) {
                val mask = glassShape(source, shapePath)
                val bleed = if (shapePath != null) ADAPTIVE_BLEED else PLATFORM_BLEED
                glassPainter.drawIcon(canvas, source, mask, glass, bleed)
                mask
            } else {
                null
            }

            val drawAppIcon: (Canvas) -> Unit = { target ->
                if (shapePath != null) {
                    val save = target.save()
                    try {
                        target.clipPath(shapePath)
                        drawShapedBase(
                            target,
                            source,
                            packScale,
                            hasBack,
                            hasPackDecoration,
                            shapePath
                        )
                    } finally {
                        target.restoreToCount(save)
                    }
                } else {
                    val legacyFraction = if (hasPackDecoration || source is AdaptiveIconDrawable) {
                        null
                    } else {
                        asIsFraction(source)
                    }
                    drawBase(target, source, packScale, hasBack, hasPackDecoration, legacyFraction)
                }
            }

            if (glassMask == null) {
                // A user-selected shape replaces the icon pack's own mask.
                val iconMask = decoration?.mask?.takeIf { shapePath == null }
                if (iconMask == null) {
                    drawAppIcon(canvas)
                } else {
                    // ADW iconmask cuts the app artwork, not the iconback plate.
                    val iconLayer = createBitmap(iconSizePx, iconSizePx)
                    try {
                        val iconLayerCanvas = Canvas(iconLayer)
                        drawAppIcon(iconLayerCanvas)

                        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                        }
                        val maskBitmap = drawableToBitmap(iconMask)
                        iconLayerCanvas.drawBitmap(
                            maskBitmap,
                            null,
                            android.graphics.Rect(0, 0, iconSizePx, iconSizePx),
                            maskPaint
                        )
                        canvas.drawBitmap(iconLayer, 0f, 0f, null)
                    } finally {
                        iconLayer.recycle()
                    }
                }
            }

            decoration?.upon?.let { upon ->
                upon.setBounds(0, 0, iconSizePx, iconSizePx)
                upon.draw(canvas)
            }
            return glassMask
        } finally {
            decorationClip?.let(canvas::restoreToCount)
        }
    }

    // A neutral plate - rounded, or cut to a chosen shape - with the icon
    // content inset onto it, matching the footprint adaptive icons render at.
    private fun withPlate(bitmap: Bitmap, shapePath: Path? = null, content: Float = PLATE_CONTENT): Bitmap {
        val output = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE_COLOR }
        canvas.drawPath(shapePath ?: platePath(iconSizePx, PLATE_RADIUS_FRACTION), platePaint)
        val inset = (iconSizePx * (1f - content) / 2f)
        canvas.drawBitmap(
            bitmap, null,
            RectF(inset, inset, iconSizePx - inset, iconSizePx - inset),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    // The glass icon's outline: the chosen shape; with System, the
    // platform's mask, or for a legacy icon the rounded plate Reshape legacy
    // icons would give it.
    private fun glassShape(source: Drawable, shapePath: Path?): Path =
        shapePath ?: (source as? AdaptiveIconDrawable)?.let { glassPainter.systemMask(it, iconSizePx) }
            ?: platePath(iconSizePx, PLATE_RADIUS_FRACTION)

    // Stamps a blurred alpha silhouette under the icon: ~30% black, 2dp blur,
    // nudged 1dp down. Runs once per render, inside the cache, not per bind.
    // The shadow gets a margin of its own around the full-size icon, and the
    // drawable draws that margin past its bounds. It used to be carved out of
    // the icon, as Launcher3's icons give up a margin inside their box, which
    // drew every icon 6dp under the size it was set to.
    // A glass icon's shadow is cast by its outline, not its pixels - a pane
    // is mostly see-through - and only what falls outside the pane is kept,
    // since a shadow seen through glass just muddies it.
    private fun withShadow(bitmap: Bitmap, glassMask: Path? = null): Drawable {
        val size = iconSizePx
        val density = resources.displayMetrics.density * resolutionScale
        val margin = ((SHADOW_BLUR_DP + SHADOW_OFFSET_Y_DP) * density).roundToInt()
        val blurPaint = Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(
                SHADOW_BLUR_DP * density, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        val offsets = IntArray(2)
        val solid = glassMask?.let { mask ->
            createBitmap(size, size).also {
                Canvas(it).drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
        }
        val silhouette = (solid ?: bitmap).extractAlpha(blurPaint, offsets)
        solid?.recycle()
        val output = Bitmap.createBitmap(size + 2 * margin, size + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW_COLOR }
        canvas.drawBitmap(
            silhouette,
            (margin + offsets[0]).toFloat(),
            margin + offsets[1] + SHADOW_OFFSET_Y_DP * density,
            shadowPaint
        )
        silhouette.recycle()
        if (glassMask != null) {
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.withTranslation(margin.toFloat(), margin.toFloat()) { drawPath(glassMask, clear) }
        }
        canvas.drawBitmap(bitmap, margin.toFloat(), margin.toFloat(), null)
        return ShadowedIconDrawable(output, margin)
    }

    // Finds the opaque bounding box (sampled every other pixel) and rescales
    // so its longer edge covers NORMALIZED_CONTENT of the canvas.
    private fun normalized(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(iconSizePx * iconSizePx)
        bitmap.getPixels(pixels, 0, iconSizePx, 0, 0, iconSizePx, iconSizePx)
        var minX = iconSizePx
        var minY = iconSizePx
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < iconSizePx) {
            var x = 0
            val rowBase = y * iconSizePx
            while (x < iconSizePx) {
                if ((pixels[rowBase + x] ushr 24) > ALPHA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        if (maxX < 0) return bitmap

        val contentSize = maxOf(maxX - minX + 1, maxY - minY + 1).toFloat()
        val scale = (iconSizePx * NORMALIZED_CONTENT) / contentSize
        if (scale in 0.95f..1.05f) return bitmap

        val output = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val contentCx = (minX + maxX + 1) / 2f
        val contentCy = (minY + maxY + 1) / 2f
        canvas.translate(iconSizePx / 2f - contentCx * scale, iconSizePx / 2f - contentCy * scale)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun drawBase(
        canvas: Canvas,
        source: Drawable,
        packScale: Float,
        hasBack: Boolean,
        hasPackDecoration: Boolean,
        legacyFraction: Float? = null
    ) {
        // ADW scale applies to the source artwork whenever fallback metadata exists.
        val fraction = when {
            hasPackDecoration -> packScale.coerceIn(0.3f, 1f)
            source is AdaptiveIconDrawable -> 1f
            else -> legacyFraction ?: (1f - (LEGACY_INSET_FRACTION * 2))
        }
        val drawnSize = (iconSizePx * fraction).roundToInt()
        val offset = (iconSizePx - drawnSize) / 2

        val adaptive = source as? AdaptiveIconDrawable

        val save = if (hasBack && adaptive != null) {
            val mask = Path(glassPainter.systemMask(adaptive, iconSizePx))
            Matrix().apply {
                setScale(fraction, fraction, iconSizePx / 2f, iconSizePx / 2f)
            }.also(mask::transform)
            canvas.save().also { canvas.clipPath(mask) }
        } else {
            -1
        }

        try {
            // Keep both adaptive layers. The pack back stays behind the complete app icon.
            source.setBounds(offset, offset, offset + drawnSize, offset + drawnSize)
            source.draw(canvas)
        } finally {
            if (save >= 0) canvas.restoreToCount(save)
        }
    }

    private fun drawShapedBase(
        canvas: Canvas,
        source: Drawable,
        packScale: Float,
        hasBack: Boolean,
        hasPackDecoration: Boolean,
        shapePath: Path
    ) {
        if (source is AdaptiveIconDrawable) {
            // A pack back replaces the adaptive background. Keep only its foreground.
            val fraction = if (hasPackDecoration) packScale.coerceIn(0.3f, 1f) else 1f
            val drawnSize = (iconSizePx * fraction).roundToInt()
            val offset = (iconSizePx - drawnSize) / 2
            val bleed = (drawnSize * ADAPTIVE_BLEED).roundToInt()
            val bounds = android.graphics.Rect(
                offset - bleed,
                offset - bleed,
                offset + drawnSize + bleed,
                offset + drawnSize + bleed
            )

            // Preserve the app's complete adaptive artwork over the pack back.
            source.background?.let {
                it.bounds = bounds
                it.draw(canvas)
            }
            source.foreground?.let {
                it.bounds = bounds
                it.draw(canvas)
            }
            return
        }

        if (hasBack) {
            // Keep the app artwork inside the scaled outline over the pack plate.
            val fraction = packScale.coerceIn(0.3f, 1f)
            val matrix = Matrix().apply {
                setScale(fraction, fraction, iconSizePx / 2f, iconSizePx / 2f)
            }
            val artworkShape = Path(shapePath).apply { transform(matrix) }
            val save = canvas.save()
            try {
                canvas.clipPath(artworkShape)
                drawBase(canvas, source, fraction, hasBack = true, hasPackDecoration = true)
            } finally {
                canvas.restoreToCount(save)
            }
            return
        }

        if (hasPackDecoration) {
            drawBase(canvas, source, packScale, hasBack = false, hasPackDecoration = true)
            return
        }

        // Without pack metadata, legacy artwork keeps the launcher's existing fill behavior.
        val drawnSize = (iconSizePx * LEGACY_SHAPE_FILL).roundToInt()
        val offset = (iconSizePx - drawnSize) / 2
        source.setBounds(offset, offset, offset + drawnSize, offset + drawnSize)
        source.draw(canvas)
    }

    // The pack ships one mask used by every icon; rasterize it once instead
    // of on each render.
    private var maskMemo: Pair<Drawable, Bitmap>? = null

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        maskMemo?.let { (memoDrawable, memoBitmap) ->
            if (memoDrawable === drawable) return memoBitmap
        }
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconSizePx, iconSizePx)
        drawable.draw(canvas)
        maskMemo = drawable to bitmap
        return bitmap
    }

    companion object {
        // The pixels behind a rendered icon, for code that reads them (cache
        // accounting, the dominant color); null for anything else.
        fun bitmapOf(icon: Drawable): Bitmap? =
            (icon as? BitmapDrawable)?.bitmap ?: (icon as? ShadowedIconDrawable)?.bitmap

        private const val ICON_SIZE_DP = 48f
        private const val LEGACY_INSET_FRACTION = 0.04f
        private const val LEGACY_SHAPE_FILL = 1.18f
        private const val ADAPTIVE_BLEED = 0.12f
        // AdaptiveIconDrawable's own: its layers reach a quarter past the icon.
        private const val PLATFORM_BLEED = 0.25f
        private const val NORMALIZED_CONTENT = 0.72f
        private const val ALPHA_THRESHOLD = 40
        // White like the platform's default adaptive plate; content sits at
        // roughly the adaptive foreground safe-zone fraction.
        private const val PLATE_COLOR = 0xFFF3F3F3.toInt()
        private const val PLATE_RADIUS_FRACTION = 0.25f
        private const val PLATE_CONTENT = 0.66f
        // On a plate cut to the icon's shape the logo is the whole icon, not a
        // glyph in a safe zone: about 68% of the shape with the uniform size,
        // which a square logo's corners still clear in a circle.
        private const val SHAPED_PLATE_CONTENT = 0.94f
        private const val SHADOW_BLUR_DP = 2f
        private const val SHADOW_OFFSET_Y_DP = 1f
        private const val SHADOW_COLOR = 0x4D000000
    }
}

// A mask drawn solid at the icon size, read back.
private fun maskPixels(mask: Path, size: Int): IntArray {
    val bitmap = createBitmap(size, size)
    Canvas(bitmap).drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    val pixels = IntArray(size * size)
    bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
    bitmap.recycle()
    return pixels
}

// The rounded plate Reshape legacy icons sit on, as an outline.
private fun platePath(size: Int, radiusFraction: Float): Path {
    val radius = size * radiusFraction
    return Path().apply {
        addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, Path.Direction.CW)
    }
}
