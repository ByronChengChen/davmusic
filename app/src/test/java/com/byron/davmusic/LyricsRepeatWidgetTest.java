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

    @Test public void repeatGlassBackgroundIsTranslucentAndLayered() {
        android.content.Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_DavMusic);
        android.graphics.drawable.Drawable background = context.getDrawable(R.drawable.repeat_glass_background);
        assertNotNull(background);
        assertTrue(background instanceof android.graphics.drawable.LayerDrawable);
        android.graphics.drawable.Drawable glassLayer = ((android.graphics.drawable.LayerDrawable) background).getDrawable(0);
        assertTrue(glassLayer instanceof android.graphics.drawable.GradientDrawable);
        android.graphics.drawable.GradientDrawable shape = (android.graphics.drawable.GradientDrawable) glassLayer;
        assertEquals(0xD9, android.graphics.Color.alpha(shape.getColor().getDefaultColor()));
    }

    @Test public void repeatDialogWindowIsConfiguredForBottomSheet() {
        android.content.Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_DavMusic);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(context).create();
        dialog.show();
        dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
        dialog.getWindow().setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        assertTrue((dialog.getWindow().getAttributes().gravity & android.view.Gravity.BOTTOM)
                == android.view.Gravity.BOTTOM);
        assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                dialog.getWindow().getAttributes().width);
        dialog.dismiss();
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
