package KondoKit.util

import rt4.GlIndexedSprite
import rt4.GlSprite
import rt4.SoftwareIndexedSprite
import rt4.SoftwareSprite
import java.awt.Color
import java.awt.image.BufferedImage

interface BaseSprite {
    val width: Int
    val height: Int
}

interface IndexedSprite : BaseSprite {
    val pixels: ByteArray
    val palette: IntArray
}

interface NonIndexedSprite : BaseSprite {
    val pixels: IntArray?
}

fun adaptSoftwareSprite(sprite: SoftwareSprite): NonIndexedSprite {
    return object : NonIndexedSprite {
        override val width: Int = sprite.width
        override val height: Int = sprite.height
        override val pixels: IntArray? = sprite.pixels
    }
}

fun adaptSoftwareIndexedSprite(sprite: SoftwareIndexedSprite): IndexedSprite {
    return object : IndexedSprite {
        override val width: Int = sprite.width
        override val height: Int = sprite.height
        override val pixels: ByteArray = sprite.pixels
        override val palette: IntArray = sprite.pallet
    }
}

fun adaptGlSprite(sprite: GlSprite): NonIndexedSprite {
    return object : NonIndexedSprite {
        override val width: Int = sprite.width
        override val height: Int = sprite.height
        override val pixels: IntArray? = sprite.pixels
    }
}

fun adaptGlIndexedSprite(sprite: GlIndexedSprite): IndexedSprite {
    return object : IndexedSprite {
        override val width: Int = sprite.width
        override val height: Int = sprite.height
        override val pixels: ByteArray = sprite.pixels
        override val palette: IntArray = sprite.pallet
    }
}

object SpriteToBufferedImage {
    /**
     * Converts a BaseSprite into a BufferedImage.
     *
     * Handles both indexed and non-indexed sprites, with optional tinting and grayscale.
     *
     * @param sprite The sprite to be converted.
     * @param tint An optional Color to tint the image.
     * @param grayscale If true, converts the image to grayscale.
     * @param brightnessBoost A multiplier to boost the brightness of the image.
     * @return The BufferedImage created from the sprite.
     */
    private fun convertToBufferedImage(
        sprite: BaseSprite,
        tint: Color? = null,
        grayscale: Boolean = false,
        brightnessBoost: Float = 1.0f
    ): BufferedImage {
        val width = sprite.width
        val height = sprite.height
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        when (sprite) {
            is IndexedSprite -> {
                val pixels = sprite.pixels
                val palette = sprite.palette

                // Manually set pixels using the palette
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val index = pixels[y * width + x].toInt() and 0xFF
                        val color = palette[index]

                        // Apply grayscale or tint if provided
                        val finalColor = if (grayscale) {
                            applyGrayscale(Color(color, true), brightnessBoost)
                        } else if (tint != null) {
                            applyTint(Color(color, true), tint, brightnessBoost)
                        } else {
                            applyBrightness(Color(color, true), brightnessBoost)
                        }

                        image.setRGB(x, y, finalColor.rgb)
                    }
                }
            }
            is NonIndexedSprite -> {
                val pixels = sprite.pixels ?: return image // Handle null case for GlSprite

                // Manually set pixels directly
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val color = pixels[y * width + x]

                        // Apply grayscale or tint if provided
                        val finalColor = if (grayscale) {
                            applyGrayscale(Color(color, true), brightnessBoost)
                        } else if (tint != null) {
                            applyTint(Color(color, true), tint, brightnessBoost)
                        } else {
                            applyBrightness(Color(color, true), brightnessBoost)
                        }

                        image.setRGB(x, y, finalColor.rgb)
                    }
                }
            }
        }

        return image
    }

    /**
     * Applies a tint to a given color using the tint's alpha value to control the intensity.
     *
     * @param original The original color.
     * @param tint The tint color to be applied.
     * @param brightnessBoost A multiplier to boost the brightness of the image.
     * @return The tinted color.
     */
    private fun applyTint(original: Color, tint: Color, brightnessBoost: Float): Color {
        val boostedColor = applyBrightness(original, brightnessBoost)
        val r = (boostedColor.red * tint.red / 255).coerceIn(0, 255)
        val g = (boostedColor.green * tint.green / 255).coerceIn(0, 255)
        val b = (boostedColor.blue * tint.blue / 255).coerceIn(0, 255)
        return Color(r, g, b, boostedColor.alpha)
    }

    /**
     * Boosts the brightness of a given color.
     *
     * @param original The original color.
     * @param factor The multiplier to boost the brightness.
     * @return The color with boosted brightness.
     */
    private fun applyBrightness(original: Color, factor: Float): Color {
        val r = (original.red * factor).coerceIn(0.0f, 255.0f).toInt()
        val g = (original.green * factor).coerceIn(0.0f, 255.0f).toInt()
        val b = (original.blue * factor).coerceIn(0.0f, 255.0f).toInt()
        return Color(r, g, b, original.alpha)
    }

    /**
     * Converts a color to grayscale and applies a brightness boost.
     *
     * @param original The original color.
     * @param brightnessBoost A multiplier to boost the brightness.
     * @return The grayscale version of the color with boosted brightness.
     */
    private fun applyGrayscale(original: Color, brightnessBoost: Float): Color {
        // Calculate the grayscale value using the luminosity method
        val grayValue = (0.3 * original.red + 0.59 * original.green + 0.11 * original.blue).toInt()
        val boostedGray = (grayValue * brightnessBoost).coerceIn(0.0f, 255.0f).toInt()
        return Color(boostedGray, boostedGray, boostedGray, original.alpha)
    }

    /**
     * Converts an unknown sprite object into a BufferedImage if it matches a known sprite type.
     *
     * @param sprite The sprite object to be converted.
     * @param tint An optional Color to tint the image.
     * @param grayscale If true, converts the image to grayscale.
     * @param brightnessBoost A multiplier to boost the brightness of the image.
     * @return The BufferedImage created from the sprite or a default image if unsupported.
     */
    fun getBufferedImageFromSprite(
        sprite: Any?,
        tint: Color? = null,
        grayscale: Boolean = false,
        brightnessBoost: Float = 1.0f
    ): BufferedImage {
        return when (sprite) {
            is SoftwareSprite -> convertToBufferedImage(adaptSoftwareSprite(sprite), tint, grayscale, brightnessBoost)
            is SoftwareIndexedSprite -> convertToBufferedImage(adaptSoftwareIndexedSprite(sprite), tint, grayscale, brightnessBoost)
            is GlSprite -> convertToBufferedImage(adaptGlSprite(sprite), tint, grayscale, brightnessBoost)
            is GlIndexedSprite -> convertToBufferedImage(adaptGlIndexedSprite(sprite), tint, grayscale, brightnessBoost)
            else -> BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) // Default empty image for unsupported types
        }
    }
}
