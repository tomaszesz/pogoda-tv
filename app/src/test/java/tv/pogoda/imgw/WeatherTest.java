package tv.pogoda.imgw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/** The parser against answers saved from the real services on 2026-10-26 at 16:45 in Kraków. */
public class WeatherTest {

    private static final long NOW = LocalDateTime.parse("2026-10-26T16:50").atZone(Weather.ZONE).toInstant().toEpochMilli();

    private static String res(String name) throws IOException {
        try (InputStream in = WeatherTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void imgwMeasurementWinsWhileFresh() throws Exception {
        Weather.Forecast f = Weather.parse(res("openmeteo.json"), res("synop.json"), res("warnings-none.json"),
                "1261", NOW, NOW);
        assertTrue(f.measured);
        assertEquals(10, f.temp);            // 9.8 at Kraków-Balice
        assertEquals("16:00", f.measuredAt); // 15 UTC is 16:00 in Poland in winter time
        assertEquals(73, f.humidity);
        assertEquals(1020, f.pressure);
        assertEquals("płd.", f.windFrom);    // 200 degrees
        assertTrue(f.warnings.isEmpty());
    }

    @Test
    public void staleMeasurementFallsBackToTheModel() throws Exception {
        long later = NOW + 4 * 60 * 60_000L;
        Weather.Forecast f = Weather.parse(res("openmeteo.json"), res("synop.json"), null, "1261", NOW, later);
        assertFalse(f.measured);
        assertEquals(10, f.temp);            // Open-Meteo's 10.0
    }

    @Test
    public void nextTwelveHoursAndFiveDays() throws Exception {
        Weather.Forecast f = Weather.parse(res("openmeteo.json"), null, null, "1261", NOW, NOW);
        assertEquals(12, f.hours.size());
        assertEquals("17:00", f.hours.get(0).label);
        assertEquals("04:00", f.hours.get(11).label);
        assertEquals(5, f.days.size());
        assertEquals("Jutro", f.days.get(0).name);
        assertEquals("Środa", f.days.get(1).name);
        assertEquals(11, f.todayMax);
        assertEquals(5, f.todayMin);
        assertEquals("06:19", f.sunrise);
        assertEquals("16:27", f.sunset);
    }

    @Test
    public void dataSavedBeforeSunTimesStillParses() throws Exception {
        String old = res("openmeteo.json").replaceAll(",\"sunrise\":\\[[^]]*]", "").replaceAll(",\"sunset\":\\[[^]]*]", "");
        Weather.Forecast f = Weather.parse(old, null, null, "1261", NOW, NOW);
        assertEquals("", f.sunrise);
        assertEquals(5, f.days.size());
    }

    @Test
    public void warningsForThePowiatOnlyAndNotExpired() {
        String json = "[{\"nazwa_zdarzenia\":\"Silny wiatr\",\"stopien\":\"1\",\"obowiazuje_do\":\"2026-10-27 08:00:00\","
                + "\"teryt\":[\"1206\",\"1261\"]},"
                + "{\"nazwa_zdarzenia\":\"Mgła\",\"stopien\":\"1\",\"obowiazuje_do\":\"2026-10-27 08:00:00\",\"teryt\":[\"0201\"]},"
                + "{\"nazwa_zdarzenia\":\"Przymrozki\",\"stopien\":\"1\",\"obowiazuje_do\":\"2026-10-26 08:00:00\",\"teryt\":[\"1261\"]}]";
        List<String> w = Weather.warnings(json, "1261", NOW);
        assertEquals(1, w.size());
        assertEquals("Silny wiatr · 1° · do 27.10 08:00", w.get(0));
    }

    @Test
    public void noWarningsMessageIsAnEmptyList() throws Exception {
        assertTrue(Weather.warnings(res("warnings-none.json"), "1261", NOW).isEmpty());
    }

    @Test
    public void everyCodeHasALineIcon() {
        assertEquals(R.drawable.ic_w_fog, Weather.iconRes(45, true));
        assertEquals(R.drawable.ic_w_night, Weather.iconRes(0, false));
        assertEquals(R.drawable.ic_w_partly_night, Weather.iconRes(2, false));
        assertEquals(R.drawable.ic_w_drizzle, Weather.iconRes(55, true));
        assertEquals(R.drawable.ic_w_heavy_rain, Weather.iconRes(65, true));
        assertEquals(R.drawable.ic_w_showers, Weather.iconRes(81, true));
        assertEquals(R.drawable.ic_w_snow, Weather.iconRes(86, true));
        assertEquals(R.drawable.ic_w_storm, Weather.iconRes(99, true));
        assertEquals(R.drawable.ic_w_cloud, Weather.iconRes(7, true));   // unknown code
    }

    @Test
    public void lineForHome() throws Exception {
        Weather.Forecast f = Weather.parse(res("openmeteo.json"), res("synop.json"), null, "1261", NOW, NOW);
        String line = Weather.line(f);
        assertTrue(line, line.contains("10°"));
        assertTrue(line, line.contains("jutro"));
    }
}
