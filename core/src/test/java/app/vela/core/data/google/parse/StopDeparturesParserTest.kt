package app.vela.core.data.google.parse

import app.vela.core.model.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture mirrors the live keyless capture (2026-07-12, NYC subway hub): the transit board
 * lives at `place[62]` inside the anonymous place-details payload, shaped
 * `["<station>", [ [null,"<group>", [ [null,[ <directions> ]] ], … "<mode>"] ]]`, each
 * direction `["<headsign>", null,null, [ <time tuples> + headway ], … "<combined label>"]`,
 * and a departure tuple `[rtEpoch,"<tz>","4:35 AM",offset,schedEpoch]`.
 */
class StopDeparturesParserTest {

    private val transit = """
        ["Times Sq-42 St",
         [
          [null,"Subway services",
           [
            [null,
             [
              ["34 St-Hudson Yards",null,null,
               [
                [[1783845332,"America/New_York","4:35 AM",-14400,1783845300],null,null,null,0,null,null,"tok",null,null,[null,null,[4,2],[[["accessibility"],1],[["crowdedness"],4,null,[[[["least"]]]]]]],null,null,null,2],
                [[1783846320,"America/New_York","4:52 AM",-14400,1783846320]],
                [[1783847520,"America/New_York","5:12 AM",-14400,1783847520]],
                [1200,"20 min"]
               ],
               null,null,null,null,null,"34 St Hudson Yards 7"
              ],
              ["Flushing-Main St",null,null,
               [
                [[1783844340,"America/New_York","4:19 AM",-14400,1783844340]],
                [[1783845540,"America/New_York","4:39 AM",-14400,1783845540]],
                [900,"15 min"]
               ],
               null,null,null,null,null,"Flushing-Main St 7"
              ]
             ]
            ]
           ],
           null,null,null,null,null,null,null,null,null,"2"
          ]
         ]
        ]
    """.trimIndent()

    /** Wrap the transit node into a place at root[6], transit at place[62], via null padding. */
    private fun body(transitNode: String): String {
        val place = "[" + "null,".repeat(62) + transitNode + "]"
        val root = "[null,null,null,null,null,null,$place]"
        return ")]}'\n$root"
    }

    @Test
    fun `parses a station departure board`() {
        val d = StopDeparturesParser.parse(body(transit))!!
        assertEquals("Times Sq-42 St", d.stationName)
        assertEquals(2, d.lines.size)

        val a = d.lines[0]
        assertEquals("34 St-Hudson Yards", a.headsign)
        assertEquals(TransitMode.SUBWAY, a.mode)
        assertEquals("7", a.label)
        assertEquals("20 min", a.headwayText)
        assertEquals(listOf("4:35 AM", "4:52 AM", "5:12 AM"), a.upcoming.map { it.clockText })
        assertEquals(1783845300L, a.upcoming[0].epochSec)   // scheduled epoch [4], not realtime [0]
        assertTrue("live time differs from timetable", a.upcoming[0].realtime)
        assertTrue("on-time departure not flagged live", !a.upcoming[1].realtime)

        val b = d.lines[1]
        assertEquals("Flushing-Main St", b.headsign)
        assertEquals("15 min", b.headwayText)
        assertEquals(listOf("4:19 AM", "4:39 AM"), b.upcoming.map { it.clockText })
        assertTrue(b.upcoming.none { it.realtime })          // equal epochs -> scheduled
    }

    @Test
    fun `a non-transit place yields null, not an error`() {
        // place[62] absent + no time tuples anywhere -> routine null (most places aren't stops)
        val root = "[null,null,null,null,null,null,[\"A cafe\",1,2,3]]"
        assertNull(StopDeparturesParser.parse(")]}'\n$root"))
    }

    @Test
    fun `finds the transit node even if its field index shifts`() {
        // put the transit node at a different place index; the shape-search fallback must find it
        val place = "[" + "null,".repeat(40) + transit + "]"
        val root = "[null,null,null,null,null,null,$place]"
        val d = StopDeparturesParser.parse(")]}'\n$root")!!
        assertEquals(2, d.lines.size)
    }
}
