package com.byron.davmusic;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.SupportMenuInflater;
import androidx.appcompat.view.menu.MenuBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real resources/widgets under Robolectric; CI only, no source-string assertions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VisualThemeTest {
    private Context themed(boolean night) {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        Context configured = RuntimeEnvironment.getApplication().createConfigurationContext(config);
        return new ContextThemeWrapper(configured, R.style.Theme_DavMusic_NoActionBar);
    }

    private View inflate(Context context, int id) {
        return LayoutInflater.from(context).inflate(id, new FrameLayout(context), false);
    }

    private void allLayouts(boolean night) {
        Context c = themed(night);
        int[] layouts = {R.layout.activity_main, R.layout.activity_search, R.layout.activity_lyrics,
                R.layout.activity_server_config, R.layout.activity_server_manage,
                R.layout.dialog_server_edit, R.layout.dialog_lyrics_search, R.layout.dialog_upload_progress,
                R.layout.item_file, R.layout.item_server, R.layout.item_search_result,
                R.layout.item_lyric_line, R.layout.view_player_bar};
        for (int layout : layouts) {
            View view = inflate(c, layout);
            assertNotNull(c.getResources().getResourceEntryName(layout), view);
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
            assertTrue(view.getMeasuredWidth() > 0);
        }
        assertEquals(night ? Color.BLACK : Color.rgb(242,242,247), c.getColor(R.color.background));
        assertEquals(night ? Color.rgb(255,113,136) : Color.rgb(201,44,72), c.getColor(R.color.accent));
        TypedValue lightBars = new TypedValue();
        assertTrue(c.getTheme().resolveAttribute(android.R.attr.windowLightStatusBar, lightBars, true));
        assertEquals(!night, lightBars.data != 0);
        TextView title = inflate(c, R.layout.item_file).findViewById(R.id.fileNameTextView);
        assertEquals(c.getColor(R.color.text_primary), title.getCurrentTextColor());
    }

    @Test public void allLayoutsInflateInLightTheme() { allLayouts(false); }
    @Test public void allLayoutsInflateInDarkTheme() { allLayouts(true); }

    @Test public void mainMenuRetainsAllActionsAndOrder() {
        Context c = themed(false);
        MenuBuilder menu = new MenuBuilder(c);
        new SupportMenuInflater(c).inflate(R.menu.main_menu, menu);
        int[] expected = {R.id.menu_search, R.id.menu_new_folder, R.id.menu_refresh,
                R.id.menu_manage_servers, R.id.menu_sort, R.id.menu_locate_current,
                R.id.menu_settings, R.id.menu_clear_cache, R.id.menu_log_endpoint,
                R.id.menu_log_token, R.id.menu_upload_log, R.id.menu_about};
        assertEquals(expected.length, menu.size());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], menu.getItem(i).getItemId());
    }

    @Test public void allSharedPlayerCopiesKeepButtonsAndSeekCallbacks() {
        Context c = themed(false);
        for (int layout : new int[]{R.layout.activity_main, R.layout.activity_search, R.layout.activity_lyrics}) {
            View screen = inflate(c, layout);
            AtomicInteger calls = new AtomicInteger();
            for (int id : new int[]{R.id.playModeButton, R.id.previousButton, R.id.playPauseButton, R.id.nextButton}) {
                View button = screen.findViewById(id);
                assertNotNull(button);
                assertNotNull(button.getContentDescription());
                button.setOnClickListener(v -> calls.incrementAndGet());
                assertTrue(button.performClick());
            }
            assertEquals(4, calls.get());
            SeekBar seek = screen.findViewById(R.id.playerProgressBar);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean user) { calls.incrementAndGet(); }
                public void onStartTrackingTouch(SeekBar bar) { }
                public void onStopTrackingTouch(SeekBar bar) { }
            });
            seek.setProgress(31);
            assertEquals(31, seek.getProgress());
            assertEquals(5, calls.get());
        }
    }

    @Test public void serverFieldsPreservePasswordAndDisabledSave() {
        Context c = themed(false);
        View view = inflate(c, R.layout.activity_server_config);
        assertFalse(view.findViewById(R.id.saveButton).isEnabled());
        TextView password = view.findViewById(R.id.passwordEditText);
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                password.getInputType() & android.text.InputType.TYPE_MASK_VARIATION);
        assertNotNull(view.findViewById(R.id.testButton));
    }

    @Test public void themedDialogsKeepPositiveAndNegativeActionsInBothModes() {
        for (boolean night : new boolean[]{false, true}) {
            Context c = themed(night);
            AtomicInteger confirmed = new AtomicInteger();
            AlertDialog dialog = new AlertDialog.Builder(c).setTitle(R.string.title_add_server)
                    .setView(inflate(c, R.layout.dialog_server_edit))
                    .setPositiveButton(R.string.btn_save, (d, which) -> confirmed.incrementAndGet())
                    .setNegativeButton(android.R.string.cancel, null).create();
            dialog.show();
            assertNotNull(dialog.findViewById(R.id.editServerUrl));
            assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertEquals(1, confirmed.get());
            dialog.dismiss();
        }
    }
}
