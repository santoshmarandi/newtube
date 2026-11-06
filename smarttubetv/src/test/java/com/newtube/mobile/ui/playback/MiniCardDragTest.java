package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MiniCardDragTest {

    @Before
    public void setUp() {
        MiniPlayerBridge.clearSavedMiniPosition();
    }

    @Test
    public void savedPositionPersistenceInBridge() {
        assertFalse(MiniPlayerBridge.hasSavedMiniPosition());
        MiniPlayerBridge.setSavedMiniPosition(-100f, -200f);
        assertTrue(MiniPlayerBridge.hasSavedMiniPosition());
        assertEquals(-100f, MiniPlayerBridge.getSavedMiniTranslationX(), 0.01f);
        assertEquals(-200f, MiniPlayerBridge.getSavedMiniTranslationY(), 0.01f);

        MiniPlayerBridge.clearSavedMiniPosition();
        assertFalse(MiniPlayerBridge.hasSavedMiniPosition());
        assertEquals(0f, MiniPlayerBridge.getSavedMiniTranslationX(), 0.01f);
        assertEquals(0f, MiniPlayerBridge.getSavedMiniTranslationY(), 0.01f);
    }

    @Test
    public void dragAndSnapToCorner() {
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
        parent.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        );
        parent.layout(0, 0, 1080, 1920);

        View card = new View(RuntimeEnvironment.getApplication());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(360, 204);
        card.setLayoutParams(lp);
        parent.addView(card);
        card.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(204, View.MeasureSpec.EXACTLY)
        );
        card.layout(700, 1600, 1060, 1804);

        final boolean[] swipedAway = {false};
        final float[] newPos = {0f, 0f};

        MiniCardSwipe swipe = MiniCardSwipe.attach(card, new MiniCardSwipe.Callback() {
            @Override
            public boolean canSwipe() {
                return true;
            }

            @Override
            public void onSwipedAway(float toTranslationX, long durationMs) {
                swipedAway[0] = true;
            }

            @Override
            public void onPositionChanged(float translationX, float translationY) {
                newPos[0] = translationX;
                newPos[1] = translationY;
            }
        }, card);

        long now = SystemClock.uptimeMillis();

        // DOWN at (800, 1700)
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 800f, 1700f, 0);
        card.dispatchTouchEvent(down);
        down.recycle();

        // Drag towards upper-left: dx = -500, dy = -1000
        MotionEvent move = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, 300f, 700f, 0);
        card.dispatchTouchEvent(move);
        move.recycle();

        assertEquals(-500f, card.getTranslationX(), 1f);
        assertEquals(-1000f, card.getTranslationY(), 1f);

        // UP: releases in upper-left quadrant -> should snap to Top-Left corner!
        MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, 300f, 700f, 0);
        card.dispatchTouchEvent(up);
        up.recycle();

        assertFalse("Card should not be swiped away", swipedAway[0]);
        assertTrue("Position should be saved in MiniPlayerBridge", MiniPlayerBridge.hasSavedMiniPosition());

        // Left corner translation = 12 - 700 = -688f
        // Top corner translation = (56 + 12) - 1600 = -1532f
        assertEquals(-688f, MiniPlayerBridge.getSavedMiniTranslationX(), 1f);
        assertEquals(-1532f, MiniPlayerBridge.getSavedMiniTranslationY(), 1f);
    }
}
