package com.openzilo.pose.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Simplified, watertight, UI-beautified 3D mesh derived directly from `openzilo-hardware/models/10_asm.stp`
 * (Size 10 OpenZilo Smart Ring, normalized by scale `1 / 14.065 mm` so outer barrel radius = `1.0` scene unit):
 *
 * - Ring Housing (`10_ring`):
 *   - Axial width `8.00 mm` (`X ∈ [-4.00, +4.00] mm`), concentric around `+X` finger bore
 *   - Outer barrel `R = 14.065 mm` with `0.35 mm` outer chamfers (`R = 13.715 mm`)
 *   - Inner comfort-fit bore `R = 9.900 mm` with `0.60 × 0.80 mm` inner chamfers (`R = 10.700 mm`)
 *   - Material: Satin Space-Grey Titanium (`COLOUR_RGB(0.694, 0.735, 0.798)` in `10_asm.stp`)
 * - Annular Side Cover (`10_cover`):
 *   - Flush on `+X = +4.00 mm` side face, `R ∈ [10.800, 13.000] mm` + two top inner alignment tabs (`±13°`)
 *   - Material: Signature Zilo Cobalt Blue satin composite
 * - Tactile Button (`button`):
 *   - Centered at `X = 0.00 mm` on top radial axis (`+Y_gl` = `+Z_body`), `r = 1.850 mm` inside `r = 1.950 mm` collar,
 *     protruding radially to `R = 14.830 mm` with smooth `0.35 mm` top dome fillet
 *   - Material: Signature Zilo Vibrant Orange
 * - Dual Microphone Ports:
 *   - Recessed acoustic ports (`r = 0.557 mm`, chamfer `r = 0.80 mm`) at `±38.2°` flanking the top button
 *
 * Interleaved vertex layout (11 floats / vertex = 44 bytes):
 * `[px, py, pz, nx, ny, nz, r, g, b, metallic, roughness]`
 */
class OpenZiloHardwareMesh private constructor(
    val vertexBuffer: FloatBuffer,
    val indexBuffer: ShortBuffer,
    val indexCount: Int,
) {
    companion object {
        const val FLOATS_PER_VERTEX = 11
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        // Exact millimeter constants from `openzilo-hardware/models/10_asm.stp`
        // Scaled to 0.54 scene units so the ring sits jewel-sized with generous viewport breathing room
        private const val SCALE = 0.54f / 14.065f
        private const val R_OUTER = 14.065f * SCALE
        private const val R_OUTER_CHAMFER = 13.715f * SCALE
        private const val R_INNER = 9.900f * SCALE
        private const val R_INNER_CHAMFER = 10.700f * SCALE
        private const val HALF_WIDTH = 4.000f * SCALE
        private const val X_OUTER_CHAMFER = 3.650f * SCALE
        private const val X_INNER_CHAMFER = 3.400f * SCALE

        // `10_cover` on +X face
        private const val R_COVER_IN = 10.800f * SCALE
        private const val R_COVER_OUT = 13.000f * SCALE
        private const val R_COVER_TAB_IN = 10.020f * SCALE
        private const val X_COVER = (4.000f + 0.025f) * SCALE

        // `button` on +Y_gl (+Z_body upright top)
        private const val R_BUTTON_CAP = 1.850f * SCALE
        private const val R_BUTTON_COLLAR = 1.980f * SCALE
        private const val Y_BUTTON_BASE = 13.850f * SCALE
        private const val Y_BUTTON_TOP = 14.830f * SCALE

        // Dual microphone ports at ±38.2° from button
        private const val MIC_ANGLE_RAD = 38.2f * (PI.toFloat() / 180f)
        private const val R_MIC_HOLE = 0.557f * SCALE
        private const val R_MIC_BEZEL = 0.820f * SCALE

        // Pure-Black 3D-Printed Polymer PBR Materials: [r, g, b, metallic, roughness]
        // Subtle roughness & tone contrast preserves crisp seam/button legibility on black-on-black parts.
        private val MAT_TITANIUM = floatArrayOf(0.042f, 0.045f, 0.050f, 0.04f, 0.42f)
        private val MAT_TITANIUM_INNER = floatArrayOf(0.036f, 0.038f, 0.043f, 0.03f, 0.48f)
        private val MAT_ZILO_BLUE_COVER = floatArrayOf(0.058f, 0.062f, 0.070f, 0.05f, 0.32f)
        private val MAT_ZILO_ORANGE_BTN = floatArrayOf(0.072f, 0.076f, 0.086f, 0.06f, 0.25f)
        private val MAT_DARK_COLLAR = floatArrayOf(0.012f, 0.013f, 0.016f, 0.02f, 0.72f)
        private val MAT_MIC_ACOUSTIC = floatArrayOf(0.005f, 0.006f, 0.008f, 0.01f, 0.90f)

        fun build(): OpenZiloHardwareMesh {
            val builder = MeshBuilder()
            buildMainHousingRevolved(builder, segments = 112)
            buildBlueSideCover(builder, segments = 112)
            buildOrangeButton(builder, radialSegments = 40)
            buildMicrophonePort(builder, angleFromTopRad = +MIC_ANGLE_RAD, segments = 24)
            buildMicrophonePort(builder, angleFromTopRad = -MIC_ANGLE_RAD, segments = 24)
            return builder.finish()
        }

        private fun buildMainHousingRevolved(b: MeshBuilder, segments: Int) {
            // Cross-section profile in (x_axial, r_radial, nx_2d, nr_2d, material)
            // Closed loop around the ring cross-section with split normals at sharp/chamfered transitions
            data class ProfileEdge(
                val x0: Float, val r0: Float,
                val x1: Float, val r1: Float,
                val mat: FloatArray,
                val smoothRadial0: Float? = null,
                val smoothAxial0: Float? = null,
                val smoothRadial1: Float? = null,
                val smoothAxial1: Float? = null,
            )

            val invSqrt2 = 0.70710677f
            val edges = listOf(
                // 1. Outer cylindrical barrel (-X_OUTER_CHAMFER .. +X_OUTER_CHAMFER)
                ProfileEdge(-X_OUTER_CHAMFER, R_OUTER, +X_OUTER_CHAMFER, R_OUTER, MAT_TITANIUM,
                    1f, 0f, 1f, 0f),
                // 2. +X outer 0.35mm chamfer
                ProfileEdge(+X_OUTER_CHAMFER, R_OUTER, +HALF_WIDTH, R_OUTER_CHAMFER, MAT_TITANIUM,
                    invSqrt2, invSqrt2, invSqrt2, invSqrt2),
                // 3. +X flat side rim (around the cover groove)
                ProfileEdge(+HALF_WIDTH, R_OUTER_CHAMFER, +HALF_WIDTH, R_INNER_CHAMFER, MAT_TITANIUM,
                    0f, 1f, 0f, 1f),
                // 4. +X inner comfort chamfer
                ProfileEdge(+HALF_WIDTH, R_INNER_CHAMFER, +X_INNER_CHAMFER, R_INNER, MAT_TITANIUM_INNER,
                    -0.6f, 0.8f, -0.85f, 0.52f),
                // 5. Inner smooth finger bore
                ProfileEdge(+X_INNER_CHAMFER, R_INNER, -X_INNER_CHAMFER, R_INNER, MAT_TITANIUM_INNER,
                    -1f, 0f, -1f, 0f),
                // 6. -X inner comfort chamfer
                ProfileEdge(-X_INNER_CHAMFER, R_INNER, -HALF_WIDTH, R_INNER_CHAMFER, MAT_TITANIUM_INNER,
                    -0.85f, -0.52f, -0.6f, -0.8f),
                // 7. -X flat side face
                ProfileEdge(-HALF_WIDTH, R_INNER_CHAMFER, -HALF_WIDTH, R_OUTER_CHAMFER, MAT_TITANIUM,
                    0f, -1f, 0f, -1f),
                // 8. -X outer 0.35mm chamfer
                ProfileEdge(-HALF_WIDTH, R_OUTER_CHAMFER, -X_OUTER_CHAMFER, R_OUTER, MAT_TITANIUM,
                    invSqrt2, -invSqrt2, invSqrt2, -invSqrt2),
            )

            // Angle theta = 0 points along +Y_gl (+Z_body = Top Button), circling in YZ plane:
            // y_gl = r * cos(theta), z_gl = r * sin(theta)
            for (edge in edges) {
                val baseIdx = b.vertexCount
                for (i in 0..segments) {
                    val theta = (i.toFloat() / segments) * (2f * PI.toFloat())
                    val ct = cos(theta)
                    val st = sin(theta)

                    val nr0 = edge.smoothRadial0 ?: 0f
                    val nx0 = edge.smoothAxial0 ?: 1f
                    b.addVertex(
                        px = edge.x0, py = edge.r0 * ct, pz = edge.r0 * st,
                        nx = nx0, ny = nr0 * ct, nz = nr0 * st,
                        mat = edge.mat,
                    )

                    val nr1 = edge.smoothRadial1 ?: 0f
                    val nx1 = edge.smoothAxial1 ?: 1f
                    b.addVertex(
                        px = edge.x1, py = edge.r1 * ct, pz = edge.r1 * st,
                        nx = nx1, ny = nr1 * ct, nz = nr1 * st,
                        mat = edge.mat,
                    )
                }
                for (i in 0 until segments) {
                    val i0 = baseIdx + i * 2
                    val i1 = i0 + 1
                    val i2 = i0 + 2
                    val i3 = i0 + 3
                    b.addTriangle(i0, i2, i1)
                    b.addTriangle(i1, i2, i3)
                }
            }
        }

        private fun buildBlueSideCover(b: MeshBuilder, segments: Int) {
            // Annular Zilo Blue cover on +X face with the two characteristic top inner alignment tabs from `10_cover`
            val baseIdx = b.vertexCount
            for (i in 0..segments) {
                val theta = (i.toFloat() / segments) * (2f * PI.toFloat())
                // Wrap angle into [-PI, PI] relative to Top (theta = 0)
                val wrapped = ((theta + PI.toFloat()) % (2f * PI.toFloat())) - PI.toFloat()
                val absDeg = abs(wrapped) * (180f / PI.toFloat())
                // Two inner alignment tabs at ±14° (width ~7°), matching `step-01.png` & `step-08.png`!
                val inTab = abs(absDeg - 14.5f) < 3.8f
                val rIn = if (inTab) R_COVER_TAB_IN else R_COVER_IN

                val ct = cos(theta)
                val st = sin(theta)
                b.addVertex(X_COVER, rIn * ct, rIn * st, 1f, 0f, 0f, MAT_ZILO_BLUE_COVER)
                b.addVertex(X_COVER, R_COVER_OUT * ct, R_COVER_OUT * st, 1f, 0f, 0f, MAT_ZILO_BLUE_COVER)
            }
            for (i in 0 until segments) {
                val i0 = baseIdx + i * 2
                val i1 = i0 + 1
                val i2 = i0 + 2
                val i3 = i0 + 3
                b.addTriangle(i0, i1, i2)
                b.addTriangle(i1, i3, i2)
            }
        }

        private fun buildOrangeButton(b: MeshBuilder, radialSegments: Int) {
            // Dark recessed collar ring on outer barrel around button
            val collarBase = b.vertexCount
            val yCollar = R_OUTER + 0.004f
            for (i in 0..radialSegments) {
                val phi = (i.toFloat() / radialSegments) * (2f * PI.toFloat())
                val cp = cos(phi)
                val sp = sin(phi)
                b.addVertex(R_BUTTON_CAP * 0.96f * cp, yCollar, R_BUTTON_CAP * 0.96f * sp, 0f, 1f, 0f, MAT_DARK_COLLAR)
                b.addVertex(R_BUTTON_COLLAR * cp, yCollar - 0.006f, R_BUTTON_COLLAR * sp, 0f, 1f, 0f, MAT_DARK_COLLAR)
            }
            for (i in 0 until radialSegments) {
                val i0 = collarBase + i * 2
                b.addTriangle(i0, i0 + 2, i0 + 1)
                b.addTriangle(i0 + 1, i0 + 2, i0 + 3)
            }

            // Tactile Orange Cylindrical Button + Filleted Dome Cap along +Y_gl
            val filletR = 0.35f * SCALE
            val rFlat = R_BUTTON_CAP - filletR
            val yFilletCenter = Y_BUTTON_TOP - filletR

            // Rings from base -> cylinder top -> 4 fillet steps -> center vertex
            val profile = listOf(
                Triple(R_BUTTON_CAP, Y_BUTTON_BASE, 0f to 1f), // (r, y, (ny, nr))
                Triple(R_BUTTON_CAP, yFilletCenter, 0f to 1f),
                Triple(rFlat + filletR * cos(0.52f), yFilletCenter + filletR * sin(0.52f), sin(0.52f) to cos(0.52f)),
                Triple(rFlat + filletR * cos(1.05f), yFilletCenter + filletR * sin(1.05f), sin(1.05f) to cos(1.05f)),
                Triple(rFlat, Y_BUTTON_TOP, 1f to 0f),
            )

            for (ringIdx in 0 until profile.size - 1) {
                val (r0, y0, n0) = profile[ringIdx]
                val (r1, y1, n1) = profile[ringIdx + 1]
                val base = b.vertexCount
                for (i in 0..radialSegments) {
                    val phi = (i.toFloat() / radialSegments) * (2f * PI.toFloat())
                    val cp = cos(phi)
                    val sp = sin(phi)
                    b.addVertex(r0 * cp, y0, r0 * sp, n0.second * cp, n0.first, n0.second * sp, MAT_ZILO_ORANGE_BTN)
                    b.addVertex(r1 * cp, y1, r1 * sp, n1.second * cp, n1.first, n1.second * sp, MAT_ZILO_ORANGE_BTN)
                }
                for (i in 0 until radialSegments) {
                    val i0 = base + i * 2
                    b.addTriangle(i0, i0 + 1, i0 + 2)
                    b.addTriangle(i0 + 1, i0 + 3, i0 + 2)
                }
            }

            // Cap disc
            val centerIdx = b.addVertex(0f, Y_BUTTON_TOP, 0f, 0f, 1f, 0f, MAT_ZILO_ORANGE_BTN)
            val rimStart = b.vertexCount
            for (i in 0..radialSegments) {
                val phi = (i.toFloat() / radialSegments) * (2f * PI.toFloat())
                b.addVertex(rFlat * cos(phi), Y_BUTTON_TOP, rFlat * sin(phi), 0f, 1f, 0f, MAT_ZILO_ORANGE_BTN)
            }
            for (i in 0 until radialSegments) {
                b.addTriangle(centerIdx, rimStart + i + 1, rimStart + i)
            }
        }

        private fun buildMicrophonePort(b: MeshBuilder, angleFromTopRad: Float, segments: Int) {
            // Radial outward axis at `angleFromTopRad` in YZ plane
            val uy = cos(angleFromTopRad)
            val uz = sin(angleFromTopRad)
            // Tangent basis `(tx, ty, tz) = (1, 0, 0)` and `(bx, by, bz) = (0, -uz, uy)`
            val by = -uz
            val bz = uy
            val xCenter = 0.05f * SCALE
            val rSurface = R_OUTER + 0.003f
            val rDepth = R_OUTER - 0.55f * SCALE

            // Chamfered metallic bezel into dark acoustic recess
            val bezelBase = b.vertexCount
            for (i in 0..segments) {
                val phi = (i.toFloat() / segments) * (2f * PI.toFloat())
                val cp = cos(phi)
                val sp = sin(phi)

                // Outer bezel lip
                val pxOut = xCenter + R_MIC_BEZEL * cp
                val pyOut = rSurface * uy + R_MIC_BEZEL * sp * by
                val pzOut = rSurface * uz + R_MIC_BEZEL * sp * bz
                b.addVertex(pxOut, pyOut, pzOut, -0.5f * cp, 0.86f * uy - 0.5f * sp * by, 0.86f * uz - 0.5f * sp * bz, MAT_DARK_COLLAR)

                // Inner hole rim
                val pxIn = xCenter + R_MIC_HOLE * cp
                val pyIn = rDepth * uy + R_MIC_HOLE * sp * by
                val pzIn = rDepth * uz + R_MIC_HOLE * sp * bz
                b.addVertex(pxIn, pyIn, pzIn, -cp, -sp * by, -sp * bz, MAT_MIC_ACOUSTIC)
            }
            for (i in 0 until segments) {
                val i0 = bezelBase + i * 2
                b.addTriangle(i0, i0 + 2, i0 + 1)
                b.addTriangle(i0 + 1, i0 + 2, i0 + 3)
            }

            // Dark acoustic bottom disc
            val centerIdx = b.addVertex(xCenter, rDepth * uy, rDepth * uz, uy, uy, uz, MAT_MIC_ACOUSTIC)
            val discStart = b.vertexCount
            for (i in 0..segments) {
                val phi = (i.toFloat() / segments) * (2f * PI.toFloat())
                val cp = cos(phi)
                val sp = sin(phi)
                b.addVertex(
                    xCenter + R_MIC_HOLE * cp,
                    rDepth * uy + R_MIC_HOLE * sp * by,
                    rDepth * uz + R_MIC_HOLE * sp * bz,
                    0f, uy, uz,
                    MAT_MIC_ACOUSTIC,
                )
            }
            for (i in 0 until segments) {
                b.addTriangle(centerIdx, discStart + i + 1, discStart + i)
            }
        }
    }

    private class MeshBuilder {
        private val verts = FloatArrayList(16_384)
        private val indices = ShortArrayList(24_576)
        var vertexCount: Int = 0
            private set

        fun addVertex(
            px: Float, py: Float, pz: Float,
            nx: Float, ny: Float, nz: Float,
            mat: FloatArray,
        ): Int {
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            verts.add(px); verts.add(py); verts.add(pz)
            verts.add(nx / len); verts.add(ny / len); verts.add(nz / len)
            verts.add(mat[0]); verts.add(mat[1]); verts.add(mat[2])
            verts.add(mat[3]); verts.add(mat[4])
            return vertexCount++
        }

        fun addTriangle(a: Int, b: Int, c: Int) {
            indices.add(a.toShort())
            indices.add(b.toShort())
            indices.add(c.toShort())
        }

        fun finish(): OpenZiloHardwareMesh {
            val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(verts.data, 0, verts.size).position(0)
            val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(indices.data, 0, indices.size).position(0)
            return OpenZiloHardwareMesh(vb, ib, indices.size)
        }
    }

    private class FloatArrayList(initialCapacity: Int) {
        var data = FloatArray(initialCapacity)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
    }

    private class ShortArrayList(initialCapacity: Int) {
        var data = ShortArray(initialCapacity)
        var size = 0
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
    }
}
