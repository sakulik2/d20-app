package xyz.sakulik.d20.app.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceShape
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * OpenGL ES 多面体骰子。
 *
 * 静止和翻滚阶段始终绘制同一个三维网格；每一面都有独立数字纹理，
 * 最终结果通过旋转对应表面朝向镜头呈现。
 */
@Composable
fun PolyhedralDice3D(
    diceType: DiceType = DiceType.D20,
    isRolling: Boolean,
    finalValue: Int? = null,
    size: Dp = 100.dp,
    onLandImpact: (() -> Unit)? = null
) {
    if (diceType == DiceType.D100) {
        PercentileDice3D(
            isRolling = isRolling,
            finalValue = finalValue,
            size = size,
            onLandImpact = onLandImpact
        )
        return
    }

    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    var wasRolling by remember(diceType) { mutableStateOf(false) }

    LaunchedEffect(diceType, isRolling, finalValue) {
        if (isRolling) {
            wasRolling = true
        } else if (wasRolling && finalValue != null) {
            delay(620)
            onLandImpact?.invoke()
            wasRolling = false
        }
    }

    OpenGlDie(
        shape = diceType.shape,
        selectedFaceIndex = ((finalValue ?: diceType.sides) - 1)
            .coerceIn(0, diceType.sides - 1),
        faceLabels = (1..diceType.sides).map(Int::toString),
        isRolling = isRolling,
        primary = primary,
        accent = accent,
        modifier = Modifier.size(size)
    )
}

@Composable
private fun PercentileDice3D(
    isRolling: Boolean,
    finalValue: Int?,
    size: Dp,
    onLandImpact: (() -> Unit)?
) {
    val value = (finalValue ?: 100).coerceIn(1, 100)
    val tens = if (value == 100) 0 else (value / 10) * 10
    val units = if (value == 100) 0 else value % 10
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val gold = Color(0xFFD69E2E)
    val bronze = Color(0xFF7C2D12)
    var wasRolling by remember { mutableStateOf(false) }

    LaunchedEffect(isRolling, finalValue) {
        if (isRolling) {
            wasRolling = true
        } else if (wasRolling && finalValue != null) {
            delay(620)
            onLandImpact?.invoke()
            wasRolling = false
        }
    }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OpenGlDie(
                shape = DiceShape.D10_DELTOID,
                selectedFaceIndex = tens / 10,
                faceLabels = (0..9).map { digit -> (digit * 10).toString().padStart(2, '0') },
                isRolling = isRolling,
                primary = gold,
                accent = bronze,
                modifier = Modifier.size(size * 0.46f)
            )
            OpenGlDie(
                shape = DiceShape.D10_DELTOID,
                selectedFaceIndex = units,
                faceLabels = (0..9).map(Int::toString),
                isRolling = isRolling,
                primary = primary,
                accent = accent,
                modifier = Modifier.size(size * 0.46f)
            )
        }
    }
}

@Composable
private fun OpenGlDie(
    shape: DiceShape,
    selectedFaceIndex: Int,
    faceLabels: List<String>,
    isRolling: Boolean,
    primary: Color,
    accent: Color,
    modifier: Modifier
) {
    val context = LocalContext.current
    val renderer = remember(shape) { DiceRenderer(meshFor(shape)) }
    val surfaceView = remember(shape) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            renderer.setFrameRequester(::requestRender)
            var previousX = 0f
            var previousY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX = event.x
                        previousY = event.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.x - previousX
                        val deltaY = event.y - previousY
                        previousX = event.x
                        previousY = event.y
                        queueEvent { renderer.rotateBy(deltaY * 0.5f, deltaX * 0.5f) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        view.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    DisposableEffect(surfaceView) {
        surfaceView.onResume()
        onDispose {
            surfaceView.onPause()
            surfaceView.visibility = android.view.View.GONE
        }
    }

    LaunchedEffect(isRolling, selectedFaceIndex, faceLabels, primary, accent) {
        surfaceView.queueEvent {
            renderer.updateState(
                rolling = isRolling,
                selectedFaceIndex = selectedFaceIndex,
                faceLabels = faceLabels,
                primary = primary.toRgba(),
                accent = accent.toRgba()
            )
        }
    }

    key(shape) {
        AndroidView(
            factory = { surfaceView },
            modifier = modifier,
            update = { view ->
                view.queueEvent {
                    renderer.updateState(
                        rolling = isRolling,
                        selectedFaceIndex = selectedFaceIndex,
                        faceLabels = faceLabels,
                        primary = primary.toRgba(),
                        accent = accent.toRgba()
                    )
                }
            }
        )
    }
}

private fun Color.toRgba() = floatArrayOf(red, green, blue, alpha)

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)

    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun length() = sqrt(dot(this))

    fun normalized(): Vec3 {
        val length = length()
        return if (length > 0.00001f) this * (1f / length) else Vec3(0f, 0f, 1f)
    }
}

private data class Face(
    val indices: IntArray,
    val normal: Vec3,
    val center: Vec3
)

private data class DiceMesh(
    val vertices: List<Vec3>,
    val faces: List<Face>,
    val trianglePositions: FloatArray,
    val triangleNormals: FloatArray,
    val triangleShades: FloatArray,
    val edgePositions: FloatArray
)

internal data class DiceMeshSummary(
    val vertexCount: Int,
    val edgeCount: Int,
    val faceCount: Int,
    val verticesPerFace: Set<Int>,
    val isPlanar: Boolean,
    val eulerCharacteristic: Int
)

internal fun inspectDiceMesh(shape: DiceShape): DiceMeshSummary {
    val mesh = meshFor(shape)
    val edges = buildSet {
        mesh.faces.forEach { face ->
            face.indices.forEachIndexed { index, start ->
                val end = face.indices[(index + 1) % face.indices.size]
                add(if (start < end) start to end else end to start)
            }
        }
    }
    return DiceMeshSummary(
        vertexCount = mesh.vertices.size,
        edgeCount = edges.size,
        faceCount = mesh.faces.size,
        verticesPerFace = mesh.faces.map { it.indices.size }.toSet(),
        isPlanar = mesh.faces.all { it.isPlanar(mesh.vertices) },
        eulerCharacteristic = mesh.vertices.size - edges.size + mesh.faces.size
    )
}

private fun buildMesh(vertices: List<Vec3>, rawFaces: List<IntArray>): DiceMesh {
    val faces = rawFaces.map { rawIndices ->
        var indices = rawIndices
        var normal = faceNormal(vertices, indices)
        val center = indices.map(vertices::get).reduce(Vec3::plus) * (1f / indices.size)
        if (normal.dot(center) < 0f) {
            indices = indices.reversedArray()
            normal = faceNormal(vertices, indices)
        }
        Face(indices, normal, center)
    }
    require(faces.all { face -> face.isPlanar(vertices) }) {
        "骰体包含不共面的多边形面"
    }

    val positions = mutableListOf<Float>()
    val normals = mutableListOf<Float>()
    val shades = mutableListOf<Float>()
    val edges = mutableListOf<Float>()
    faces.forEachIndexed { faceIndex, face ->
        for (index in 1 until face.indices.lastIndex) {
            intArrayOf(face.indices[0], face.indices[index], face.indices[index + 1]).forEach { vertexIndex ->
                positions.add(vertices[vertexIndex])
                normals.add(face.normal)
                shades += if (faceIndex % 2 == 0) 0.12f else 0.34f
            }
        }
        face.indices.forEachIndexed { index, vertexIndex ->
            val nextIndex = face.indices[(index + 1) % face.indices.size]
            edges.add(vertices[vertexIndex])
            edges.add(vertices[nextIndex])
        }
    }
    return DiceMesh(
        vertices = vertices,
        faces = faces,
        trianglePositions = positions.toFloatArray(),
        triangleNormals = normals.toFloatArray(),
        triangleShades = shades.toFloatArray(),
        edgePositions = edges.toFloatArray()
    )
}

private fun MutableList<Float>.add(vector: Vec3) {
    add(vector.x)
    add(vector.y)
    add(vector.z)
}

private fun faceNormal(vertices: List<Vec3>, indices: IntArray): Vec3 {
    val first = vertices[indices[0]]
    val second = vertices[indices[1]]
    val third = vertices[indices[2]]
    return (second - first).cross(third - first).normalized()
}

private fun Face.isPlanar(vertices: List<Vec3>): Boolean {
    val origin = vertices[indices.first()]
    return indices.drop(1).all { index ->
        abs((vertices[index] - origin).dot(normal)) < 0.001f
    }
}

private fun meshFor(shape: DiceShape): DiceMesh {
    return when (shape) {
        DiceShape.TRIANGLE -> tetrahedronMesh()
        DiceShape.CUBE -> cubeMesh()
        DiceShape.OCTAHEDRON -> octahedronMesh()
        DiceShape.D10_DELTOID,
        DiceShape.D100_PERCENTILE -> d10Mesh()
        DiceShape.D12_DODECAHEDRON -> dodecahedronMesh()
        DiceShape.ICOSAHEDRON -> icosahedronMesh()
    }
}

private fun tetrahedronMesh(): DiceMesh {
    val scale = 1f / sqrt(3f)
    return buildMesh(
        listOf(
            Vec3(scale, scale, scale), Vec3(scale, -scale, -scale),
            Vec3(-scale, scale, -scale), Vec3(-scale, -scale, scale)
        ),
        listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 1, 3),
            intArrayOf(0, 3, 2), intArrayOf(1, 2, 3)
        )
    )
}

private fun cubeMesh(): DiceMesh {
    val scale = 1f / sqrt(3f)
    return buildMesh(
        listOf(
            Vec3(-scale, -scale, -scale), Vec3(scale, -scale, -scale),
            Vec3(scale, scale, -scale), Vec3(-scale, scale, -scale),
            Vec3(-scale, -scale, scale), Vec3(scale, -scale, scale),
            Vec3(scale, scale, scale), Vec3(-scale, scale, scale)
        ),
        listOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(5, 4, 7, 6),
            intArrayOf(4, 0, 3, 7), intArrayOf(1, 5, 6, 2),
            intArrayOf(4, 5, 1, 0), intArrayOf(3, 2, 6, 7)
        )
    )
}

private fun octahedronMesh(): DiceMesh {
    return buildMesh(
        listOf(
            Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f),
            Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f),
            Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f)
        ),
        listOf(
            intArrayOf(4, 0, 2), intArrayOf(4, 2, 1),
            intArrayOf(4, 1, 3), intArrayOf(4, 3, 0),
            intArrayOf(5, 2, 0), intArrayOf(5, 1, 2),
            intArrayOf(5, 3, 1), intArrayOf(5, 0, 3)
        )
    )
}

private fun d10Mesh(): DiceMesh {
    val polarHeight = 1.08f
    val ringHeight = polarHeight * (
        (1f - cos((PI / 5.0)).toFloat()) /
            (1f + cos((PI / 5.0)).toFloat())
        )
    val vertices = mutableListOf(
        Vec3(0f, 0f, polarHeight),
        Vec3(0f, 0f, -polarHeight)
    )
    repeat(5) { index ->
        val topAngle = index * (2.0 * PI / 5.0)
        val bottomAngle = topAngle + PI / 5.0
        vertices += Vec3(
            cos(topAngle).toFloat() * 0.86f,
            sin(topAngle).toFloat() * 0.86f,
            ringHeight
        )
        vertices += Vec3(
            cos(bottomAngle).toFloat() * 0.86f,
            sin(bottomAngle).toFloat() * 0.86f,
            -ringHeight
        )
    }
    return buildMesh(
        vertices,
        listOf(
            intArrayOf(0, 2, 3, 4), intArrayOf(0, 4, 5, 6),
            intArrayOf(0, 6, 7, 8), intArrayOf(0, 8, 9, 10),
            intArrayOf(0, 10, 11, 2), intArrayOf(1, 3, 2, 11),
            intArrayOf(1, 5, 4, 3), intArrayOf(1, 7, 6, 5),
            intArrayOf(1, 9, 8, 7), intArrayOf(1, 11, 10, 9)
        )
    )
}

private fun dodecahedronMesh(): DiceMesh {
    return dualMesh(icosahedronMesh())
}

private fun dualMesh(source: DiceMesh): DiceMesh {
    val vertices = source.faces.map { face -> face.center.normalized() }
    val faces = source.vertices.indices.map { vertexIndex ->
        val axis = source.vertices[vertexIndex].normalized()
        val reference = if (abs(axis.z) < 0.9f) Vec3(0f, 0f, 1f) else Vec3(0f, 1f, 0f)
        val tangent = reference.cross(axis).normalized()
        val bitangent = axis.cross(tangent).normalized()
        source.faces.indices
            .filter { faceIndex -> vertexIndex in source.faces[faceIndex].indices }
            .sortedBy { faceIndex ->
                val center = vertices[faceIndex]
                kotlin.math.atan2(center.dot(bitangent), center.dot(tangent))
            }
            .toIntArray()
    }
    return buildMesh(vertices, faces)
}

private fun icosahedronMesh(): DiceMesh {
    val phi = (1f + sqrt(5f)) / 2f
    val length = sqrt(1f + phi * phi)
    val a = 1f / length
    val b = phi / length
    return buildMesh(
        listOf(
            Vec3(-a, b, 0f), Vec3(a, b, 0f), Vec3(-a, -b, 0f), Vec3(a, -b, 0f),
            Vec3(0f, -a, b), Vec3(0f, a, b), Vec3(0f, -a, -b), Vec3(0f, a, -b),
            Vec3(b, 0f, -a), Vec3(b, 0f, a), Vec3(-b, 0f, -a), Vec3(-b, 0f, a)
        ),
        listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7),
            intArrayOf(0, 7, 10), intArrayOf(0, 10, 11), intArrayOf(1, 5, 9),
            intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6),
            intArrayOf(7, 1, 8), intArrayOf(3, 9, 4), intArrayOf(3, 4, 2),
            intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10),
            intArrayOf(8, 6, 7), intArrayOf(9, 8, 1)
        )
    )
}

private class DiceRenderer(private val mesh: DiceMesh) : GLSurfaceView.Renderer {
    private val trianglePositions = mesh.trianglePositions.asBuffer()
    private val triangleNormals = mesh.triangleNormals.asBuffer()
    private val triangleShades = mesh.triangleShades.asBuffer()
    private val edgePositions = mesh.edgePositions.asBuffer()
    private val labelPositions = mesh.faces.map { face ->
        labelGeometry(face, mesh.vertices).first.asBuffer()
    }
    private val labelTextureCoordinates = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    ).asBuffer()
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val rotation = FloatArray(16)
    private val translatedRotation = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private var meshProgram = 0
    private var lineProgram = 0
    private var textureProgram = 0
    private var labelTextures = IntArray(0)
    private var labels = emptyList<String>()
    private var selectedFace = 0
    private var rolling = false
    private var settling = false
    private var settleElapsed = 0f
    private var lastFrameNanos = 0L
    private var orientation = Quaternion.identity()
    private var settleStart = Quaternion.identity()
    private var settleTarget = Quaternion.identity()
    private var angularVelocity = Vec3(310f, 430f, 190f)
    private var primary = floatArrayOf(0.25f, 0.45f, 0.95f, 1f)
    private var accent = floatArrayOf(0.6f, 0.25f, 0.95f, 1f)
    private var requestFrame: (() -> Unit)? = null

    fun setFrameRequester(requester: () -> Unit) {
        requestFrame = requester
    }

    fun rotateBy(xDegrees: Float, yDegrees: Float) {
        if (rolling) return
        settling = false
        orientation = (
            Quaternion.fromEulerDelta(Vec3(xDegrees, yDegrees, 0f)) * orientation
        ).normalized()
        requestFrame?.invoke()
    }

    fun updateState(
        rolling: Boolean,
        selectedFaceIndex: Int,
        faceLabels: List<String>,
        primary: FloatArray,
        accent: FloatArray
    ) {
        this.primary = primary
        this.accent = accent
        val nextFace = selectedFaceIndex.coerceIn(0, mesh.faces.lastIndex)
        val resultChanged = nextFace != selectedFace || faceLabels != labels
        selectedFace = nextFace
        if (faceLabels != labels) {
            labels = faceLabels.take(mesh.faces.size)
            replaceLabelTextures()
        }

        if (rolling && !this.rolling) {
            angularVelocity = Vec3(
                Random.nextFloat() * 180f + 330f,
                Random.nextFloat() * 220f + 410f,
                Random.nextFloat() * 150f + 170f
            )
            settling = false
        } else if (!rolling && (this.rolling || resultChanged)) {
            settleStart = orientation
            settleTarget = settledOrientation(mesh.faces[selectedFace], mesh.vertices)
            settleElapsed = 0f
            settling = true
        }
        this.rolling = rolling
        requestFrame?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(1f, 1f)
        meshProgram = createProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        textureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        replaceLabelTextures()
        Matrix.setLookAtM(view, 0, 0f, 0.12f, 4.1f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / max(height, 1).toFloat()
        Matrix.perspectiveM(projection, 0, 34f, aspect, 0.1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == 0L) 0f else min((now - lastFrameNanos) / 1_000_000_000f, 0.05f)
        lastFrameNanos = now
        updateAnimation(delta)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val height = when {
            rolling -> 0.18f + abs(sin(now / 100_000_000.0)).toFloat() * 0.18f
            settling -> (1f - (settleElapsed / SETTLE_DURATION).coerceIn(0f, 1f)) * 0.12f
            else -> 0f
        }
        orientation.toMatrix(rotation)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, height, 0f)
        Matrix.multiplyMM(translatedRotation, 0, model, 0, rotation, 0)
        System.arraycopy(translatedRotation, 0, model, 0, model.size)
        Matrix.scaleM(model, 0, 0.96f, 0.96f, 0.96f)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        drawMesh()
        drawEdges()
        drawLabels()
        if (rolling || settling) requestFrame?.invoke()
    }

    private fun updateAnimation(delta: Float) {
        if (rolling) {
            orientation = Quaternion.fromEulerDelta(angularVelocity * delta) * orientation
            orientation = orientation.normalized()
        } else if (settling) {
            settleElapsed += delta
            val progress = (settleElapsed / SETTLE_DURATION).coerceIn(0f, 1f)
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            orientation = Quaternion.slerp(settleStart, settleTarget, eased)
            if (progress >= 1f) {
                orientation = settleTarget
                settling = false
            }
        }
    }

    private fun drawMesh() {
        GLES20.glUseProgram(meshProgram)
        val position = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(meshProgram, "aNormal")
        val shade = GLES20.glGetAttribLocation(meshProgram, "aShade")
        trianglePositions.bind(position, 3)
        triangleNormals.bind(normal, 3)
        triangleShades.bind(shade, 1)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgram, "uModel"), 1, false, model, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(meshProgram, "uPrimary"), 1, primary, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(meshProgram, "uAccent"), 1, accent, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.trianglePositions.size / 3)
        position.disable()
        normal.disable()
        shade.disable()
    }

    private fun drawEdges() {
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        edgePositions.bind(position, 3)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(lineProgram, "uColor"), 0.04f, 0.05f, 0.08f, 0.68f)
        GLES20.glLineWidth(2.2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, mesh.edgePositions.size / 3)
        position.disable()
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    private fun drawLabels() {
        if (labelTextures.size != mesh.faces.size) return
        mesh.faces.indices.forEach(::drawLabel)
    }

    private fun drawLabel(faceIndex: Int) {
        GLES20.glUseProgram(textureProgram)
        val position = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(textureProgram, "aTexCoord")
        labelPositions[faceIndex].bind(position, 3)
        labelTextureCoordinates.bind(textureCoordinate, 2)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(textureProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTextures[faceIndex])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgram, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        position.disable()
        textureCoordinate.disable()
    }

    private fun replaceLabelTextures() {
        if (meshProgram == 0) return
        if (labelTextures.isNotEmpty()) {
            GLES20.glDeleteTextures(labelTextures.size, labelTextures, 0)
        }
        labelTextures = IntArray(mesh.faces.size)
        GLES20.glGenTextures(labelTextures.size, labelTextures, 0)
        labelTextures.forEachIndexed { faceIndex, textureId ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val bitmap = createLabelBitmap(labels.getOrNull(faceIndex).orEmpty())
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }
    }

    companion object {
        private const val SETTLE_DURATION = 0.58f
    }
}

private fun labelGeometry(face: Face, vertices: List<Vec3>): Pair<FloatArray, FloatArray> {
    val tangent = (vertices[face.indices[0]] - face.center).normalized()
    val bitangent = face.normal.cross(tangent).normalized()
    val inset = face.indices.indices.minOf { index ->
        val start = vertices[face.indices[index]]
        val end = vertices[face.indices[(index + 1) % face.indices.size]]
        distanceToLine(face.center, start, end)
    }
    val halfSize = inset * if (face.indices.size == 3) 0.43f else 0.56f
    val center = face.center + face.normal * 0.025f
    val bottomLeft = center - tangent * halfSize - bitangent * halfSize
    val bottomRight = center + tangent * halfSize - bitangent * halfSize
    val topLeft = center - tangent * halfSize + bitangent * halfSize
    val topRight = center + tangent * halfSize + bitangent * halfSize
    return floatArrayOf(
        bottomLeft.x, bottomLeft.y, bottomLeft.z,
        bottomRight.x, bottomRight.y, bottomRight.z,
        topLeft.x, topLeft.y, topLeft.z,
        topRight.x, topRight.y, topRight.z
    ) to floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )
}

private fun distanceToLine(point: Vec3, start: Vec3, end: Vec3): Float {
    val edge = end - start
    val projection = ((point - start).dot(edge) / max(edge.dot(edge), 0.00001f)).coerceIn(0f, 1f)
    return (point - (start + edge * projection)).length()
}

private fun settledOrientation(face: Face, vertices: List<Vec3>): Quaternion {
    val screenX = (vertices[face.indices[0]] - face.center).normalized()
    val screenY = face.normal.cross(screenX).normalized()
    val matrix = floatArrayOf(
        screenX.x, screenY.x, face.normal.x, 0f,
        screenX.y, screenY.y, face.normal.y, 0f,
        screenX.z, screenY.z, face.normal.z, 0f,
        0f, 0f, 0f, 1f
    )
    return Quaternion.fromMatrix(matrix).normalized()
}

private data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(other: Quaternion) = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w
    )

    fun normalized(): Quaternion {
        val length = sqrt(w * w + x * x + y * y + z * z)
        return if (length > 0.00001f) {
            Quaternion(w / length, x / length, y / length, z / length)
        } else {
            identity()
        }
    }

    fun toMatrix(output: FloatArray) {
        val quaternion = normalized()
        val xx = quaternion.x * quaternion.x
        val yy = quaternion.y * quaternion.y
        val zz = quaternion.z * quaternion.z
        val xy = quaternion.x * quaternion.y
        val xz = quaternion.x * quaternion.z
        val yz = quaternion.y * quaternion.z
        val wx = quaternion.w * quaternion.x
        val wy = quaternion.w * quaternion.y
        val wz = quaternion.w * quaternion.z
        output[0] = 1f - 2f * (yy + zz)
        output[1] = 2f * (xy + wz)
        output[2] = 2f * (xz - wy)
        output[3] = 0f
        output[4] = 2f * (xy - wz)
        output[5] = 1f - 2f * (xx + zz)
        output[6] = 2f * (yz + wx)
        output[7] = 0f
        output[8] = 2f * (xz + wy)
        output[9] = 2f * (yz - wx)
        output[10] = 1f - 2f * (xx + yy)
        output[11] = 0f
        output[12] = 0f
        output[13] = 0f
        output[14] = 0f
        output[15] = 1f
    }

    companion object {
        fun identity() = Quaternion(1f, 0f, 0f, 0f)

        fun fromEulerDelta(degrees: Vec3): Quaternion {
            val xRotation = fromAxisAngle(Vec3(1f, 0f, 0f), degrees.x)
            val yRotation = fromAxisAngle(Vec3(0f, 1f, 0f), degrees.y)
            val zRotation = fromAxisAngle(Vec3(0f, 0f, 1f), degrees.z)
            return zRotation * yRotation * xRotation
        }

        fun fromAxisAngle(axis: Vec3, degrees: Float): Quaternion {
            val radians = degrees * PI.toFloat() / 180f
            val half = radians / 2f
            val sine = sin(half)
            val normalizedAxis = axis.normalized()
            return Quaternion(
                cos(half),
                normalizedAxis.x * sine,
                normalizedAxis.y * sine,
                normalizedAxis.z * sine
            )
        }

        fun fromMatrix(matrix: FloatArray): Quaternion {
            val trace = matrix[0] + matrix[5] + matrix[10]
            return when {
                trace > 0f -> {
                    val scale = sqrt(trace + 1f) * 2f
                    Quaternion(
                        0.25f * scale,
                        (matrix[6] - matrix[9]) / scale,
                        (matrix[8] - matrix[2]) / scale,
                        (matrix[1] - matrix[4]) / scale
                    )
                }
                matrix[0] > matrix[5] && matrix[0] > matrix[10] -> {
                    val scale = sqrt(1f + matrix[0] - matrix[5] - matrix[10]) * 2f
                    Quaternion(
                        (matrix[6] - matrix[9]) / scale,
                        0.25f * scale,
                        (matrix[4] + matrix[1]) / scale,
                        (matrix[8] + matrix[2]) / scale
                    )
                }
                matrix[5] > matrix[10] -> {
                    val scale = sqrt(1f + matrix[5] - matrix[0] - matrix[10]) * 2f
                    Quaternion(
                        (matrix[8] - matrix[2]) / scale,
                        (matrix[4] + matrix[1]) / scale,
                        0.25f * scale,
                        (matrix[9] + matrix[6]) / scale
                    )
                }
                else -> {
                    val scale = sqrt(1f + matrix[10] - matrix[0] - matrix[5]) * 2f
                    Quaternion(
                        (matrix[1] - matrix[4]) / scale,
                        (matrix[8] + matrix[2]) / scale,
                        (matrix[9] + matrix[6]) / scale,
                        0.25f * scale
                    )
                }
            }
        }

        fun slerp(start: Quaternion, end: Quaternion, fraction: Float): Quaternion {
            var target = end
            var cosine = start.w * end.w + start.x * end.x + start.y * end.y + start.z * end.z
            if (cosine < 0f) {
                cosine = -cosine
                target = Quaternion(-end.w, -end.x, -end.y, -end.z)
            }
            if (cosine > 0.9995f) {
                return Quaternion(
                    start.w + fraction * (target.w - start.w),
                    start.x + fraction * (target.x - start.x),
                    start.y + fraction * (target.y - start.y),
                    start.z + fraction * (target.z - start.z)
                ).normalized()
            }
            val angle = acos(cosine.coerceIn(-1f, 1f))
            val sine = sin(angle)
            val startWeight = sin((1f - fraction) * angle) / sine
            val endWeight = sin(fraction * angle) / sine
            return Quaternion(
                start.w * startWeight + target.w * endWeight,
                start.x * startWeight + target.x * endWeight,
                start.y * startWeight + target.y * endWeight,
                start.z * startWeight + target.z * endWeight
            )
        }
    }
}

private fun FloatArray.asBuffer(): FloatBuffer {
    return ByteBuffer.allocateDirect(size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(this@asBuffer)
            position(0)
        }
}

private fun FloatBuffer.bind(attribute: Int, componentCount: Int) {
    position(0)
    GLES20.glEnableVertexAttribArray(attribute)
    GLES20.glVertexAttribPointer(attribute, componentCount, GLES20.GL_FLOAT, false, 0, this)
}

private fun Int.disable() {
    GLES20.glDisableVertexAttribArray(this)
}

private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    return shader
}

private fun createLabelBitmap(label: String): Bitmap {
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val textSize = when {
        label.length >= 3 -> 104f
        label.length == 2 -> 132f
        else -> 154f
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        this.textSize = textSize
    }
    val baseline = 128f - (paint.ascent() + paint.descent()) / 2f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 16f
    paint.color = android.graphics.Color.argb(225, 8, 10, 18)
    canvas.drawText(label, 128f, baseline, paint)
    paint.style = Paint.Style.FILL
    paint.color = when (label) {
        "1" -> android.graphics.Color.rgb(255, 105, 105)
        "20", "100", "00" -> android.graphics.Color.rgb(255, 220, 100)
        else -> android.graphics.Color.WHITE
    }
    canvas.drawText(label, 128f, baseline, paint)
    return bitmap
}

private const val MESH_VERTEX_SHADER = """
    uniform mat4 uMvp;
    uniform mat4 uModel;
    attribute vec3 aPosition;
    attribute vec3 aNormal;
    attribute float aShade;
    varying vec3 vNormal;
    varying vec3 vWorldPosition;
    varying float vShade;
    void main() {
        vec4 worldPosition = uModel * vec4(aPosition, 1.0);
        vWorldPosition = worldPosition.xyz;
        vNormal = normalize(mat3(uModel) * aNormal);
        vShade = aShade;
        gl_Position = uMvp * vec4(aPosition, 1.0);
    }
"""

private const val MESH_FRAGMENT_SHADER = """
    precision mediump float;
    uniform vec4 uPrimary;
    uniform vec4 uAccent;
    varying vec3 vNormal;
    varying vec3 vWorldPosition;
    varying float vShade;
    void main() {
        vec3 normal = normalize(vNormal);
        vec3 lightDirection = normalize(vec3(-0.45, 0.72, 1.0));
        vec3 viewDirection = normalize(vec3(0.0, 0.0, 4.1) - vWorldPosition);
        vec3 halfVector = normalize(lightDirection + viewDirection);
        float diffuse = max(dot(normal, lightDirection), 0.0);
        float specular = pow(max(dot(normal, halfVector), 0.0), 42.0);
        float rim = pow(1.0 - max(dot(normal, viewDirection), 0.0), 2.2);
        vec3 material = mix(uPrimary.rgb, uAccent.rgb, vShade);
        vec3 color = material * (0.30 + diffuse * 0.74);
        color += vec3(1.0, 0.94, 0.82) * specular * 0.55;
        color += uAccent.rgb * rim * 0.24;
        gl_FragColor = vec4(color, uPrimary.a);
    }
"""

private const val LINE_VERTEX_SHADER = """
    uniform mat4 uMvp;
    attribute vec3 aPosition;
    void main() {
        gl_Position = uMvp * vec4(aPosition * 1.003, 1.0);
    }
"""

private const val LINE_FRAGMENT_SHADER = """
    precision mediump float;
    uniform vec4 uColor;
    void main() {
        gl_FragColor = uColor;
    }
"""

private const val TEXTURE_VERTEX_SHADER = """
    uniform mat4 uMvp;
    attribute vec3 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        vTexCoord = aTexCoord;
        gl_Position = uMvp * vec4(aPosition, 1.0);
    }
"""

private const val TEXTURE_FRAGMENT_SHADER = """
    precision mediump float;
    uniform sampler2D uTexture;
    varying vec2 vTexCoord;
    void main() {
        vec4 color = texture2D(uTexture, vTexCoord);
        if (color.a < 0.05) discard;
        gl_FragColor = color;
    }
"""
