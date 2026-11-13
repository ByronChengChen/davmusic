package com.byron.davmusic;

import static org.junit.Assert.*;
import android.widget.Button;
import android.widget.LinearLayout;
import org.robolectric.RuntimeEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LyricsRepeatWidgetTest {
    @Test public void activityInflatesRepeatActionsAndDeleteContainer() {
        android.content.Context context = new android.view.ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_DavMusic);
        android.view.View view = android.view.LayoutInflater.from(context).inflate(R.layout.activity_lyrics, null, false);
        assertNotNull(view.findViewById(R.id.repeatSetStart));
        assertNotNull(view.findViewById(R.id.repeatSetEnd));
        assertNotNull(view.findViewById(R.id.repeatSave));
        assertNotNull(view.findViewById(R.id.repeatToggle));
        assertTrue(view.findViewById(R.id.repeatIntervals) instanceof LinearLayout);
        assertEquals("开始复读", ((Button) view.findViewById(R.id.repeatToggle)).getText().toString());
        assertEquals("设为 A", ((Button) view.findViewById(R.id.repeatSetStart)).getText().toString());
    }
}
