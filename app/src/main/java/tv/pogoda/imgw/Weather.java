package tv.pogoda.imgw;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The weather for one place, from three small requests:
 * - IMGW-PIB synop (danepubliczne.imgw.pl): the latest hourly measurement at one synoptic station;
 * - IMGW-PIB meteorological warnings, kept only for one powiat (TERYT);
 * - Open-Meteo: the sky now, the next 12 hours and the next 5 days for the place's coordinates.
 * The raw answers are kept in preferences, so every screen shows the last good data at once and survives a dropped
 * connection; each source is refreshed on its own, so one failing does not throw away the others.
 */
final class Weather {

    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    static final Locale PL = new Locale("pl", "PL");

    private static final String PREFS = "weather";
    private static final String SYNOP_URL = "https://danepubliczne.imgw.pl/api/data/synop/id/%s";
    private static final String WARNINGS_URL = "https://danepubliczne.imgw.pl/api/data/warningsmeteo";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
            + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m,"
            + "relative_humidity_2m,pressure_msl"
            + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,"
            + "sunrise,sunset"
            + "&timezone=Europe%%2FWarsaw&forecast_days=6&forecast_hours=14&wind_speed_unit=ms";

    /** An IMGW measurement older than this is not shown as "now"; the model's value is used instead. */
    static final long MEASUREMENT_MAX_AGE_MS = 3 * 60 * 60_000L;
    static final int HOURS = 12;
    static final int DAYS = 5;

    private static final Object LOCK = new Object();

    static final class Hour {
        String label;
        int code;
        boolean day;
        int temp;
        int rainChance;
    }

    static final class Day {
        String name;
        int code;
        int max;
        int min;
        double rainMm;
        int rainChance;
    }

    static final class Forecast {
        long fetchedAt;
        int temp;
        /** True when temp is the IMGW measurement, false when it is the model's. */
        boolean measured;
        String measuredAt = "";
        int feels;
        int code;
        boolean day = true;
        double wind;
        String windFrom = "";
        int humidity;
        int pressure;
        int todayMax;
        int todayMin;
        /** Today's sunrise and sunset, "06:19", local time; empty in data saved before they were asked for. */
        String sunrise = "";
        String sunset = "";
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    /** What the place is: from res/values/config.xml. */
    static final class Place {
        final String lat, lon, synopId, teryt;

        Place(String lat, String lon, String synopId, String teryt) {
            this.lat = lat;
            this.lon = lon;
            this.synopId = synopId;
            this.teryt = teryt;
        }

        static Place of(Context c) {
            return new Place(c.getString(R.string.latitude), c.getString(R.string.longitude),
                    c.getString(R.string.synop_id), c.getString(R.string.teryt));
        }
    }

    private Weather() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * When the forecast was last fetched successfully; 0 if never, or if it was asked for differently (another
     * place, or fields added in an update), so the next screen fetches it again at once.
     */
    static long fetchedAt(Context c) {
        SharedPreferences p = prefs(c);
        return forecastUrl(Place.of(c)).equals(p.getString("url", null)) ? p.getLong("fetchedAt", 0) : 0;
    }

    private static String forecastUrl(Place place) {
        return String.format(Locale.US, FORECAST_URL, place.lat, place.lon);
    }

    /** The last data saved, or null when there is no forecast yet. */
    static Forecast cached(Context c) {
        SharedPreferences p = prefs(c);
        String forecast = p.getString("forecast", null);
        if (forecast == null) return null;
        try {
            return parse(forecast, p.getString("synop", null), p.getString("warnings", null),
                    Place.of(c).teryt, p.getLong("fetchedAt", 0), System.currentTimeMillis());
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Blocking: call off the main thread. Fetches all three sources; one that fails keeps its last answer.
     * Throws only when the forecast itself could not be fetched. One fetch at a time across the screen, the
     * screensaver and the launcher's line.
     */
    static Forecast fetch(Context c) throws IOException, JSONException {
        synchronized (LOCK) {
            Place place = Place.of(c);
            SharedPreferences p = prefs(c);
            SharedPreferences.Editor e = p.edit();
            String url = forecastUrl(place);
            String forecast = get(url);
            long now = System.currentTimeMillis();
            String synop = p.getString("synop", null);
            String warnings = p.getString("warnings", null);
            try {
                synop = get(String.format(Locale.US, SYNOP_URL, place.synopId));
                new JSONObject(synop);   // only a parseable answer is kept
                e.putString("synop", synop);
            } catch (IOException | JSONException ignored) {
                // the previous measurement stays until it is too old to show
            }
            try {
                warnings = get(WARNINGS_URL);
                e.putString("warnings", warnings);
            } catch (IOException ignored) {
                // the previous warnings stay
            }
            Forecast f = parse(forecast, synop, warnings, place.teryt, now, now);
            e.putString("forecast", forecast).putString("url", url).putLong("fetchedAt", now).apply();
            return f;
        }
    }

    /** Pure: the three answers (synop and warnings may be null) as one Forecast, as seen at nowMs. */
    static Forecast parse(String forecastJson, String synopJson, String warningsJson, String teryt,
                          long fetchedAt, long nowMs) throws JSONException {
        JSONObject root = new JSONObject(forecastJson);
        JSONObject cur = root.getJSONObject("current");
        Forecast f = new Forecast();
        f.fetchedAt = fetchedAt;
        f.temp = round(cur.getDouble("temperature_2m"));
        f.feels = round(cur.getDouble("apparent_temperature"));
        f.code = cur.getInt("weather_code");
        f.day = cur.optInt("is_day", 1) == 1;
        f.wind = cur.optDouble("wind_speed_10m", 0);
        f.windFrom = compass(cur.optDouble("wind_direction_10m", Double.NaN));
        f.humidity = round(cur.optDouble("relative_humidity_2m", 0));
        f.pressure = round(cur.optDouble("pressure_msl", 0));

        if (synopJson != null) applySynop(f, synopJson, nowMs);

        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZONE);
        JSONObject hourly = root.getJSONObject("hourly");
        JSONArray hTime = hourly.getJSONArray("time");
        for (int i = 0; i < hTime.length() && f.hours.size() < HOURS; i++) {
            LocalDateTime t = LocalDateTime.parse(hTime.getString(i));
            if (!t.isAfter(now)) continue;   // the next full hours only
            Hour h = new Hour();
            h.label = String.format(Locale.US, "%02d:00", t.getHour());
            h.temp = round(hourly.getJSONArray("temperature_2m").getDouble(i));
            h.code = hourly.getJSONArray("weather_code").getInt(i);
            h.day = hourly.getJSONArray("is_day").optInt(i, 1) == 1;
            h.rainChance = hourly.getJSONArray("precipitation_probability").optInt(i, 0);
            f.hours.add(h);
        }

        JSONObject daily = root.getJSONObject("daily");
        JSONArray dTime = daily.getJSONArray("time");
        for (int i = 0; i < dTime.length(); i++) {
            LocalDate date = LocalDate.parse(dTime.getString(i));
            int max = round(daily.getJSONArray("temperature_2m_max").getDouble(i));
            int min = round(daily.getJSONArray("temperature_2m_min").getDouble(i));
            if (date.equals(now.toLocalDate())) {
                f.todayMax = max;
                f.todayMin = min;
                f.sunrise = clock(daily.optJSONArray("sunrise"), i);
                f.sunset = clock(daily.optJSONArray("sunset"), i);
                continue;
            }
            if (date.isBefore(now.toLocalDate()) || f.days.size() >= DAYS) continue;
            Day d = new Day();
            d.name = date.equals(now.toLocalDate().plusDays(1)) ? "Jutro" : capitalize(
                    date.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, PL));
            d.code = daily.getJSONArray("weather_code").getInt(i);
            d.max = max;
            d.min = min;
            d.rainMm = daily.getJSONArray("precipitation_sum").optDouble(i, 0);
            d.rainChance = daily.getJSONArray("precipitation_probability_max").optInt(i, 0);
            f.days.add(d);
        }

        if (warningsJson != null) f.warnings.addAll(warnings(warningsJson, teryt, nowMs));
        return f;
    }

    /** IMGW's measurement replaces the model's temperature, wind, humidity and pressure while it is fresh. */
    private static void applySynop(Forecast f, String json, long nowMs) throws JSONException {
        JSONObject s = new JSONObject(json);
        String date = s.optString("data_pomiaru", "");
        String hour = s.optString("godzina_pomiaru", "");
        double temp = number(s, "temperatura");
        if (date.isEmpty() || hour.isEmpty() || Double.isNaN(temp)) return;
        // IMGW gives the measurement hour in UTC.
        Instant at = LocalDate.parse(date).atTime(Integer.parseInt(hour.trim()), 0).toInstant(ZoneOffset.UTC);
        if (nowMs - at.toEpochMilli() > MEASUREMENT_MAX_AGE_MS) return;
        f.temp = round(temp);
        f.measured = true;
        f.measuredAt = DateTimeFormatter.ofPattern("HH:mm").format(at.atZone(ZONE));
        double wind = number(s, "predkosc_wiatru");
        if (!Double.isNaN(wind)) f.wind = wind;
        double dir = number(s, "kierunek_wiatru");
        if (!Double.isNaN(dir)) f.windFrom = compass(dir);
        double humidity = number(s, "wilgotnosc_wzgledna");
        if (!Double.isNaN(humidity)) f.humidity = round(humidity);
        double pressure = number(s, "cisnienie");
        if (!Double.isNaN(pressure)) f.pressure = round(pressure);
    }

    /**
     * IMGW warnings in force for the powiat: "Silny wiatr · 1° · do 27.10 08:00". With no warnings at all IMGW
     * answers an object with a message instead of an array, which gives an empty list.
     */
    static List<String> warnings(String json, String teryt, long nowMs) {
        List<String> out = new ArrayList<>();
        JSONArray all;
        try {
            all = new JSONArray(json);
        } catch (JSONException notAList) {
            return out;
        }
        DateTimeFormatter in = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        DateTimeFormatter shown = DateTimeFormatter.ofPattern("dd.MM HH:mm");
        for (int i = 0; i < all.length(); i++) {
            JSONObject w = all.optJSONObject(i);
            if (w == null) continue;
            JSONArray areas = w.optJSONArray("teryt");
            boolean here = false;
            for (int j = 0; areas != null && j < areas.length(); j++) {
                if (teryt.equals(areas.optString(j))) here = true;
            }
            if (!here) continue;
            String until = "";
            try {
                LocalDateTime end = LocalDateTime.parse(w.optString("obowiazuje_do", ""), in);
                if (end.atZone(ZONE).toInstant().toEpochMilli() < nowMs) continue;   // already over
                until = " · do " + shown.format(end);
            } catch (RuntimeException unparsed) {
                // shown without an end time
            }
            String level = w.optString("stopien", "");
            out.add(w.optString("nazwa_zdarzenia", "Ostrzeżenie") + (level.isEmpty() ? "" : " · " + level + "°") + until);
        }
        return out;
    }

    /**
     * One line for the launcher's Home, e.g. "10°  ·  Pochmurno  ·  jutro 12° / 4°". The icon goes separately
     * (WeatherProvider's "icon" column), so Home draws the same line icons as this screen.
     */
    static String line(Forecast f) {
        StringBuilder b = new StringBuilder();
        b.append(f.temp).append("°  ·  ").append(describe(f.code));
        if (!f.days.isEmpty()) {
            Day d = f.days.get(0);
            b.append("  ·  jutro ").append(d.max).append("° / ").append(d.min).append('°');
        }
        if (!f.warnings.isEmpty()) b.append("  ·  ⚠ ").append(f.warnings.get(0));
        return b.toString();
    }

    /** WMO weather code in Polish. */
    static String describe(int code) {
        if (code == 0) return "Bezchmurnie";
        if (code == 1) return "Przeważnie pogodnie";
        if (code == 2) return "Częściowe zachmurzenie";
        if (code == 3) return "Pochmurno";
        if (code == 45 || code == 48) return "Mgła";
        if (code >= 51 && code <= 57) return "Mżawka";
        if (code >= 61 && code <= 67) return code >= 65 ? "Silny deszcz" : "Deszcz";
        if (code >= 71 && code <= 77) return "Śnieg";
        if (code >= 80 && code <= 82) return "Przelotny deszcz";
        if (code == 85 || code == 86) return "Przelotny śnieg";
        if (code >= 95) return "Burza";
        return "";
    }

    /** WMO weather code as one of the app's own line icons (res/drawable/ic_w_*), the same on every TV. */
    static int iconRes(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_clear : R.drawable.ic_w_night;
        if (code == 1 || code == 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_partly_night;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code >= 51 && code <= 57) return R.drawable.ic_w_drizzle;
        if (code >= 61 && code <= 67) return code >= 65 ? R.drawable.ic_w_heavy_rain : R.drawable.ic_w_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 80 && code <= 82) return R.drawable.ic_w_showers;
        if (code >= 95) return R.drawable.ic_w_storm;
        return R.drawable.ic_w_cloud;
    }

    /** Where the wind blows from, as a Polish abbreviation. */
    static String compass(double degrees) {
        if (Double.isNaN(degrees)) return "";
        String[] names = {"płn.", "płn.-wsch.", "wsch.", "płd.-wsch.", "płd.", "płd.-zach.", "zach.", "płn.-zach."};
        return names[(int) Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8];
    }

    private static double number(JSONObject o, String key) {
        // IMGW sends numbers as strings ("9.8") or null.
        String v = o.optString(key, "");
        if (v.isEmpty() || v.equals("null")) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "2026-10-26T06:19" at index i as "06:19"; empty when missing. */
    private static String clock(JSONArray times, int i) {
        if (times == null) return "";
        try {
            return DateTimeFormatter.ofPattern("HH:mm").format(LocalDateTime.parse(times.optString(i, "")));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(PL) + s.substring(1);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6_000);
        con.setReadTimeout(8_000);
        con.setRequestProperty("User-Agent", "PogodaTV/1.0 (Android TV screensaver)");
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                // Every answer is a few kB; refuse anything absurd rather than fill the TV's memory.
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 512 * 1024) throw new IOException("answer too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            con.disconnect();
        }
    }
}
