package dev.troupe.android.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.troupe.android.core.theme.LocalTroupePalette

/**
 * Troupe brand mark, matching `src/renderer/components/TermulMark.tsx` (viewBox 0 0 569 507).
 * Inherits theme ink so it reads crisply on every dark and light theme.
 */
@Composable
fun TroupeMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalTroupePalette.current.secondary,
    contentDescription: String? = "Troupe",
) {
    val semanticModifier = if (contentDescription != null) {
        modifier.semantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        modifier
    }
    Canvas(semanticModifier.size(size)) {
        val sx = this.size.width / 569f
        val sy = this.size.height / 507f
        val scale = minOf(sx, sy)
        val dx = (this.size.width - 569f * scale) / 2f
        val dy = (this.size.height - 507f * scale) / 2f
        withTransform({
            translate(dx, dy)
            scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero)
            translate(-446.04f, -826.59f)
            translate(37.282f, 448.302f)
            scale(2.924821f, 2.924821f, androidx.compose.ui.geometry.Offset.Zero)
        }) {
            // Left parallelogram bar
            val leftPath = Path().apply {
                fillType = PathFillType.EvenOdd
                moveTo(146.496f, 133.765f)
                lineTo(139.837f, 171.622f)
                cubicTo(139.261f, 174.901f, 141.784f, 177.907f, 145.113f, 177.907f)
                lineTo(190.062f, 177.907f)
                lineTo(167.442f, 268.284f)
                cubicTo(166.865f, 271.563f, 169.388f, 274.568f, 172.717f, 274.568f)
                lineTo(223.11f, 274.568f)
                cubicTo(225.71f, 274.568f, 227.935f, 272.701f, 228.385f, 270.14f)
                lineTo(258.769f, 135.621f)
                cubicTo(259.346f, 132.342f, 256.824f, 129.337f, 253.494f, 129.337f)
                lineTo(151.772f, 129.337f)
                cubicTo(149.171f, 129.337f, 146.947f, 131.204f, 146.496f, 133.765f)
                close()
            }
            drawPath(leftPath, color = tint)

            // Right T-hook (shifted -26.278515 on X)
            withTransform({ translate(-26.2785f, 0f) }) {
                val rightPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    moveTo(272.622f, 302.62f)
                    lineTo(251.873f, 302.62f)
                    cubicTo(254.474f, 302.62f, 256.699f, 300.753f, 257.148f, 298.192f)
                    lineTo(288.639f, 157.389f)
                    lineTo(248.668f, 157.389f)
                    lineTo(216.975f, 296.321f)
                    lineTo(248.668f, 157.389f)
                    lineTo(354.887f, 157.389f)
                    cubicTo(358.216f, 157.389f, 360.739f, 160.394f, 360.163f, 163.673f)
                    lineTo(353.504f, 201.53f)
                    cubicTo(353.053f, 204.091f, 350.829f, 205.959f, 348.228f, 205.959f)
                    lineTo(300.845f, 205.959f)
                    lineTo(277.898f, 298.192f)
                    cubicTo(277.448f, 300.753f, 275.223f, 302.62f, 272.622f, 302.62f)
                    close()
                }
                drawPath(rightPath, color = tint)
            }
        }
    }
}

/**
 * Lightweight 24dp stroke vector icons matching Troupe's Lucide / Hugeicons icon set.
 * Avoids pulling the 18 MB `material-icons-extended` artifact while keeping crisp 1.75px strokes.
 */
object TroupeIcons {
    private fun strokeIcon(name: String, strokeWidth: Float = 1.8f, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = build,
            )
        }.build()

    val Menu: ImageVector by lazy {
        strokeIcon("Menu") {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 18f); lineTo(20f, 18f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            moveTo(18f, 6f); lineTo(6f, 18f)
            moveTo(6f, 6f); lineTo(18f, 18f)
        }
    }

    val ArrowLeft: ImageVector by lazy {
        strokeIcon("ArrowLeft") {
            moveTo(19f, 12f); lineTo(5f, 12f)
            moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
        }
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp", strokeWidth = 2.1f) {
            moveTo(12f, 19f); lineTo(12f, 5f)
            moveTo(5f, 12f); lineTo(12f, 5f); lineTo(19f, 12f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown") {
            moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        strokeIcon("ChevronLeft") {
            moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f)
        }
    }

    val MoreHorizontal: ImageVector by lazy {
        strokeIcon("MoreHorizontal", strokeWidth = 2.4f) {
            moveTo(5f, 12f); lineTo(5.01f, 12f)
            moveTo(12f, 12f); lineTo(12.01f, 12f)
            moveTo(19f, 12f); lineTo(19.01f, 12f)
        }
    }

    val MessageSquare: ImageVector by lazy {
        strokeIcon("MessageSquare") {
            moveTo(21f, 15f)
            curveTo(21f, 16.1f, 20.1f, 17f, 19f, 17f)
            lineTo(7f, 17f); lineTo(3f, 21f); lineTo(3f, 5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
            lineTo(19f, 3f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
            close()
        }
    }

    val MessageSquarePlus: ImageVector by lazy {
        strokeIcon("MessageSquarePlus") {
            moveTo(21f, 15f)
            curveTo(21f, 16.1f, 20.1f, 17f, 19f, 17f)
            lineTo(7f, 17f); lineTo(3f, 21f); lineTo(3f, 5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
            lineTo(19f, 3f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
            close()
            moveTo(12f, 7f); lineTo(12f, 13f)
            moveTo(9f, 10f); lineTo(15f, 10f)
        }
    }

    val Terminal: ImageVector by lazy {
        strokeIcon("Terminal") {
            moveTo(4f, 17f); lineTo(10f, 11f); lineTo(4f, 5f)
            moveTo(12f, 19f); lineTo(20f, 19f)
        }
    }

    val FileCode: ImageVector by lazy {
        strokeIcon("FileCode") {
            moveTo(14f, 2f); lineTo(6f, 2f)
            curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            lineTo(4f, 20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            lineTo(18f, 22f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            lineTo(20f, 8f); close()
            moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
            moveTo(10f, 13f); lineTo(8f, 15f); lineTo(10f, 17f)
            moveTo(14f, 13f); lineTo(16f, 15f); lineTo(14f, 17f)
        }
    }

    val FileText: ImageVector by lazy {
        strokeIcon("FileText") {
            moveTo(14f, 2f); lineTo(6f, 2f)
            curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            lineTo(4f, 20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            lineTo(18f, 22f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            lineTo(20f, 8f); close()
            moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
            moveTo(8f, 13f); lineTo(16f, 13f)
            moveTo(8f, 17f); lineTo(13f, 17f)
        }
    }

    val Folder: ImageVector by lazy {
        strokeIcon("Folder") {
            moveTo(4f, 20f); lineTo(20f, 20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            lineTo(22f, 8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            lineTo(12.1f, 6f); lineTo(10.1f, 4f); lineTo(4f, 4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            close()
        }
    }

    val GitBranch: ImageVector by lazy {
        strokeIcon("GitBranch") {
            moveTo(6f, 3f); lineTo(6f, 15f)
            moveTo(18f, 9f); curveTo(18f, 12.3f, 15.3f, 15f, 12f, 15f); lineTo(6f, 15f)
            moveTo(6f, 18f); lineTo(6f, 18.01f)
            moveTo(18f, 6f); lineTo(18f, 6.01f)
        }
    }

    val Github: ImageVector by lazy {
        strokeIcon("Github") {
            moveTo(9f, 19f)
            curveTo(4f, 20.5f, 4f, 16.5f, 2f, 16f)
            moveTo(15f, 21f); lineTo(15f, 17.5f)
            curveTo(15f, 16.5f, 14.9f, 16.1f, 14.5f, 15.5f)
            curveTo(17.6f, 15.2f, 20.8f, 14f, 20.8f, 8.5f)
            curveTo(20.8f, 7.1f, 20.3f, 5.9f, 19.5f, 5f)
            curveTo(19.6f, 4.7f, 20f, 3.3f, 19.3f, 1.5f)
            curveTo(19.3f, 1.5f, 18.2f, 1.2f, 15.8f, 2.8f)
            curveTo(13.8f, 2.3f, 11.7f, 2.3f, 9.7f, 2.8f)
            curveTo(7.3f, 1.2f, 6.2f, 1.5f, 6.2f, 1.5f)
            curveTo(5.5f, 3.3f, 5.9f, 4.7f, 6f, 5f)
            curveTo(5.2f, 5.9f, 4.7f, 7.1f, 4.7f, 8.5f)
            curveTo(4.7f, 14f, 7.9f, 15.2f, 11f, 15.5f)
            curveTo(10.6f, 16.1f, 10.5f, 16.6f, 10.5f, 17.5f)
            lineTo(10.5f, 21f)
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") {
            moveTo(11f, 4f)
            curveTo(14.87f, 4f, 18f, 7.13f, 18f, 11f)
            curveTo(18f, 14.87f, 14.87f, 18f, 11f, 18f)
            curveTo(7.13f, 18f, 4f, 14.87f, 4f, 11f)
            curveTo(4f, 7.13f, 7.13f, 4f, 11f, 4f)
            close()
            moveTo(21f, 21f); lineTo(16.65f, 16.65f)
        }
    }

    val Settings: ImageVector by lazy {
        strokeIcon("Settings") {
            moveTo(12f, 9f)
            curveTo(13.66f, 9f, 15f, 10.34f, 15f, 12f)
            curveTo(15f, 13.66f, 13.66f, 15f, 12f, 15f)
            curveTo(10.34f, 15f, 9f, 13.66f, 9f, 12f)
            curveTo(9f, 10.34f, 10.34f, 9f, 12f, 9f)
            close()
            moveTo(19.4f, 15f); lineTo(21f, 13.5f); lineTo(21f, 10.5f); lineTo(19.4f, 9f)
            lineTo(18.5f, 6.8f); lineTo(19.3f, 4.7f); lineTo(17.2f, 2.6f); lineTo(15.1f, 3.4f)
            lineTo(13.5f, 2.6f); lineTo(10.5f, 2.6f); lineTo(8.9f, 3.4f)
            lineTo(6.8f, 2.6f); lineTo(4.7f, 4.7f); lineTo(5.5f, 6.8f); lineTo(4.6f, 9f)
            lineTo(3f, 10.5f); lineTo(3f, 13.5f); lineTo(4.6f, 15f); lineTo(5.5f, 17.2f)
            lineTo(4.7f, 19.3f); lineTo(6.8f, 21.4f); lineTo(8.9f, 20.6f)
            lineTo(10.5f, 21.4f); lineTo(13.5f, 21.4f); lineTo(15.1f, 20.6f)
            lineTo(17.2f, 21.4f); lineTo(19.3f, 19.3f); lineTo(18.5f, 17.2f); close()
        }
    }

    val Camera: ImageVector by lazy {
        strokeIcon("Camera") {
            moveTo(23f, 19f)
            curveTo(23f, 20.1f, 22.1f, 21f, 21f, 21f)
            lineTo(3f, 21f)
            curveTo(1.9f, 21f, 1f, 20.1f, 1f, 19f)
            lineTo(1f, 8f)
            curveTo(1f, 6.9f, 1.9f, 6f, 3f, 6f)
            lineTo(7f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(17f, 6f); lineTo(21f, 6f)
            curveTo(22.1f, 6f, 23f, 6.9f, 23f, 8f); close()
            moveTo(12f, 10f)
            curveTo(13.93f, 10f, 15.5f, 11.57f, 15.5f, 13.5f)
            curveTo(15.5f, 15.43f, 13.93f, 17f, 12f, 17f)
            curveTo(10.07f, 17f, 8.5f, 15.43f, 8.5f, 13.5f)
            curveTo(8.5f, 11.57f, 10.07f, 10f, 12f, 10f); close()
        }
    }

    val History: ImageVector by lazy {
        strokeIcon("History") {
            moveTo(3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
            curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
            curveTo(9.1f, 3f, 6.5f, 4.4f, 4.9f, 6.5f)
            moveTo(3f, 3f); lineTo(3f, 7f); lineTo(7f, 7f)
            moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
        }
    }

    val CalendarClock: ImageVector by lazy {
        strokeIcon("CalendarClock") {
            moveTo(8f, 2f); lineTo(8f, 6f)
            moveTo(16f, 2f); lineTo(16f, 6f)
            moveTo(3f, 10f); lineTo(21f, 10f)
            moveTo(19f, 4f); lineTo(5f, 4f)
            curveTo(3.9f, 4f, 3f, 4.9f, 3f, 6f)
            lineTo(3f, 19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            lineTo(12f, 21f)
            moveTo(17f, 14f); lineTo(17f, 17f); lineTo(19f, 18f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check", strokeWidth = 2.0f) {
            moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f)
        }
    }

    val Copy: ImageVector by lazy {
        strokeIcon("Copy") {
            moveTo(9f, 9f); lineTo(20f, 9f); lineTo(20f, 20f); lineTo(9f, 20f); close()
            moveTo(5f, 15f); lineTo(4f, 15f); lineTo(4f, 4f); lineTo(15f, 4f); lineTo(15f, 5f)
        }
    }

    val Edit: ImageVector by lazy {
        strokeIcon("Edit") {
            moveTo(11f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 13f)
            moveTo(18.5f, 2.5f); lineTo(21.5f, 5.5f); lineTo(12f, 15f); lineTo(9f, 15f); lineTo(9f, 12f); close()
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            moveTo(3f, 6f); lineTo(21f, 6f)
            moveTo(19f, 6f); lineTo(18f, 20f)
            curveTo(18f, 21.1f, 17.1f, 22f, 16f, 22f)
            lineTo(8f, 22f)
            curveTo(6.9f, 22f, 6f, 21.1f, 6f, 20f)
            lineTo(5f, 6f)
            moveTo(8f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(16f, 6f)
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            moveTo(21f, 12f)
            curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
            curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
            curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
            curveTo(15.3f, 3f, 18.2f, 4.8f, 19.7f, 7.5f)
            moveTo(21f, 3f); lineTo(21f, 8f); lineTo(16f, 8f)
        }
    }

    val Home: ImageVector by lazy {
        strokeIcon("Home") {
            moveTo(3f, 10.5f); lineTo(12f, 3f); lineTo(21f, 10.5f)
            moveTo(5f, 9f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 9f)
        }
    }

    val Shield: ImageVector by lazy {
        strokeIcon("Shield") {
            moveTo(12f, 2f); lineTo(4f, 5f); lineTo(4f, 11f)
            curveTo(4f, 16.5f, 7.4f, 20.7f, 12f, 22f)
            curveTo(16.6f, 20.7f, 20f, 16.5f, 20f, 11f)
            lineTo(20f, 5f); close()
        }
    }

    val Server: ImageVector by lazy {
        strokeIcon("Server") {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 10f); lineTo(3f, 10f); close()
            moveTo(3f, 14f); lineTo(21f, 14f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            moveTo(7f, 7f); lineTo(7.01f, 7f)
            moveTo(7f, 17f); lineTo(7.01f, 17f)
        }
    }

    val Star: ImageVector by lazy {
        strokeIcon("Star") {
            moveTo(12f, 2f); lineTo(15.09f, 8.26f); lineTo(22f, 9.27f)
            lineTo(17f, 14.14f); lineTo(18.18f, 21.02f); lineTo(12f, 17.77f)
            lineTo(5.82f, 21.02f); lineTo(7f, 14.14f); lineTo(2f, 9.27f)
            lineTo(8.91f, 8.26f); close()
        }
    }

    val StopSquare: ImageVector by lazy {
        ImageVector.Builder("StopSquare", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close()
            }
        }.build()
    }

    val Keyboard: ImageVector by lazy {
        strokeIcon("Keyboard") {
            moveTo(2f, 6f); lineTo(22f, 6f); lineTo(22f, 18f); lineTo(2f, 18f); close()
            moveTo(6f, 10f); lineTo(6.01f, 10f)
            moveTo(10f, 10f); lineTo(10.01f, 10f)
            moveTo(14f, 10f); lineTo(14.01f, 10f)
            moveTo(18f, 10f); lineTo(18.01f, 10f)
            moveTo(7f, 14f); lineTo(17f, 14f)
        }
    }

    val Clipboard: ImageVector get() = ClipboardPaste

    val ClipboardPaste: ImageVector by lazy {
        strokeIcon("ClipboardPaste") {
            moveTo(16f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); lineTo(6f, 20f); lineTo(6f, 4f); lineTo(8f, 4f)
            moveTo(9f, 2f); lineTo(15f, 2f); lineTo(15f, 6f); lineTo(9f, 6f); close()
        }
    }

    val Link: ImageVector by lazy {
        strokeIcon("Link") {
            moveTo(10f, 13f); curveTo(10.4f, 13.5f, 11f, 14f, 12f, 14f)
            lineTo(15f, 14f); curveTo(17.2f, 14f, 19f, 12.2f, 19f, 10f)
            curveTo(19f, 7.8f, 17.2f, 6f, 15f, 6f); lineTo(13f, 6f)
            moveTo(14f, 11f); curveTo(13.6f, 10.5f, 13f, 10f, 12f, 10f)
            lineTo(9f, 10f); curveTo(6.8f, 10f, 5f, 11.8f, 5f, 14f)
            curveTo(5f, 16.2f, 6.8f, 18f, 9f, 18f); lineTo(11f, 18f)
        }
    }

    val QrCode: ImageVector by lazy {
        strokeIcon("QrCode") {
            moveTo(3f, 3f); lineTo(9f, 3f); lineTo(9f, 9f); lineTo(3f, 9f); close()
            moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); lineTo(15f, 9f); close()
            moveTo(3f, 15f); lineTo(9f, 15f); lineTo(9f, 21f); lineTo(3f, 21f); close()
            moveTo(15f, 15f); lineTo(15f, 18f); lineTo(18f, 18f)
            moveTo(21f, 15f); lineTo(21f, 21f); lineTo(15f, 21f)
        }
    }

    val Key: ImageVector by lazy {
        strokeIcon("Key") {
            moveTo(8f, 15f)
            curveTo(10.2f, 15f, 12f, 13.2f, 12f, 11f)
            curveTo(12f, 8.8f, 10.2f, 7f, 8f, 7f)
            curveTo(5.8f, 7f, 4f, 8.8f, 4f, 11f)
            curveTo(4f, 13.2f, 5.8f, 15f, 8f, 15f); close()
            moveTo(12f, 11f); lineTo(21f, 11f)
            moveTo(18f, 11f); lineTo(18f, 14f)
            moveTo(21f, 11f); lineTo(21f, 14f)
        }
    }

    val Lock: ImageVector by lazy {
        strokeIcon("Lock") {
            moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 21f); lineTo(5f, 21f); close()
            moveTo(8f, 11f); lineTo(8f, 7f)
            curveTo(8f, 4.8f, 9.8f, 3f, 12f, 3f)
            curveTo(14.2f, 3f, 16f, 4.8f, 16f, 7f)
            lineTo(16f, 11f)
        }
    }

    val Wrench: ImageVector by lazy {
        strokeIcon("Wrench") {
            moveTo(14.7f, 6.3f)
            curveTo(15.5f, 4.5f, 17.6f, 3.6f, 19.4f, 4.3f)
            lineTo(16.8f, 6.9f); lineTo(17.1f, 9.2f); lineTo(19.4f, 9.5f)
            lineTo(22f, 6.9f)
            curveTo(22.7f, 8.7f, 21.8f, 10.8f, 20f, 11.6f)
            lineTo(9.5f, 22f); lineTo(2f, 14.5f); close()
        }
    }

    val Bot: ImageVector by lazy {
        strokeIcon("Bot") {
            moveTo(12f, 3f); lineTo(12f, 7f)
            moveTo(4f, 7f); lineTo(20f, 7f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(9f, 12f); lineTo(9.01f, 12f)
            moveTo(15f, 12f); lineTo(15.01f, 12f)
            moveTo(9f, 16f); lineTo(15f, 16f)
        }
    }

    val FileDiff: ImageVector by lazy {
        strokeIcon("FileDiff") {
            moveTo(14f, 2f); lineTo(6f, 2f); lineTo(6f, 22f); lineTo(18f, 22f); lineTo(18f, 8f); close()
            moveTo(14f, 2f); lineTo(14f, 8f); lineTo(18f, 8f)
            moveTo(12f, 11f); lineTo(12f, 15f)
            moveTo(10f, 13f); lineTo(14f, 13f)
            moveTo(10f, 18f); lineTo(14f, 18f)
        }
    }

    val Image: ImageVector by lazy {
        strokeIcon("Image") {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            moveTo(3f, 15f); lineTo(8f, 10f); lineTo(13f, 15f); lineTo(16f, 12f); lineTo(21f, 17f)
        }
    }

    val Sparkles: ImageVector by lazy {
        strokeIcon("Sparkles") {
            moveTo(12f, 3f); lineTo(13.8f, 8.2f); lineTo(19f, 10f); lineTo(13.8f, 11.8f)
            lineTo(12f, 17f); lineTo(10.2f, 11.8f); lineTo(5f, 10f); lineTo(10.2f, 8.2f); close()
        }
    }
}
