package tv.pogoda.imgw;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather screen (layout weather_screen), shared by WeatherActivity and WeatherDream. After weatherclock-tv's
 * scene: black and a slow ambient glow. Every 30 seconds the content fades out, moves to a new spot around the centre
 * and fades back in, so nothing burns into the OLED panel. Data comes from the cache at once; it is fetched when
 * older than 20 minutes and then every 30 minutes while the screen is shown, and the hours are redrawn every 10
 * minutes so past ones drop off. Nothing runs when the screen is gone.
 */
final class WeatherScene {

    private static final long MOVE_MS = 30_000L;
    private static final long FADE_OUT_MS = 700L;
    private static final long FADE_IN_MS = 900L;
    /** How far from the centre the content may sit; the move happens while it is faded out, so it is never seen. */
    private static final int DRIFT_X_DP = 24;
    private static final int DRIFT_Y_DP = 16;
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long STALE_MS = 20 * 60_000L;
    private static final long TOO_OLD_MS = 6 * 60 * 60_000L;
    private static final int REDRAW_EVERY_MOVES = 20;
    private static final int GLOW_EVERY_MOVES = 2;
    private static final int GLOW_STEPS = 12;
    private static final long GLOW_STEP_MS = 500L;
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFF1E88E5};

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final View content;
    private final View now;
    private final ImageView nowIcon;
    private final TextView nowTemp, nowText, nowDetail, nowSource, warnings, sunrise, sunset;
    private final View sun;
    private final LinearLayout hours, days;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private int moves;
    private boolean running;
    /** Where the content sits relative to the centre, in px; a new random spot every MOVE_MS. */
    private float offsetX, offsetY;
    private int placedWidth, placedHeight;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moves++;
            if (moves % REDRAW_EVERY_MOVES == 0) show(Weather.cached(context));
            move();
            if (moves % GLOW_EVERY_MOVES == 0) shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    WeatherScene(Context context, View root) {
        this.context = context;
        content = root.findViewById(R.id.content);
        now = root.findViewById(R.id.now);
        nowIcon = root.findViewById(R.id.nowIcon);
        nowTemp = root.findViewById(R.id.nowTemp);
        nowText = root.findViewById(R.id.nowText);
        nowDetail = root.findViewById(R.id.nowDetail);
        nowSource = root.findViewById(R.id.nowSource);
        warnings = root.findViewById(R.id.warnings);
        sun = root.findViewById(R.id.sun);
        sunrise = root.findViewById(R.id.sunrise);
        sunset = root.findViewById(R.id.sunset);
        hours = root.findViewById(R.id.hours);
        days = root.findViewById(R.id.days);
        View ambient = root.findViewById(R.id.ambient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.6f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        running = true;
        show(Weather.cached(context));
        content.setAlpha(0f);
        content.post(() -> {
            pickOffset();
            place();
            content.animate().alpha(1f).setDuration(1000).start();
        });
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(tick, MOVE_MS);
        boolean stale = System.currentTimeMillis() - Weather.fetchedAt(context) > STALE_MS;
        handler.postDelayed(refresh, stale ? 0 : REFRESH_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        content.animate().cancel();
    }

    void destroy() {
        stop();
        io.shutdownNow();
    }

    private void fetch() {
        io.execute(() -> {
            Weather.Forecast f;
            try {
                f = Weather.fetch(context);
            } catch (Exception e) {
                return;   // keep showing the cached data until it is too old
            }
            handler.post(() -> {
                if (running) show(f);
            });
        });
    }

    private void show(Weather.Forecast f) {
        boolean ok = f != null && System.currentTimeMillis() - f.fetchedAt <= TOO_OLD_MS;
        int visibility = ok ? View.VISIBLE : View.INVISIBLE;
        now.setVisibility(visibility);
        hours.setVisibility(visibility);
        days.setVisibility(visibility);
        if (!ok) {
            warnings.setText(R.string.no_data);
            warnings.setVisibility(View.VISIBLE);
            return;
        }
        nowIcon.setImageResource(Weather.iconRes(f.code, f.day));
        nowTemp.setText(f.temp + "°");
        nowText.setText(Weather.describe(f.code) + "  ·  " + f.todayMax + "° / " + f.todayMin + "°");
        StringBuilder detail = new StringBuilder("Odczuwalna " + f.feels + "°");
        detail.append("   ·   Wiatr ").append(String.format(Locale.US, "%.0f", f.wind)).append(" m/s");
        if (!f.windFrom.isEmpty()) detail.append(' ').append(f.windFrom);
        detail.append("   ·   ").append(f.humidity).append("%   ·   ").append(f.pressure).append(" hPa");
        nowDetail.setText(detail);
        nowSource.setText(f.measured
                ? "Pomiar IMGW · " + context.getString(R.string.station_name) + " · " + f.measuredAt
                : "Model Open-Meteo · " + context.getString(R.string.place_name));

        if (f.warnings.isEmpty()) {
            warnings.setVisibility(View.GONE);
        } else {
            warnings.setText("⚠  Ostrzeżenie IMGW: " + String.join("   ·   ", f.warnings));
            warnings.setVisibility(View.VISIBLE);
        }

        hours.removeAllViews();
        for (Weather.Hour h : f.hours) {
            LinearLayout col = column();
            col.addView(label(h.label, 12, 0x99FFFFFF, R.font.outfit_regular, 0.04f));
            col.addView(icon(Weather.iconRes(h.code, h.day), 36));
            col.addView(label(h.temp + "°", 18, 0xE6FFFFFF, R.font.outfit_regular, 0f));
            col.addView(label(h.rainChance >= 10 ? h.rainChance + "%" : " ", 11, 0xFF7FB8FF, R.font.outfit_medium, 0f));
            hours.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        days.removeAllViews();
        for (Weather.Day d : f.days) {
            LinearLayout col = column();
            col.addView(label(d.name, 14, 0xB3FFFFFF, R.font.outfit_medium, 0.04f));
            col.addView(icon(Weather.iconRes(d.code, true), 48));
            col.addView(label(d.max + "°  " + d.min + "°", 18, 0xE6FFFFFF, R.font.outfit_regular, 0f));
            String rain = d.rainMm >= 0.1
                    ? String.format(new Locale("pl"), "%.1f mm · %d%%", d.rainMm, d.rainChance) : "bez opadów";
            col.addView(label(rain, 11, d.rainMm >= 0.1 ? 0xFF7FB8FF : 0x66FFFFFF, R.font.outfit_regular, 0f));
            days.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        boolean sunKnown = !f.sunrise.isEmpty() && !f.sunset.isEmpty();
        sun.setVisibility(sunKnown ? View.VISIBLE : View.GONE);
        if (sunKnown) {
            sunrise.setText("Wschód  " + f.sunrise);
            sunset.setText("Zachód  " + f.sunset);
        }
        // The data can change the content's size: keep it at the same offset from the new centre.
        content.post(() -> {
            if (content.getWidth() != placedWidth || content.getHeight() != placedHeight) place();
        });
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        return col;
    }

    /** One of the line icons, sizeDp square, centred in its column. */
    private ImageView icon(int res, int sizeDp) {
        ImageView v = new ImageView(context);
        v.setImageResource(res);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String text, int sp, int color, int font, float spacing) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        // A vertical LinearLayout gives its children the full column width: centre the text in it.
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (font != 0) t.setTypeface(context.getResources().getFont(font));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /** Every MOVE_MS: fade out, jump to a new spot a few points from the centre, fade back in. */
    private void move() {
        content.animate().alpha(0f).setDuration(FADE_OUT_MS).withLayer().withEndAction(() -> {
            pickOffset();
            place();
            content.animate().alpha(1f).setDuration(FADE_IN_MS).withLayer().start();
        }).start();
    }

    /** At once, without animation: on start and when the content's size changes. */
    private void place() {
        placedWidth = content.getWidth();
        placedHeight = content.getHeight();
        content.setX(targetX());
        content.setY(targetY());
        centreGlow();
    }

    private void pickOffset() {
        offsetX = (random.nextFloat() * 2 - 1) * dp(DRIFT_X_DP);
        offsetY = (random.nextFloat() * 2 - 1) * dp(DRIFT_Y_DP);
    }

    private float targetX() {
        View parent = (View) content.getParent();
        float x = (parent.getWidth() - content.getWidth()) / 2f + offsetX;
        return Math.max(0, Math.min(x, parent.getWidth() - content.getWidth()));
    }

    private float targetY() {
        View parent = (View) content.getParent();
        float y = (parent.getHeight() - content.getHeight()) / 2f + offsetY;
        return Math.max(0, Math.min(y, parent.getHeight() - content.getHeight()));
    }

    /** The glow sits behind the weather block, top right of the content. */
    private void centreGlow() {
        View parent = (View) content.getParent();
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((content.getX() + content.getWidth() * 0.75f) / parent.getWidth(),
                    (content.getY() + content.getHeight() * 0.25f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x33000000 | c, 0x10000000 | c, c});
    }
}
