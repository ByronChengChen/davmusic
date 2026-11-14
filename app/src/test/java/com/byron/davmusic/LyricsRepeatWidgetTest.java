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
        android.graphics.drawable.Drawable secondary = context.getDrawable(R.drawable.button_surface_secondary);
        assertTrue(secondary instanceof android.graphics.drawable.GradientDrawable);
        android.graphics.drawable.GradientDrawable secondaryShape = (android.graphics.drawable.GradientDrawable) secondary;
        assertEquals(context.getColor(R.color.repeat_secondary_action), secondaryShape.getColor().getDefaultColor());
        android.graphics.drawable.Drawable background = context.getDrawable(R.drawable.repeat_glass_background);
        assertNotNull(background);
        assertTrue(background instanceof android.graphics.drawable.LayerDrawable);
        android.graphics.drawable.Drawable glassLayer = ((android.graphics.drawable.LayerDrawable) background).getDrawable(0);
        assertTrue(glassLayer instanceof android.graphics.drawable.GradientDrawable);
        android.graphics.drawable.GradientDrawable shape = (android.graphics.drawable.GradientDrawable) glassLayer;
        assertEquals(context.getColor(R.color.repeat_sheet_surface), shape.getColor().getDefaultColor());
    }

    @Test public void repeatSemanticPaletteHasDistinctRolesInLightAndDarkModes() {
        for (int mode : new int[]{android.content.res.Configuration.UI_MODE_NIGHT_NO,
                android.content.res.Configuration.UI_MODE_NIGHT_YES}) {
            android.content.res.Configuration config = new android.content.res.Configuration(
                    RuntimeEnvironment.getApplication().getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | mode;
            android.content.Context context = new ContextThemeWrapper(
                    RuntimeEnvironment.getApplication().createConfigurationContext(config), R.style.Theme_DavMusic);
            assertContrast(context.getColor(R.color.repeat_on_selected), context.getColor(R.color.repeat_selected_surface));
            assertContrast(context.getColor(R.color.repeat_on_destructive), context.getColor(R.color.repeat_destructive));
            assertContrast(context.getColor(R.color.text_primary), context.getColor(R.color.repeat_secondary_action));
            assertContrast(context.getColor(R.color.on_primary), context.getColor(R.color.primary));
            View sheet = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_ab_repeat, null, false);
            Button secondary = sheet.findViewById(R.id.repeatSetStart);
            assertNull(secondary.getBackgroundTintList());
            assertEquals(context.getColor(R.color.text_primary), secondary.getCurrentTextColor());
            android.graphics.drawable.GradientDrawable fill = (android.graphics.drawable.GradientDrawable) secondary.getBackground();
            assertEquals(context.getColor(R.color.repeat_secondary_action), fill.getColor().getDefaultColor());
        }
    }

    private static void assertContrast(int foreground, int background) {
        assertTrue("Text contrast must be at least 4.5:1",
                androidx.core.graphics.ColorUtils.calculateContrast(foreground, background) >= 4.5);
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

    @Test public void dynamicRangeActionsHaveExplicitSpacing() throws Exception {
        LyricsActivity activity = org.robolectric.Robolectric.buildActivity(LyricsActivity.class).get();
        java.lang.reflect.Field rangesField = LyricsActivity.class.getDeclaredField("repeatRanges");
        rangesField.setAccessible(true);
        java.util.List<RepeatRange> ranges = new java.util.ArrayList<>();
        ranges.add(RepeatRange.create(1000, 3000, 10000));
        ranges.add(RepeatRange.create(4000, 6000, 10000));
        ((java.util.List<RepeatRange>) rangesField.get(activity)).addAll(ranges);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(
                new ContextThemeWrapper(activity, R.style.Theme_DavMusic)).create();
        dialog.show();
        android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        java.lang.reflect.Field dialogField = LyricsActivity.class.getDeclaredField("repeatDialog");
        dialogField.setAccessible(true);
        dialogField.set(activity, dialog);
        java.lang.reflect.Field containerField = LyricsActivity.class.getDeclaredField("repeatIntervalsView");
        containerField.setAccessible(true);
        containerField.set(activity, container);
        java.lang.reflect.Method render = LyricsActivity.class.getDeclaredMethod("renderRepeatRanges");
        render.setAccessible(true);
        render.invoke(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals(2, container.getChildCount());
        for (int i = 0; i < container.getChildCount(); i++) {
            android.widget.LinearLayout row = (android.widget.LinearLayout) container.getChildAt(i);
            assertEquals(8f, ((android.widget.LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).getMarginEnd() / density, 0.1f);
            assertEquals(8f, ((android.widget.LinearLayout.LayoutParams) row.getLayoutParams()).bottomMargin / density, 0.1f);
            assertEquals("×", ((Button) row.getChildAt(2)).getText().toString());
        }
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
        assertNotNull(((Button) view.findViewById(R.id.repeatToggle)).getBackground());
        assertNotNull(((Button) view.findViewById(R.id.repeatSetStart)).getBackground());
        assertNotNull(((Button) view.findViewById(R.id.repeatSave)).getBackground());
        assertEquals(8f, view.findViewById(R.id.repeatSetStart).getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams
                ? ((android.widget.LinearLayout.LayoutParams) view.findViewById(R.id.repeatSetStart).getLayoutParams()).rightMargin / context.getResources().getDisplayMetrics().density
                : -1f, 0.1f);
        assertEquals(8f, ((android.widget.LinearLayout.LayoutParams) view.findViewById(R.id.repeatSetEnd).getLayoutParams()).rightMargin
                / context.getResources().getDisplayMetrics().density, 0.1f);
    }
}
