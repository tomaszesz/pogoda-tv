package tv.pogoda.imgw;

import android.service.dreams.DreamService;

/** The weather screen as the system screensaver. Any key wakes the TV back to where it was. */
public class WeatherDream extends DreamService {

    private WeatherScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.weather_screen);
        scene = new WeatherScene(this, getWindow().getDecorView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.destroy();
        super.onDetachedFromWindow();
    }
}
