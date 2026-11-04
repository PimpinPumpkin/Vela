package app.vela.core.data

import app.vela.core.data.google.parse.TransitParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitPreviewLinesTest {
    private fun node(vararg entries: Pair<Int, JsonElement>): JsonArray {
        val values = MutableList<JsonElement>((entries.maxOfOrNull { it.first } ?: -1) + 1) { JsonNull }
        entries.forEach { (index, value) -> values[index] = value }
        return JsonArray(values)
    }

    private fun badges(names: List<String>) = JsonArray(names.map { name ->
        JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(1), JsonPrimitive("#1d59b3"), JsonPrimitive("#ffffff")))
    })

    // Synthetic Davis-to-Sacramento journey using the parser's existing positional shapes.
    private fun trip(summary: List<String>, rides: List<String>) = TransitParser.parse(
        node(0 to node(1 to JsonArray(listOf(node(
            0 to node(3 to node(1 to JsonPrimitive("60 min")), 14 to badges(summary)),
            1 to node(0 to node(1 to JsonArray(rides.flatMap { name -> listOf(
                node(0 to node(3 to node(1 to JsonPrimitive("2 min")))),
                node(0 to node(14 to badges(listOf(name))), 5 to JsonArray(listOf(
                    node(0 to JsonPrimitive("Davis")), node(0 to JsonPrimitive("Sacramento")),
                ))),
            ) }))),
        )))))
    ).single()

    @Test fun `preview includes the missing middle ride in journey order`() {
        val rides = listOf("38", "448", "74")
        val itinerary = trip(listOf("38", "74"), rides)
        assertEquals(rides, itinerary.steps.mapNotNull { it.line?.name })
        assertEquals(rides, itinerary.lines.map { it.name })
    }

    @Test fun `preview repeats a line ridden again after transfers`() {
        val rides = listOf("38", "448", "38")
        val itinerary = trip(listOf("38", "448"), rides)
        assertEquals(rides, itinerary.steps.mapNotNull { it.line?.name })
        assertEquals(rides, itinerary.lines.map { it.name })
    }
}
