package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import com.newtube.mobile.ui.common.Motion;

/**
 * Drag the floating mini-player card anywhere across the screen, or swipe sideways to dismiss.
 *
 * <p>Taps expand the player (or trigger child controls like play/pause and close). Once touch
 * travels beyond slop, dragging takes over, moving the card freely in 2D across the viewport.
 * On release, a fast horizontal flick or dragging predominantly off-screen flings the card away
 * and notifies {@link Callback#onSwipedAway}; otherwise, the card settles smoothly within screen
 * boundaries, saving its position across host activities and playback minimize transitions.</p>
 */
public final class MiniCardSwipe implements View.OnTouchListener {
    public interface Callback {
        /** False while the card must not move (closing, entering, no session). */
        boolean canSwipe();

        /** Committed: fly the card to {@code toTranslationX} over {@code durationMs}, then close. */
        void onSwipedAway(float toTranslationX, long durationMs);

        /** Called when the card has been dragged to a new position. */
        default void onPositionChanged(float translationX, float translationY) {}
    }

    private static final float FLING_VELOCITY_DP = 450f;
    private static final long FLY_MIN_MS = 110;
    private static final long FLY_MAX_MS = 240;
    private static final float MARGIN_DP = 12f;

    private final View mCard;
    private final Callback mCallback;
    private final int mTouchSlop;
    private final float mFlingVelocity;

    private float mDownRawX;
    private float mDownRawY;
    private float mStartTx;
    private float mStartTy;
    private boolean mDragging;
    private boolean mIgnoring;
    @Nullable
    private VelocityTracker mVelocity;

    /** Returns the swipe/drag handler, so the host can {@link #stop} it before moving the card itself. */
    public static MiniCardSwipe attach(View card, Callback callback, View... touchTargets) {
        MiniCardSwipe swipe = new MiniCardSwipe(card, callback);
        for (View target : touchTargets) {
            if (target != null) {
                target.setOnTouchListener(swipe);
            }
        }
        return swipe;
    }

    public void stop() {
        if (mDragging) {
            mDragging = false;
            mIgnoring = true;
            recycleVelocity();
        }
        mCard.animate().cancel();
    }

    private MiniCardSwipe(View card, Callback callback) {
        mCard = card;
        mCallback = callback;
        mTouchSlop = ViewConfiguration.get(card.getContext()).getScaledTouchSlop();
        mFlingVelocity = FLING_VELOCITY_DP * card.getResources().getDisplayMetrics().density;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mStartTx = mCard.getTranslationX();
                mStartTy = mCard.getTranslationY();
                mDragging = false;
                mIgnoring = false;
                recycleVelocity();
                mVelocity = VelocityTracker.obtain();
                trackVelocity(event);
                mCard.animate().cancel();
                return false; // View still sees the press (ripple, click)

            case MotionEvent.ACTION_MOVE: {
                if (mIgnoring) {
                    return true;
                }
                trackVelocity(event);
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (!mDragging) {
                    if (Math.hypot(dx, dy) <= mTouchSlop || !mCallback.canSwipe()) {
                        return false;
                    }
                    mDragging = true;
                    // Cancel press on the touched view so click/ripple does not fire on release
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    view.onTouchEvent(cancel);
                    cancel.recycle();
                    ViewParent parent = mCard.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    mCard.animate().cancel();
                }

                float targetTx = mStartTx + dx;
                float targetTy = mStartTy + dy;

                float minTy = getMinTranslationY();
                float maxTy = 0f;
                float clampedTy = Math.max(minTy, Math.min(maxTy, targetTy));

                mCard.setTranslationX(targetTx);
                mCard.setTranslationY(clampedTy);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mIgnoring) {
                    mIgnoring = false;
                    return true;
                }
                if (!mDragging) {
                    recycleVelocity();
                    return false;
                }
                trackVelocity(event);
                float velocityX = 0f;
                float velocityY = 0f;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    velocityX = mVelocity.getXVelocity();
                    velocityY = mVelocity.getYVelocity();
                }
                recycleVelocity();
                mDragging = false;
                handleDragRelease(velocityX, velocityY);
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN:
                if (mDragging) {
                    mDragging = false;
                    mIgnoring = true;
                    recycleVelocity();
                    settleWithinBounds();
                }
                return mIgnoring;

            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                mIgnoring = false;
                if (mDragging) {
                    mDragging = false;
                    settleWithinBounds();
                    return true;
                }
                return false;

            default:
                return mDragging;
        }
    }

    private void handleDragRelease(float velocityX, float velocityY) {
        float density = mCard.getResources().getDisplayMetrics().density;
        float cardW = mCard.getWidth() > 0 ? mCard.getWidth() : 180f * density;
        int parentW = getParentWidth();

        float visualLeft = mCard.getLeft() + mCard.getTranslationX();
        float visualRight = mCard.getRight() + mCard.getTranslationX();

        boolean isFling = Math.abs(velocityX) > mFlingVelocity && Math.abs(velocityX) > Math.abs(velocityY) * 1.5f;
        boolean flingRight = isFling && velocityX > 0 && visualRight > (parentW - cardW * 0.4f);
        boolean flingLeft = isFling && velocityX < 0 && visualLeft < (cardW * 0.4f);
        boolean draggedOffRight = visualLeft > (parentW - cardW * 0.5f);
        boolean draggedOffLeft = visualRight < (cardW * 0.5f);

        if ((flingRight || flingLeft || draggedOffRight || draggedOffLeft) && mCallback.canSwipe()) {
            boolean toRight = flingRight || draggedOffRight || (isFling ? velocityX > 0 : mCard.getTranslationX() > 0);
            float offscreenX = toRight ? (parentW - mCard.getLeft() + 20f * density) : (-mCard.getRight() - 20f * density);
            float speed = Math.max(Math.abs(velocityX), mFlingVelocity * 1.5f);
            float remaining = Math.abs(offscreenX - mCard.getTranslationX());
            long durationMs = Math.round(1000f * remaining / speed);
            durationMs = Math.max(FLY_MIN_MS, Math.min(FLY_MAX_MS, durationMs));
            mCallback.onSwipedAway(offscreenX, durationMs);
            return;
        }

        settleWithinBounds();
    }

    private void settleWithinBounds() {
        float minTx = getMinTranslationX();
        float maxTx = 0f;
        float minTy = getMinTranslationY();
        float maxTy = 0f;

        float finalTx = Math.max(minTx, Math.min(maxTx, mCard.getTranslationX()));
        float finalTy = Math.max(minTy, Math.min(maxTy, mCard.getTranslationY()));

        MiniPlayerBridge.setSavedMiniPosition(finalTx, finalTy);
        mCallback.onPositionChanged(finalTx, finalTy);

        if (mCard.getTranslationX() != finalTx || mCard.getTranslationY() != finalTy) {
            mCard.animate()
                    .translationX(finalTx)
                    .translationY(finalTy)
                    .setDuration(180)
                    .setInterpolator(Motion.EMPHASIZED)
                    .start();
        }
    }

    private float getMarginPx() {
        return MARGIN_DP * mCard.getResources().getDisplayMetrics().density;
    }

    private int getParentWidth() {
        View parent = (View) mCard.getParent();
        return parent != null && parent.getWidth() > 0 ? parent.getWidth()
                : mCard.getResources().getDisplayMetrics().widthPixels;
    }

    private int getParentHeight() {
        View parent = (View) mCard.getParent();
        return parent != null && parent.getHeight() > 0 ? parent.getHeight()
                : mCard.getResources().getDisplayMetrics().heightPixels;
    }

    private float getMinTranslationX() {
        float margin = getMarginPx();
        float left = mCard.getLeft();
        if (left <= 0) {
            int cardW = mCard.getWidth() > 0 ? mCard.getWidth()
                    : Math.round(180f * mCard.getResources().getDisplayMetrics().density);
            return -(getParentWidth() - cardW - margin);
        }
        return margin - left;
    }

    private float getMinTranslationY() {
        float margin = getMarginPx();
        float top = mCard.getTop();
        if (top <= 0) {
            int cardH = mCard.getHeight() > 0 ? mCard.getHeight()
                    : Math.round(102f * mCard.getResources().getDisplayMetrics().density);
            return -(getParentHeight() - cardH - margin);
        }
        return margin - top;
    }

    private void recycleVelocity() {
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }

    private void trackVelocity(MotionEvent event) {
        if (mVelocity == null) {
            return;
        }
        MotionEvent raw = MotionEvent.obtain(event);
        raw.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(raw);
        raw.recycle();
    }
}
