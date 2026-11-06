package com.newtube.mobile.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MobileSearchFocusTest {

    @Test
    public void testSearchSubmitClearsFocusAndHidesSuggestions() {
        MobileSearchActivity activity = Robolectric.buildActivity(MobileSearchActivity.class).setup().get();
        EditText input = activity.findViewById(R.id.mobile_search_input);
        View suggestions = activity.findViewById(R.id.mobile_search_suggestions);

        input.requestFocus();
        assertTrue("Input should have focus initially", input.hasFocus());

        // Submit search
        input.setText("funny cats");
        input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

        assertFalse("Input should lose focus after search submission", input.hasFocus());
        assertEquals("Suggestions overlay should be GONE after submit", View.GONE, suggestions.getVisibility());
    }

    @Test
    public void testBackPressedClearsSearchFocusFirst() {
        MobileSearchActivity activity = Robolectric.buildActivity(MobileSearchActivity.class).setup().get();
        EditText input = activity.findViewById(R.id.mobile_search_input);
        View suggestions = activity.findViewById(R.id.mobile_search_suggestions);

        input.requestFocus();
        suggestions.setVisibility(View.VISIBLE);
        assertTrue(input.hasFocus());

        activity.getOnBackPressedDispatcher().onBackPressed();

        assertFalse("Back pressed should clear search focus", input.hasFocus());
        assertEquals(View.GONE, suggestions.getVisibility());
        assertFalse("Activity should not be finishing on first back press while editing", activity.isFinishing());
    }

    @Test
    public void testFocusLossHidesSuggestions() {
        MobileSearchActivity activity = Robolectric.buildActivity(MobileSearchActivity.class).setup().get();
        EditText input = activity.findViewById(R.id.mobile_search_input);
        View suggestions = activity.findViewById(R.id.mobile_search_suggestions);
        View root = activity.findViewById(R.id.mobile_search_root);

        input.requestFocus();
        suggestions.setVisibility(View.VISIBLE);

        // Moving focus to root container
        root.requestFocus();

        assertFalse("Input should lose focus", input.hasFocus());
        assertEquals("Suggestions overlay should hide when input loses focus", View.GONE, suggestions.getVisibility());
    }
}
