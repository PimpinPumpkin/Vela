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
}
