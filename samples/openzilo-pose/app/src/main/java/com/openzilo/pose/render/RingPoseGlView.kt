package com.openzilo.pose.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.openzilo.pose.LiveMotionSample
import com.openzilo.pose.PoseQuaternion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Interactive OpenGL ES 3.2 4x-MSAA PBR viewport rendering the `openzilo-hardware` 3D Ring,
 * floating studio horizon grid, ring body coordinate axes, and live gravity vector.
 */
@SuppressLint("ViewConstructor")
class RingPoseGlView(
    context: Context,
    motionStepper: (Double) -> LiveMotionSample,
    ledPulseProvider: () -> Float,
) : GLSurfaceView(context) {

    val renderer = RingGlRenderer(motionStepper, ledPulseProvider)

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoomBy(detector.scaleFactor)
                return true
            }
        },
    )

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser())
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun resetCamera() = renderer.resetCamera()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    renderer.orbitBy(dx, dy)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
        }
        return true
    }

    private class MsaaConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val msaaAttribs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, 1,
                EGL10.EGL_SAMPLES, 4,
                EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (egl.eglChooseConfig(display, msaaAttribs, configs, 1, numConfig) && numConfig[0] > 0) {
                return configs[0]!!
            }
            val fallback = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE,
            )
            check(egl.eglChooseConfig(display, fallback, configs, 1, numConfig) && numConfig[0] > 0) {
                "Unable to select OpenGL ES 3 config"
            }
            return configs[0]!!
        }
    }
}

class RingGlRenderer(
    private val motionStepper: (Double) -> LiveMotionSample,
    private val ledPulseProvider: () -> Float,
) : GLSurfaceView.Renderer {

    @Volatile var showAxes: Boolean = true
    @Volatile var showGrid: Boolean = true

    @Volatile private var camAzimuthDeg = 34f
    @Volatile private var camElevationDeg = 22f
    @Volatile private var camDistance = 4.45f

    private var smoothedQ: PoseQuaternion = PoseQuaternion.IDENTITY
    private var lastFrameNs: Long = 0L

    private var pbrProgram = 0
    private var lineProgram = 0
    private var ringVao = 0
    private var ringIndexCount = 0
    private var gridVao = 0
    private var gridVertexCount = 0
    private var axesVao = 0
    private var axesVertexCount = 0

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun orbitBy(dxPx: Float, dyPx: Float) {
        camAzimuthDeg = (camAzimuthDeg - dxPx * 0.28f) % 360f
        camElevationDeg = (camElevationDeg + dyPx * 0.22f).coerceIn(-75f, 75f)
    }

    fun zoomBy(scaleFactor: Float) {
        camDistance = (camDistance / scaleFactor).coerceIn(2.2f, 8.2f)
    }

    fun resetCamera() {
        camAzimuthDeg = 34f
        camElevationDeg = 22f
        camDistance = 4.45f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES32.glClearColor(0.062f, 0.078f, 0.115f, 1.0f)
        GLES32.glEnable(GLES32.GL_DEPTH_TEST)
        GLES32.glEnable(GLES32.GL_CULL_FACE)
        GLES32.glCullFace(GLES32.GL_BACK)
        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)

        pbrProgram = linkProgram(PBR_VERTEX_SHADER, PBR_FRAGMENT_SHADER)
        lineProgram = linkProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)

        val mesh = OpenZiloHardwareMesh.build()
        ringIndexCount = mesh.indexCount
        val vaos = IntArray(3)
        val vbos = IntArray(4)
        GLES32.glGenVertexArrays(3, vaos, 0)
        GLES32.glGenBuffers(4, vbos, 0)

        ringVao = vaos[0]
        GLES32.glBindVertexArray(ringVao)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vbos[0])
        GLES32.glBufferData(
            GLES32.GL_ARRAY_BUFFER,
            mesh.vertexBuffer.capacity() * 4,
            mesh.vertexBuffer,
            GLES32.GL_STATIC_DRAW,
        )
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, vbos[1])
        GLES32.glBufferData(
            GLES32.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indexBuffer.capacity() * 2,
            mesh.indexBuffer,
            GLES32.GL_STATIC_DRAW,
        )
        val stride = OpenZiloHardwareMesh.STRIDE_BYTES
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, stride, 0)
        GLES32.glEnableVertexAttribArray(1)
        GLES32.glVertexAttribPointer(1, 3, GLES32.GL_FLOAT, false, stride, 3 * 4)
        GLES32.glEnableVertexAttribArray(2)
        GLES32.glVertexAttribPointer(2, 3, GLES32.GL_FLOAT, false, stride, 6 * 4)
        GLES32.glEnableVertexAttribArray(3)
        GLES32.glVertexAttribPointer(3, 2, GLES32.GL_FLOAT, false, stride, 9 * 4)

        // World horizon circle + polar grid at Y = -1.35
        val gridVerts = buildPolarHorizonGrid()
        gridVertexCount = gridVerts.capacity() / 7
        gridVao = vaos[1]
        GLES32.glBindVertexArray(gridVao)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vbos[2])
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, gridVerts.capacity() * 4, gridVerts, GLES32.GL_STATIC_DRAW)
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 7 * 4, 0)
        GLES32.glEnableVertexAttribArray(1)
        GLES32.glVertexAttribPointer(1, 4, GLES32.GL_FLOAT, false, 7 * 4, 3 * 4)

        // Ring local X (Bore, Red), Y_gl (+Z_body Top Button, Blue-Cyan), Z_gl (-Y_body Side, Green)
        val axisVerts = buildRingBodyAxes()
        axesVertexCount = axisVerts.capacity() / 7
        axesVao = vaos[2]
        GLES32.glBindVertexArray(axesVao)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, vbos[3])
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, axisVerts.capacity() * 4, axisVerts, GLES32.GL_STATIC_DRAW)
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glVertexAttribPointer(0, 3, GLES32.GL_FLOAT, false, 7 * 4, 0)
        GLES32.glEnableVertexAttribArray(1)
        GLES32.glVertexAttribPointer(1, 4, GLES32.GL_FLOAT, false, 7 * 4, 3 * 4)

        GLES32.glBindVertexArray(0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        val fovy = if (aspect < 1f) 50f else 38f
        Matrix.perspectiveM(projMatrix, 0, fovy, aspect, 0.2f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT or GLES32.GL_DEPTH_BUFFER_BIT)

        val nowNs = System.nanoTime()
        val dtFrameS = if (lastFrameNs == 0L) 1.0 / 120.0 else ((nowNs - lastFrameNs) / 1_000_000_000.0).coerceIn(0.002, 0.050)
        lastFrameNs = nowNs

        val sample = motionStepper(dtFrameS)
        val speedDps = sqrt(sample.wxDps * sample.wxDps + sample.wyDps * sample.wyDps + sample.wzDps * sample.wzDps)
        // Sub-10ms micro-polish filter on top of the 100Hz PLL trajectory spline
        val tau = if (sample.stationary && speedDps < 1.8) 0.045 else 0.012
        val alpha = (1.0 - exp(-dtFrameS / tau)).coerceIn(0.08, 0.92)
        smoothedQ = PoseQuaternion.slerp(smoothedQ, sample.q, alpha)

        val azRad = Math.toRadians(camAzimuthDeg.toDouble())
        val elRad = Math.toRadians(camElevationDeg.toDouble())
        val dist = camDistance
        val eyeX = (dist * cos(elRad) * sin(azRad)).toFloat()
        val eyeY = (dist * sin(elRad)).toFloat()
        val eyeZ = (dist * cos(elRad) * cos(azRad)).toFloat()

        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, -0.06f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // 1. Draw floating polar horizon reference grid + world zenith Plumblines
        if (showGrid) {
            GLES32.glUseProgram(lineProgram)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, identityMatrix, 0)
            GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvpMatrix, 0)
            GLES32.glBindVertexArray(gridVao)
            GLES32.glLineWidth(2.0f)
            GLES32.glDrawArrays(GLES32.GL_LINES, 0, gridVertexCount)
        }

        // 2. Draw OpenZilo Hardware 3D Ring with Cook-Torrance GGX PBR + Studio Environment IBL
        smoothedQ.toOpenGlMatrix(modelMatrix)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES32.glUseProgram(pbrProgram)
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(pbrProgram, "uMvp"), 1, false, mvpMatrix, 0)
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(pbrProgram, "uModel"), 1, false, modelMatrix, 0)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(pbrProgram, "uCameraPos"), eyeX, eyeY, eyeZ)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(pbrProgram, "uButtonPulse"), ledPulseProvider())

        GLES32.glBindVertexArray(ringVao)
        GLES32.glDrawElements(GLES32.GL_TRIANGLES, ringIndexCount, GLES32.GL_UNSIGNED_SHORT, 0)

        // 3. Draw Stationary World Reference Axes (X / Y / Z fixed in world space — never rotating with the ring!)
        if (showAxes) {
            GLES32.glUseProgram(lineProgram)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, identityMatrix, 0)
            GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvpMatrix, 0)
            GLES32.glBindVertexArray(axesVao)
            GLES32.glLineWidth(4.0f)
            GLES32.glDrawArrays(GLES32.GL_LINES, 0, axesVertexCount)
        }

        GLES32.glBindVertexArray(0)
    }

    companion object {
        private fun buildPolarHorizonGrid(): FloatBuffer {
            val floats = ArrayList<Float>(1024)
            fun addLine(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
                floats.add(x0); floats.add(y0); floats.add(z0); floats.add(r); floats.add(g); floats.add(b); floats.add(a)
                floats.add(x1); floats.add(y1); floats.add(z1); floats.add(r); floats.add(g); floats.add(b); floats.add(a)
            }
            val yFloor = -0.82f
            val segs = 72
            for (ring in listOf(0.46f to 0.28f, 0.84f to 0.40f, 1.25f to 0.16f)) {
                val (rad, alpha) = ring
                for (i in 0 until segs) {
                    val a0 = (i.toFloat() / segs) * (2.0 * Math.PI)
                    val a1 = ((i + 1).toFloat() / segs) * (2.0 * Math.PI)
                    addLine(
                        (rad * cos(a0)).toFloat(), yFloor, (rad * sin(a0)).toFloat(),
                        (rad * cos(a1)).toFloat(), yFloor, (rad * sin(a1)).toFloat(),
                        0.28f, 0.52f, 0.88f, alpha,
                    )
                }
            }
            // Cardinal horizon crosshairs
            addLine(-1.35f, yFloor, 0f, 1.35f, yFloor, 0f, 0.35f, 0.55f, 0.85f, 0.30f)
            addLine(0f, yFloor, -1.35f, 0f, yFloor, 1.35f, 0.35f, 0.55f, 0.85f, 0.30f)
            // World vertical gravity / zenith reference axis through center
            addLine(0f, yFloor, 0f, 0f, 0.92f, 0f, 0.22f, 0.82f, 0.58f, 0.40f)

            val buf = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (v in floats) buf.put(v)
            buf.position(0)
            return buf
        }

        private fun buildRingBodyAxes(): FloatBuffer {
            val floats = floatArrayOf(
                // +X Bore axis (Coral Red)
                0f, 0f, 0f, 0.98f, 0.36f, 0.38f, 0.92f,
                0.78f, 0f, 0f, 0.98f, 0.36f, 0.38f, 0.92f,
                // +Z Body Top / Button radial axis -> +Y_gl (Sky Cyan)
                0f, 0f, 0f, 0.22f, 0.76f, 0.98f, 0.95f,
                0f, 0.86f, 0f, 0.22f, 0.76f, 0.98f, 0.95f,
                // +Y Body Lateral side axis -> -Z_gl (Emerald)
                0f, 0f, 0f, 0.20f, 0.86f, 0.54f, 0.88f,
                0f, 0f, -0.78f, 0.20f, 0.86f, 0.54f, 0.88f,
            )
            val buf = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(floats).position(0)
            return buf
        }

        private fun linkProgram(vertSrc: String, fragSrc: String): Int {
            val vs = compileShader(GLES32.GL_VERTEX_SHADER, vertSrc)
            val fs = compileShader(GLES32.GL_FRAGMENT_SHADER, fragSrc)
            val prog = GLES32.glCreateProgram()
            GLES32.glAttachShader(prog, vs)
            GLES32.glAttachShader(prog, fs)
            GLES32.glLinkProgram(prog)
            val status = IntArray(1)
            GLES32.glGetProgramiv(prog, GLES32.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES32.GL_TRUE) {
                "GL link error: ${GLES32.glGetProgramInfoLog(prog)}"
            }
            GLES32.glDeleteShader(vs)
            GLES32.glDeleteShader(fs)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES32.glCreateShader(type)
            GLES32.glShaderSource(shader, src)
            GLES32.glCompileShader(shader)
            val status = IntArray(1)
            GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES32.GL_TRUE) {
                "GL shader error: ${GLES32.glGetShaderInfoLog(shader)}"
            }
            return shader
        }

        private const val PBR_VERTEX_SHADER = """#version 320 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aAlbedo;
            layout(location = 3) in vec2 aMaterial;

            uniform mat4 uMvp;
            uniform mat4 uModel;

            out vec3 vLocalPos;
            out vec3 vWorldPos;
            out vec3 vWorldNormal;
            out vec3 vAlbedo;
            out vec2 vMaterial;

            void main() {
                vLocalPos = aPosition;
                vec4 worldPos = uModel * vec4(aPosition, 1.0);
                vWorldPos = worldPos.xyz;
                vWorldNormal = normalize(mat3(uModel) * aNormal);
                vAlbedo = aAlbedo;
                vMaterial = aMaterial;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        private const val PBR_FRAGMENT_SHADER = """#version 320 es
            precision highp float;

            in vec3 vLocalPos;
            in vec3 vWorldPos;
            in vec3 vWorldNormal;
            in vec3 vAlbedo;
            in vec2 vMaterial; // x = metallic, y = roughness

            uniform vec3 uCameraPos;
            uniform float uButtonPulse;

            out vec4 fragColor;

            const float PI = 3.14159265359;

            float hash31(vec3 p) {
                p = fract(p * 0.3183099 + 0.1);
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }

            float distributionGGX(vec3 N, vec3 H, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float NdotH = max(dot(N, H), 0.0);
                float NdotH2 = NdotH * NdotH;
                float denom = (NdotH2 * (a2 - 1.0) + 1.0);
                return a2 / max(PI * denom * denom, 1e-5);
            }

            float geometrySchlickGGX(float NdotV, float roughness) {
                float r = roughness + 1.0;
                float k = (r * r) / 8.0;
                return NdotV / max(NdotV * (1.0 - k) + k, 1e-5);
            }

            float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
                return geometrySchlickGGX(max(dot(N, V), 0.0), roughness) *
                       geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
            }

            vec3 fresnelSchlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
            }

            vec3 evalLight(vec3 L, vec3 radiance, vec3 N, vec3 V, vec3 albedo, float metallic, float roughness, vec3 F0) {
                vec3 H = normalize(V + L);
                float NDF = distributionGGX(N, H, roughness);
                float G = geometrySmith(N, V, L, roughness);
                vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
                vec3 spec = (NDF * G * F) / max(4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0), 1e-4);
                vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
                float NdotL = max(dot(N, L), 0.0);
                return (kD * albedo / PI + spec) * radiance * NdotL;
            }

            // High-contrast studio rim softboxes so pure-black 3D-printed bevels & button contour pop cleanly
            vec3 sampleStudioEnv(vec3 R, float roughness) {
                float up = clamp(R.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 horizon = vec3(0.28, 0.35, 0.48);
                vec3 sky = vec3(0.82, 0.90, 1.05);
                vec3 ground = vec3(0.08, 0.10, 0.15);
                vec3 base = mix(mix(ground, horizon, smoothstep(0.0, 0.45, up)), sky, smoothstep(0.45, 1.0, up));
                float strip1 = pow(max(dot(R, normalize(vec3(0.52, 0.76, 0.38))), 0.0), mix(42.0, 6.0, roughness));
                float strip2 = pow(max(dot(R, normalize(vec3(-0.78, 0.42, -0.46))), 0.0), mix(28.0, 5.0, roughness));
                float strip3 = pow(max(dot(R, normalize(vec3(0.0, -0.35, -0.93))), 0.0), mix(22.0, 4.0, roughness));
                return base + vec3(1.55, 1.60, 1.70) * strip1 + vec3(0.85, 1.15, 1.65) * strip2 + vec3(0.65, 0.78, 1.0) * strip3;
            }

            void main() {
                vec3 N = normalize(vWorldNormal);
                vec3 V = normalize(uCameraPos - vWorldPos);

                // Subtle 3D-printed SLS/resin micro-grain + ultra-fine axial layer ripple
                float layerWave = sin(vLocalPos.x * 260.0) * 0.012;
                float grain = (hash31(floor(vLocalPos * 220.0)) - 0.5) * 0.035;

                float metallic = vMaterial.x;
                float roughness = clamp(vMaterial.y + layerWave + grain, 0.14, 0.95);
                vec3 albedo = max(vAlbedo + grain * 0.08, vec3(0.004));

                // Matte/satin black polymer dielectric F0 (~0.048) gives realistic grazing edge sheen
                vec3 F0 = mix(vec3(0.048), albedo, metallic);

                vec3 Lo = vec3(0.0);
                Lo += evalLight(normalize(vec3(0.55, 0.74, 0.38)), vec3(4.2, 4.25, 4.4), N, V, albedo, metallic, roughness, F0);
                Lo += evalLight(normalize(vec3(-0.80, 0.36, -0.48)), vec3(2.6, 3.1, 4.0), N, V, albedo, metallic, roughness, F0);
                Lo += evalLight(normalize(vec3(0.05, -0.82, 0.56)), vec3(1.2, 1.35, 1.65), N, V, albedo, metallic, roughness, F0);
                Lo += evalLight(normalize(vec3(0.0, 0.25, -0.96)), vec3(2.2, 2.5, 3.1), N, V, albedo, metallic, roughness, F0);

                vec3 R = reflect(-V, N);
                float NdotV = max(dot(N, V), 0.0);
                vec3 F = fresnelSchlick(NdotV, F0);
                vec3 envSpec = sampleStudioEnv(R, roughness) * F * mix(1.35, 0.55, roughness);
                vec3 kD = (1.0 - F) * (1.0 - metallic);
                vec3 envDiff = mix(vec3(0.14, 0.17, 0.24), vec3(0.38, 0.44, 0.56), N.y * 0.5 + 0.5) * albedo * kD;

                // Subtle silhouette rim lift so black-on-dark contours stay razor sharp
                float rim = pow(1.0 - NdotV, 3.6) * (1.0 - roughness * 0.65);
                vec3 rimGlow = vec3(0.20, 0.27, 0.38) * rim;

                vec3 color = envDiff + envSpec + Lo + rimGlow;

                // Tactile top button subtle cyan feedback pulse on tap/click events
                if (vMaterial.y < 0.28 && vLocalPos.y > 0.52) {
                    color += vec3(0.18, 0.68, 1.0) * uButtonPulse * 0.65;
                }

                // ACES filmic tone mapping + sRGB gamma
                color = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);
                color = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
                fragColor = vec4(color, 1.0);
            }
        """

        private const val LINE_VERTEX_SHADER = """#version 320 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec4 aColor;
            uniform mat4 uMvp;
            out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val LINE_FRAGMENT_SHADER = """#version 320 es
            precision mediump float;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vColor;
            }
        """
    }
}
