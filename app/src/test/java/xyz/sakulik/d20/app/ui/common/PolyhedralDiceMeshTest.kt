package xyz.sakulik.d20.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceShape

class PolyhedralDiceMeshTest {

    @Test
    fun standardDiceMeshesHaveExpectedTopology() {
        val expected = mapOf(
            DiceShape.TRIANGLE to Triple(4, 4, setOf(3)),
            DiceShape.CUBE to Triple(8, 6, setOf(4)),
            DiceShape.OCTAHEDRON to Triple(6, 8, setOf(3)),
            DiceShape.D10_DELTOID to Triple(12, 10, setOf(4)),
            DiceShape.D12_DODECAHEDRON to Triple(20, 12, setOf(5)),
            DiceShape.ICOSAHEDRON to Triple(12, 20, setOf(3))
        )

        expected.forEach { (shape, topology) ->
            val summary = inspectDiceMesh(shape)
            assertEquals(shape.name, topology.first, summary.vertexCount)
            assertEquals(shape.name, topology.second, summary.faceCount)
            assertEquals(shape.name, topology.third, summary.verticesPerFace)
            assertEquals(shape.name, 2, summary.eulerCharacteristic)
            assertTrue(shape.name, summary.isPlanar)
        }
    }
}
