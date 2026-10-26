package tv.pogoda.imgw;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/**
 * The weather screen as an activity: what the launcher starts after Home has been idle (idle_activity), and the
 * app's tile. Like the launcher's own clock, any key goes back to Home; it never stays in the recents list.
 */
public class WeatherActivity extends Activity {

    private WeatherScene scene;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.weather_screen);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        scene = new WeatherScene(this, getWindow().getDecorView());
    }

    @Override
    protected void onStart() {
        super.onStart();
        scene.start();
    }

    @Override
    protected void onStop() {
        scene.stop();
        super.onStop();
        // Gone from the screen means done: the process holds nothing until the next time.
        finish();
    }

    @Override
    protected void onDestroy() {
        scene.destroy();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        // Volume and mute keep working over the screen; everything else wakes it.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
