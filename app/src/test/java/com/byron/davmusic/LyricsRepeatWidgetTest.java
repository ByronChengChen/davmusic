package com.byron.davmusic;

import static org.junit.Assert.*;
import android.widget.Button;
import android.widget.ImageButton;
import android.view.ContextThemeWrapper;
import android.view.View;
import org.robolectric.RuntimeEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LyricsRepeatWidgetTest {
    @Test public void lyricsPageHasMoreButtonAndNoInlineRepeatPanel() {
        android.content.Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_DavMusic);
        View view = android.view.LayoutInflater.from(context).inflate(R.layout.activity_lyrics, null, false);
        assertTrue(view.findViewById(R.id.moreButton) instanceof ImageButton);
        assertNull(view.findViewById(R.id.repeatSetStart));
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(
                context, view.findViewById(R.id.moreButton));
        menu.inflate(R.menu.lyrics_more);
        assertEquals(1, menu.getMenu().size());
        assertEquals(R.id.action_repeat, menu.getMenu().getItem(0).getItemId());
        assertEquals("复读", menu.getMenu().getItem(0).getTitle().toString());
    }

    @Test public void repeatDialogInflatesAllRepeatActions() {
        android.content.Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_DavMusic);
        View view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_ab_repeat, null, false);
        assertNotNull(view.findViewById(R.id.repeatSetStart));
        assertNotNull(view.findViewById(R.id.repeatSetEnd));
        assertNotNull(view.findViewById(R.id.repeatSave));
        assertNotNull(view.findViewById(R.id.repeatToggle));
        assertNotNull(view.findViewById(R.id.repeatIntervals));
        assertEquals("开始复读", ((Button) view.findViewById(R.id.repeatToggle)).getText().toString());
        assertEquals(6f, view.findViewById(R.id.repeatSetStart).getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams
                ? ((android.widget.LinearLayout.LayoutParams) view.findViewById(R.id.repeatSetStart).getLayoutParams()).rightMargin / context.getResources().getDisplayMetrics().density
                : -1f, 0.1f);
        assertEquals(6f, ((android.widget.LinearLayout.LayoutParams) view.findViewById(R.id.repeatSetEnd).getLayoutParams()).rightMargin
                / context.getResources().getDisplayMetrics().density, 0.1f);
    }
}
