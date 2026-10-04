package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyMapKmlTest {
    private val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>Davis picnic plan</name>
    <description><![CDATA[Where to meet &amp; park]]></description>
    <Style id="icon-1899-0288D1"><IconStyle><color>ffd18802</color><scale>1</scale></IconStyle></Style>
    <Style id="line-FF0000-5000-normal"><LineStyle><color>ff0000ff</color><width>5</width></LineStyle></Style>
    <Style id="line-FF0000-5000-highlight"><LineStyle><color>ff0000ff</color><width>7.5</width></LineStyle></Style>
    <StyleMap id="line-FF0000-5000">
      <Pair><key>normal</key><styleUrl>#line-FF0000-5000-normal</styleUrl></Pair>
      <Pair><key>highlight</key><styleUrl>#line-FF0000-5000-highlight</styleUrl></Pair>
    </StyleMap>
    <Style id="poly-0F9D58-1200-77"><LineStyle><color>ff589d0f</color><width>1.2</width></LineStyle><PolyStyle><color>4d589d0f</color></PolyStyle></Style>
    <Folder>
      <name>Meeting spots</name>
      <Placemark>
        <name>Farmers Market</name>
        <description><![CDATA[Saturday mornings<br>Bring cash<img src="https://example.com/x.jpg" height="200" width="auto" />]]></description>
        <styleUrl>#icon-1899-0288D1</styleUrl>
        <Point><coordinates>
            -121.7445,38.5435,0
        </coordinates></Point>
      </Placemark>
    </Folder>
    <Folder>
      <name>Routes &amp; areas</name>
      <Placemark>
        <name>Walk from the station</name>
        <styleUrl>#line-FF0000-5000</styleUrl>
        <LineString><tessellate>1</tessellate><coordinates>
            -121.7376,38.5436,0
            -121.7400,38.5440,0
            -121.7445,38.5435,0
        </coordinates></LineString>
      </Placemark>
      <Placemark>
        <name>Picnic lawn</name>
        <styleUrl>#poly-0F9D58-1200-77</styleUrl>
        <Polygon><outerBoundaryIs><LinearRing><tessellate>1</tessellate><coordinates>
            -121.7450,38.5440,0 -121.7440,38.5440,0 -121.7440,38.5430,0 -121.7450,38.5430,0 -121.7450,38.5440,0
        </coordinates></LinearRing></outerBoundaryIs></Polygon>
      </Placemark>
    </Folder>
  </Document>
</kml>"""

    @Test fun `a custom map becomes pins, lines and areas with their layers and colors`() {
        val m = MyMapKml.parse(kml, "abc")!!
        assertEquals("Davis picnic plan", m.title)
        assertEquals("Where to meet & park", m.description)
        assertEquals(1, m.places.size)
        val pin = m.places[0]
        assertEquals("Farmers Market", pin.name)
        assertEquals("Meeting spots", pin.category)
        assertEquals("Saturday mornings\nBring cash", pin.savedNote)
        assertEquals(0xFF0288D1, pin.pinColor) // IconStyle ffd18802 is aabbggrr
        assertEquals("Meeting spots", pin.mapLayer)
        assertEquals(listOf("https://example.com/x.jpg"), pin.photoUrls)
        // KML is lng,lat: the pin must land in Davis, not off Antarctica.
        assertEquals(38.5435, pin.location.lat, 1e-6); assertEquals(-121.7445, pin.location.lng, 1e-6)

        assertEquals(2, m.shapes.size)
        val line = m.shapes[0]
        assertEquals("Walk from the station", line.name); assertEquals(false, line.closed)
        assertEquals(6, line.pts.size); assertEquals(38.5436, line.pts[0], 1e-6)
        assertEquals(0xFFFF0000, line.color) // aabbggrr ff0000ff = opaque red
        assertEquals(5f, line.width); assertNull(line.fill)
        assertEquals("Routes & areas", line.layer)
        val area = m.shapes[1]
        assertTrue(area.closed); assertEquals(0xFF0F9D58, area.color); assertEquals(0x4D0F9D58L, area.fill)
    }

    @Test fun `marker icons are named by the viewer page and drawn by the icon server`() {
        val kml = """<kml><Document><name>Lunch</name>
            <Style id="icon-1577-FFD600-normal"><IconStyle><color>ff00d6ff</color><Icon><href>https://www.gstatic.com/mapspro/images/stock/503-wht-blank_maps.png</href></Icon></IconStyle></Style>
            <StyleMap id="icon-1577-FFD600"><Pair><key>normal</key><styleUrl>#icon-1577-FFD600-normal</styleUrl></Pair></StyleMap>
            <Folder><name>Food</name>
            <Placemark><name>Burgers</name><styleUrl>#icon-1577-FFD600</styleUrl><Point><coordinates>-121.7400,38.5440,0</coordinates></Point></Placemark>
            <Placemark><name>Plain</name><styleUrl>#icon-1899-DB4436-nodesc</styleUrl><Point><coordinates>-121.7410,38.5450,0</coordinates></Point></Placemark>
            <Placemark><name>Unnamed icon</name><styleUrl>#icon-1602-FF5252</styleUrl><Point><coordinates>-121.7420,38.5460,0</coordinates></Point></Placemark>
            </Folder></Document></kml>"""
        // The page escapes its URLs; only the icon's file name is read.
        val viewer = """[\"https://mt.googleapis.com/vt/icon/name\\u003dicons/onion/SHARED-mymaps-container-bg_4x.png,icons/onion/SHARED-mymaps-container_4x.png,icons/onion/1577-food-fork-knife_4x.png\\u0026highlight\\u003dff000000,FFD600\\u0026scale\\u003d2.0\"] icons/onion/1899-blank-shape_pin_4x.png"""
        val m = MyMapKml.parse(kml, "x", viewer)!!
        assertEquals(
            "https://mt.googleapis.com/vt/icon/name=icons/onion/SHARED-mymaps-container-bg_4x.png,icons/onion/SHARED-mymaps-container_4x.png,icons/onion/1577-food-fork-knife_4x.png&highlight=ff000000,FFD600&scale=4.0",
            m.places[0].pinIconUrl,
        )
        assertEquals(0xFFFFD600, m.places[0].pinColor)
        assertNull("the plain pin keeps Vela's pin", m.places[1].pinIconUrl)
        assertNull("an icon the page does not name keeps its color only", m.places[2].pinIconUrl)
        assertNull(MyMapKml.parse(kml, "x")!!.places[0].pinIconUrl) // no viewer page: colors only
    }

    @Test fun `a directions layer becomes a line with its stops`() {
        fun pt(n: String, lng: Double, lat: Double) = "<Placemark><name>$n</name><Point><coordinates>$lng,$lat,0</coordinates></Point></Placemark>"
        val kml = """<kml><Document><name>Day out</name>
            <Folder><name>Sights</name>${pt("Arboretum", -121.7500, 38.5320)}</Folder>
            <Folder><name>Directions from Station to Market</name>
            <Placemark><name>Directions from Station to Market</name><LineString><coordinates>-121.7377,38.5436,0 -121.7400,38.5440,0 -121.7445,38.5435,0</coordinates></LineString></Placemark>
            ${pt("Station", -121.7377, 38.5436)}${pt("Cafe", -121.7400, 38.5441)}${pt("Market", -121.7445, 38.5435)}
            </Folder>
            <Folder><name>A line beside a pin</name>
            <Placemark><name>Path</name><LineString><coordinates>-121.7600,38.5500,0 -121.7700,38.5500,0</coordinates></LineString></Placemark>
            ${pt("Far pin", -121.7000, 38.5000)}${pt("Other", -121.7001, 38.5001)}
            </Folder></Document></kml>"""
        val m = MyMapKml.parse(kml, "x")!!
        assertEquals(listOf("Arboretum", "Far pin", "Other"), m.places.map { it.name }) // the trip's points left the pin list
        assertEquals(listOf("Station", "Cafe", "Market"), m.shapes[0].stops.map { it.name })
        assertEquals(38.5441, m.shapes[0].stops[1].lat, 1e-6)
        assertTrue("a line whose ends are not its layer's points is only a line", m.shapes[1].stops.isEmpty())
    }

    @Test fun `text that is not a custom map reads as nothing`() {
        assertNull(MyMapKml.parse("<html><body>Sign in</body></html>"))
        assertNull(MyMapKml.parse("<kml><Document><name>Empty</name></Document></kml>"))
    }

    @Test fun `custom map links are recognized in every form`() {
        val id = "1A3dRqP4zu7PgCrHIF29qj6AuTpkq493p"
        for (u in listOf(
            "https://www.google.com/maps/d/viewer?mid=$id&ll=38.5,-121.7&z=12",
            "https://www.google.com/maps/d/u/0/edit?mid=$id&usp=sharing",
            "https://google.com/maps/d/embed?mid=$id",
            "https://www.google.com/maps/d/u/1/viewer?hl=en&mid=$id",
        )) {
            assertEquals(u, id, MapLinkParser.myMapId(u))
            assertTrue(MapLinkParser.isShareLink(u))
        }
        assertNull(MapLinkParser.myMapId("https://www.google.com/maps/place/Davis/@38.5,-121.7,12z"))
    }

    @Test fun `a shape measures its length and its area`() {
        // A 0.001 degree square at Davis: about 111 m tall and 87 m wide.
        val sq = listOf(38.5430, -121.7450, 38.5430, -121.7440, 38.5440, -121.7440, 38.5440, -121.7450)
        assertEquals(111.3 * 2 + 87.1 * 2, app.vela.core.util.ShapeMeasure.lengthM(sq, closed = true), 2.0)
        assertEquals(111.3 * 87.1, app.vela.core.util.ShapeMeasure.areaM2(sq), 60.0)
        assertEquals(87.1, app.vela.core.util.ShapeMeasure.lengthM(sq.take(4)), 1.0)
    }
}
