package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import com.newtube.mobile.ui.common.Motion;

/**
 * YouTube-style floating mini-player controller:
 * <ul>
 *   <li>4-corner magnetic snapping with velocity physics.</li>
 *   <li>Auto-hiding controls (Play/Pause, Close, Progress) after 2.5s of playback.</li>
 *   <li>Single-tap: toggles controls or expands player when controls are visible.</li>
 *   <li>Double-tap: resizes mini player between compact (1.0x) and enlarged (1.35x).</li>
 *   <li>Pinch-to-zoom: fluid two-finger scaling.</li>
 *   <li>Sideways fling or drag off-screen: smoothly dismisses player.</li>
 * </ul>
 */
public final class MiniCardSwipe implements View.OnTouchListener {
    public interface Callback {
        /** False while the card must not move (closing, entering, no session). */
        boolean canSwipe();

        /** Committed: fly the card to {@code toTranslationX} over {@code durationMs}, then close. */
        void onSwipedAway(float toTranslationX, long durationMs);

        /** Called when the card has been dragged to a new position. */
        default void onPositionChanged(float translationX, float translationY) {}

        /** Called when user taps to expand the mini player back to full-screen watch page. */
        default void onExpand() {}
    }

    private static final float FLING_VELOCITY_DP = 450f;
    private static final long FLY_MIN_MS = 110;
    private static final long FLY_MAX_MS = 240;
    private static final float MARGIN_DP = 12f;
    private static final float TOP_BAR_DP = 56f;
    private static final long AUTO_HIDE_DELAY_MS = 2500;

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

    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    private float mCurrentScale = 1.0f;

    private boolean mControlsVisible = true;
    private final Runnable mAutoHideControls = this::hideControls;
    @Nullable private View mPlayPauseView;
    @Nullable private View mCloseView;
    @Nullable private View mProgressView;

    public static MiniCardSwipe attach(View card, Callback callback, View... touchTargets) {
        MiniCardSwipe swipe = new MiniCardSwipe(card, callback);
        for (View target : touchTargets) {
            if (target != null) {
                target.setOnTouchListener(swipe);
            }
        }
        return swipe;
    }

    public void setControlsViews(@Nullable View playPause, @Nullable View close, @Nullable View progress) {
        mPlayPauseView = playPause;
        mCloseView = close;
        mProgressView = progress;
        scheduleAutoHide();
    }

    public boolean areControlsVisible() {
        return mControlsVisible;
    }

    public void showControls() {
        mControlsVisible = true;
        animateControlsAlpha(1f);
        scheduleAutoHide();
    }

    public void hideControls() {
        ExoPlayer player = MiniPlayerBridge.getPlayer();
        boolean isPlaying = player != null && player.isPlaying();
        if (!isPlaying) {
            // Keep controls visible while paused
            return;
        }
        mControlsVisible = false;
        animateControlsAlpha(0f);
    }

    private void animateControlsAlpha(float alpha) {
        View[] views = new View[] {mPlayPauseView, mCloseView, mProgressView};
        for (View v : views) {
            if (v != null) {
                v.animate().alpha(alpha).setDuration(180).start();
            }
        }
    }

    public void scheduleAutoHide() {
        mCard.removeCallbacks(mAutoHideControls);
        mCard.postDelayed(mAutoHideControls, AUTO_HIDE_DELAY_MS);
    }

    public void stop() {
        if (mDragging) {
            mDragging = false;
            mIgnoring = true;
            recycleVelocity();
        }
        mCard.removeCallbacks(mAutoHideControls);
        mCard.animate().cancel();
    }

    private MiniCardSwipe(View card, Callback callback) {
        mCard = card;
        mCallback = callback;
        Context ctx = card.getContext();
        mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        mFlingVelocity = FLING_VELOCITY_DP * card.getResources().getDisplayMetrics().density;

        mGestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!mControlsVisible) {
                    showControls();
                    return true;
                }
                mCallback.onExpand();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleScale();
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mCurrentScale *= detector.getScaleFactor();
                mCurrentScale = Math.max(1.0f, Math.min(1.45f, mCurrentScale));
                mCard.setScaleX(mCurrentScale);
                mCard.setScaleY(mCurrentScale);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                mCurrentScale = (mCurrentScale > 1.18f) ? 1.35f : 1.0f;
                mCard.animate().scaleX(mCurrentScale).scaleY(mCurrentScale)
                        .setDuration(180).setInterpolator(Motion.EMPHASIZED).start();
            }
        });
    }

    private void toggleScale() {
        mCurrentScale = (mCurrentScale > 1.15f) ? 1.0f : 1.35f;
        updatePivotForCorner(mCard.getTranslationX(), mCard.getTranslationY());
        mCard.animate()
                .scaleX(mCurrentScale)
                .scaleY(mCurrentScale)
                .setDuration(220)
                .setInterpolator(Motion.EMPHASIZED)
                .start();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!mCallback.canSwipe()) {
            return false;
        }

        mScaleDetector.onTouchEvent(event);
        if (mScaleDetector.isInProgress()) {
            mDragging = false;
            mIgnoring = true;
            return true;
        }

        mGestureDetector.onTouchEvent(event);

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
                showControls();
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (mIgnoring) {
                    return true;
                }
                trackVelocity(event);
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (!mDragging) {
                    if (Math.hypot(dx, dy) <= mTouchSlop) {
                        return false;
                    }
                    mDragging = true;
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
                    snapToNearestCorner(0f, 0f);
                }
                return mIgnoring;

            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                mIgnoring = false;
                if (mDragging) {
                    mDragging = false;
                    snapToNearestCorner(0f, 0f);
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

        snapToNearestCorner(velocityX, velocityY);
    }

    public void snapToNearestCorner(float velocityX, float velocityY) {
        float minTx = getMinTranslationX();
        float minTy = getMinTranslationY();
        float midX = minTx / 2f;
        float midY = minTy / 2f;

        float targetTx;
        float targetTy;

        if (velocityX < -mFlingVelocity) {
            targetTx = minTx;
        } else if (velocityX > mFlingVelocity) {
            targetTx = 0f;
        } else {
            targetTx = (mCard.getTranslationX() < midX) ? minTx : 0f;
        }

        if (velocityY < -mFlingVelocity) {
            targetTy = minTy;
        } else if (velocityY > mFlingVelocity) {
            targetTy = 0f;
        } else {
            targetTy = (mCard.getTranslationY() < midY) ? minTy : 0f;
        }

        updatePivotForCorner(targetTx, targetTy);

        MiniPlayerBridge.setSavedMiniPosition(targetTx, targetTy);
        mCallback.onPositionChanged(targetTx, targetTy);

        mCard.animate()
                .translationX(targetTx)
                .translationY(targetTy)
                .setDuration(240)
                .setInterpolator(Motion.EMPHASIZED)
                .start();

        scheduleAutoHide();
    }

    private void updatePivotForCorner(float tx, float ty) {
        float minTx = getMinTranslationX();
        float minTy = getMinTranslationY();

        boolean isLeft = Math.abs(tx - minTx) < Math.abs(tx);
        boolean isTop = Math.abs(ty - minTy) < Math.abs(ty);

        float pivotX = isLeft ? 0f : mCard.getWidth();
        float pivotY = isTop ? 0f : mCard.getHeight();

        mCard.setPivotX(pivotX);
        mCard.setPivotY(pivotY);
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
        float density = mCard.getResources().getDisplayMetrics().density;
        float topInset = (TOP_BAR_DP + MARGIN_DP) * density;
        float top = mCard.getTop();
        if (top <= 0) {
            int cardH = mCard.getHeight() > 0 ? mCard.getHeight()
                    : Math.round(102f * density);
            return -(getParentHeight() - cardH - topInset);
        }
        return topInset - top;
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
