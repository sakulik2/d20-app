package xyz.sakulik.d20.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingNarrativeExtractorTest {
    @Test
    fun extractsIncompleteNarrativeForLivePreview() {
        val partial = """{"narrative":"雨声逼近，门外传来"""

        assertEquals("雨声逼近，门外传来", StreamingNarrativeExtractor.extract(partial))
    }

    @Test
    fun decodesEscapesAndStopsAtNarrativeEnd() {
        val complete = """{"narrative":"第一行\n他说：\"快走\"","game_events":[]}"""

        assertEquals("第一行\n他说：\"快走\"", StreamingNarrativeExtractor.extract(complete))
    }

    @Test
    fun ignoresEventOnlyObject() {
        val event = """{"type":"add_item","name":"信件"}"""

        assertEquals("", StreamingNarrativeExtractor.extract(event))
    }
}
