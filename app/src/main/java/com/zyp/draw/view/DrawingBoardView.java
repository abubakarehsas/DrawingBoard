package com.zyp.draw.view;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImageSketchFilter;

/**
 * Created by zhangyiipeng on 2018/7/6.
 */

public class DrawingBoardView extends FrameLayout implements View.OnTouchListener {

    public static final String TAG = "DrawingBoardView";

    public static final int STROKE = 0;
    public static final int ERASER = 1;
    public static final int DEFAULT_STROKE_SIZE = 7;
    public static final int DEFAULT_ERASER_SIZE = 50;
    //画布最大缩放比率
    private static float MAX_ZOOM_FACTOR = 10f;
    //画布最小缩放比率
    private static float MIN_ZOOM_FACTOR = 0.8f;
    //touch move canvas rate
    private static float MOVE_CANVAS_RATE = 8.0f;
    //预览view相对canvas的缩放比率
    private static float PREVIEW_SCALE_RATE = 4.0f;
    //取色view相对canvas的缩放比率
    private final float PICK_COLOR_SCALE_RATE = 3.0f;
    //蒙层透明度
    public static final int COVER_ALPHA = 100;
    private List<DrawPathData> allDrawPathDatas = new ArrayList<>();
    private List<DrawPathData> undoDrawPathDatas = new LinkedList<>();
    private Paint mPaint;
    private Paint mDrawPaint;
    private Path mDrawPath;
    private Bitmap mDrawBitmap;
    private Canvas mDrawCanvas;
    private Rect mClipBoundsRect;
    private Paint mCoverPaint;

    private Bitmap mBottomBitmap;
    private GPUImage mGpuImage;
    //原始bitmap
    private Bitmap mSourceBitmap;
    //素描bitmap
    private Bitmap mSketchBitmap;

    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mGestureDetector;
    private TouchColorPickView mColorPickView;
    private PreviewRegionView mPreviewRegionView;
    private ValueAnimator mZoomCanvasAnimator;

    private int strokeColor = Color.BLACK;
    //是否在绘制预览view
    private boolean isPreviewRegion = false;
    //是否绘制Path
    private boolean isDrawPath = false;
    private ObjectAnimator mPreviewViewHideAnimator;
    private ObjectAnimator mPreviewViewShowAnimator;

    public DrawingBoardView(@NonNull Context context) {
        this(context, null);
    }

    public DrawingBoardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DrawingBoardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setOnTouchListener(this);
        //默认画笔
        mPaint = new Paint();
        //设置画笔抗锯齿和抗抖动
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);
        mPaint.setStyle(Paint.Style.STROKE); //设置画笔为实心
        mPaint.setStrokeCap(Paint.Cap.ROUND); //设置画笔笔触为圆形
        mPaint.setStrokeJoin(Paint.Join.ROUND); //设置画笔接触点为圆形
        mPaint.setStrokeWidth(DEFAULT_STROKE_SIZE);  //画笔的宽度
        mPaint.setColor(strokeColor); //画笔的颜色
        //绘制画笔
        mDrawPaint = new Paint(mPaint);
        //绘制路径
        mDrawPath = new Path();
        //蒙层画笔
        mCoverPaint = new Paint();
        mCoverPaint.setColor(Color.WHITE);
        mCoverPaint.setAlpha(COVER_ALPHA);
        //缩放后剪切边距
        mClipBoundsRect = new Rect();

        mScaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureListener());
        mGestureDetector = new GestureDetector(getContext(), new GestureListener());

        getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {

                    @SuppressLint("NewApi")
                    @SuppressWarnings("deprecation")
                    @Override
                    public void onGlobalLayout() {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                            getViewTreeObserver()
                                    .removeGlobalOnLayoutListener(this);
                        } else {
                            getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);
                        }
                        initPreviewRegionView();

                        initColorPickView();
                    }

                });

    }


    private int preMotionEvent = -1;

    private float preMoveX;
    private float preMoveY;

    private float touchX;
    private float touchY;

    private float moveX;
    private float moveY;

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        mScaleGestureDetector.onTouchEvent(event);
        if (mGestureDetector.onTouchEvent(event)) {
            return true;
        }

        touchX = event.getX() / mZoomFactor + mClipBoundsRect.left;
        touchY = event.getY() / mZoomFactor + mClipBoundsRect.top;

        //如果多点触摸return
        if (event.getPointerCount() > 1) {
            preMotionEvent = -1;
            return true;
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                preMotionEvent = MotionEvent.ACTION_DOWN;

                mDrawPath.reset();

                preMoveX = touchX;
                preMoveY = touchY;
                mDrawPath.moveTo(touchX, touchY);
                colorPick(touchX, touchY, event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                if ((preMotionEvent == MotionEvent.ACTION_DOWN ||
                        preMotionEvent == MotionEvent.ACTION_MOVE)) {
                    preMotionEvent = MotionEvent.ACTION_MOVE;
                    moveX = touchX - preMoveX;
                    moveY = touchY - preMoveY;

                    colorPick(touchX, touchY, event.getX(), event.getY());

                    if (Math.sqrt(moveX * moveX + moveY * moveY) > 5) {
                        if (!isLongPress) {
                            mDrawPath.quadTo(preMoveX, preMoveY, (touchX + preMoveX) / 2, (touchY + preMoveY) / 2);
                            isDrawPathDatas = false;
                            isDrawPath = true;
                            invalidate();
                        } else {
                            isDrawPath = false;
                        }

                        preMoveX = touchX;
                        preMoveY = touchY;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                preMotionEvent = MotionEvent.ACTION_UP;

                if (isLongPress) {
                    isLongPress = false;
                    hideColorPickView();
                    invalidate();

                }
                Log.d(TAG, "isDrawPath: " + isDrawPath);
                if (isDrawPath) {
                    isDrawPath = false;
                    undoDrawPathDatas.clear();
                    isRedo = false;
                    allDrawPathDatas.add(new DrawPathData(new Path(mDrawPath), mDrawPaint));
                    isUndo = true;
                    if (undoRedoListener != null) undoRedoListener.onUndoRedo(isUndo, isRedo);
                }
                break;
        }
        return true;
    }

    private boolean isLongPress;

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(final MotionEvent event) {
            isDrawPathDatas = false;
            mZoomCenterX = event.getX() / mZoomFactor + mClipBoundsRect.left;
            mZoomCenterY = event.getY() / mZoomFactor + mClipBoundsRect.top;
            zoomUpCanvas();
            return true;
        }

        public void onLongPress(MotionEvent e) {
            isLongPress = true;
            invalidate();
        }
    }


    private float mZoomFactor = 1.0f;
    private float mZoomCenterX = -1.0f;
    private float mZoomCenterY = -1.0f;

    private class ScaleGestureListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            isDrawPathDatas = false;
            isPreviewRegion = false;

            float scaleFactor = detector.getScaleFactor();
            Log.e(TAG, "onScale :  " + scaleFactor);
            mZoomFactor *= scaleFactor;
            Log.e(TAG, "mZoomFactor :  " + mZoomFactor);
            mZoomFactor = Math.max(MIN_ZOOM_FACTOR, Math.min(mZoomFactor, MAX_ZOOM_FACTOR));
            mZoomFactor = mZoomFactor > MAX_ZOOM_FACTOR ? MAX_ZOOM_FACTOR : mZoomFactor < MIN_ZOOM_FACTOR ? MIN_ZOOM_FACTOR : mZoomFactor;

            mZoomCenterX = detector.getFocusX() / mZoomFactor + mClipBoundsRect.left;
            mZoomCenterY = detector.getFocusY() / mZoomFactor + mClipBoundsRect.top;

            Log.w(TAG, "mZoomCenterX:" + mZoomCenterX + " , mZoomCenterY:" + mZoomCenterY);

            if (mZoomFactor > 1.0f) {
                showPreviewRegionView();
            } else {
                hidePreviewRegionView();
            }

            invalidate();

            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            if (mZoomFactor < 1.0f) {
                mZoomFactor = 1.0f;
            }
            invalidate();
        }
    }


    private float ratioCenterX;
    private float ratioCenterY;
    @Override
    public void onDraw(Canvas canvas) {

        if (mDrawBitmap == null || isDrawPathDatas) {
            //创建绘制bitmap
            mDrawBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            //创建白色的bitmap，作为绘制的最底层背景色
            mBottomBitmap = mDrawBitmap.copy(Bitmap.Config.ARGB_8888, true);
            mBottomBitmap.eraseColor(Color.WHITE);
            //绘制Canvas
            mDrawCanvas = new Canvas(mDrawBitmap);

            //计算初始的中心点坐标
            calculateXY();
        }

        canvas.save();
        //画布缩放
        canvas.scale(mZoomFactor, mZoomFactor, mZoomCenterX, mZoomCenterY);

        Log.d(TAG, "isDrawPathDatas:" + isDrawPathDatas + " , allDrawPathDatas:" + allDrawPathDatas.size());
        if (isDrawPathDatas) {
            for (DrawPathData drawPathData : allDrawPathDatas) {
                mDrawCanvas.drawPath(drawPathData.getPath(), drawPathData.getPaint());
            }
        } else {
            mDrawCanvas.drawPath(mDrawPath, mDrawPaint);
        }

        // canvas 放大或縮小後，drawRect會產生偏移,你要计算缩放比例，getClipBounds能得到两个顶点的坐标, 根据两个顶点的坐标的比例来确定坐标
        canvas.getClipBounds(mClipBoundsRect);
        Log.d(TAG, "mZoomCenterX:" + mZoomCenterX + " , mZoomCenterY:" + mZoomCenterY);
        Log.d(TAG, "centerX:" + mClipBoundsRect.centerX() + " , centerY:" + mClipBoundsRect.centerY());

        //mClipBoundsRect.centerX()与mZoomCenterX成一定比率，这个对计算位置很关键
        ratioCenterX = mZoomCenterX / mClipBoundsRect.centerX();
        ratioCenterY = mZoomCenterY / mClipBoundsRect.centerY();
        Log.d(TAG, "ratioCenterX : " + ratioCenterX + " , ratioCenterY :" + ratioCenterY);

        canvas.drawBitmap(mBottomBitmap, 0, 0, null);//底色
        if (bgBitmap != null) {
            if (isDrawSketch) {
                mDrawPaint.setAlpha(alpha);
                canvas.drawBitmap(mSketchBitmap, bgBitmapTranslateX, bgBitmapTranslateY, null);
                canvas.drawBitmap(mSourceBitmap, bgBitmapTranslateX, bgBitmapTranslateY, mDrawPaint);
            } else {
                mDrawPaint.setAlpha(alpha);
                canvas.drawBitmap(mSourceBitmap, bgBitmapTranslateX, bgBitmapTranslateY, null);
                canvas.drawBitmap(mSketchBitmap, bgBitmapTranslateX, bgBitmapTranslateY, mDrawPaint);
            }
            mDrawPaint.setAlpha(255);
        }

        if (!isLongPress) {
            canvas.drawRect(mClipBoundsRect, mCoverPaint);//蒙层
            canvas.drawBitmap(mDrawBitmap, 0, 0, null);//绘画
        }

        canvas.restore();

        if (mPreviewRegionView != null && !isPreviewRegion) {
            //绘制右上角的预览区域
            mPreviewRegionView.drawPreviewRegion(mDrawBitmap, bgBitmap, bgBitmapTranslateX, bgBitmapTranslateY, mClipBoundsRect, 4.0f);
        }
        super.onDraw(canvas);
    }

    private int bgBitmapTranslateX = 0;
    private int bgBitmapTranslateY = 0;
    private int drawBitmapTranslateX = 0;
    private int drawBitmapTranslateY = 0;
    private void calculateXY() {
        if (!isDrawPathDatas) {
            mClipBoundsRect = new Rect(0, 0, getWidth(), getHeight());
            mZoomCenterX = mClipBoundsRect.centerX();
            mZoomCenterY = mClipBoundsRect.centerY();
        }

        if (mDrawBitmap.getWidth() < mDrawBitmap.getHeight()) {
            drawBitmapTranslateX = mClipBoundsRect.centerX() - (mDrawBitmap.getWidth() / 2);
        } else {
            drawBitmapTranslateY = mClipBoundsRect.centerY() - (mDrawBitmap.getHeight() / 2);
        }
    }


    public interface OnPickColorListener {
        void onPickColor(int color);
    }

    private OnPickColorListener mPickColorListener;
    public void setPickColorListener(OnPickColorListener listener) {
        this.mPickColorListener = listener;
    }

    public interface OnUndoRedoListener {
        void onUndoRedo(boolean isUndo, boolean isRedo);
    }

    private OnUndoRedoListener undoRedoListener;
    public void setOnUndoRedoListener(OnUndoRedoListener listener) {
        this.undoRedoListener = listener;
    }

    /**
     * 初始化预览view
     */
    private void initPreviewRegionView() {
        mPreviewRegionView = new PreviewRegionView(getContext());
        FrameLayout.LayoutParams layoutParams = new LayoutParams(getWidth() / 4, getHeight() / 4,
                Gravity.TOP | Gravity.END);
        mPreviewRegionView.setLayoutParams(layoutParams);
        mPreviewRegionView.setOnZoomRegionListener(new PreviewRegionView.OnZoomRegionListener() {
            @Override
            public void onZoomRegionMoved(Rect newRect) {
                isPreviewRegion = true;

                mZoomCenterX = newRect.centerX() * 4 * ratioCenterX;
                mZoomCenterY = newRect.centerY() * 4 * ratioCenterY;
                invalidate();
            }
        });
        addView(mPreviewRegionView);
        hidePreviewRegionView();
    }

    /**
     * 显示预览view
     */
    private synchronized void showPreviewRegionView() {
        if (mPreviewViewShowAnimator == null){
            mPreviewViewShowAnimator = ObjectAnimator.ofFloat(mPreviewRegionView, "alpha", 0f, 1f);
            mPreviewViewShowAnimator.setDuration(500);
        }
        if (mPreviewRegionView != null && mPreviewRegionView.getVisibility() == INVISIBLE) {
            mPreviewRegionView.setVisibility(VISIBLE);
            mPreviewViewShowAnimator.start();
        }
    }

    /**
     * 隐藏预览view
     */
    private synchronized void hidePreviewRegionView() {
        mZoomCenterX = mClipBoundsRect.centerX();
        mZoomCenterY = mClipBoundsRect.centerY();
        if (mPreviewViewHideAnimator == null) {
            mPreviewViewHideAnimator = ObjectAnimator.ofFloat(mPreviewRegionView, "alpha", 1f, 0f);
            mPreviewViewHideAnimator.setDuration(500);
            mPreviewViewHideAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mPreviewRegionView.setVisibility(INVISIBLE);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }
            });
        }
        if (mPreviewRegionView != null && mPreviewRegionView.getVisibility() == VISIBLE) {
            mPreviewViewHideAnimator.start();
        }
    }

    /**
     * 初始化取色view
     */
    private void initColorPickView() {
        mColorPickView = new TouchColorPickView(getContext());
        FrameLayout.LayoutParams layoutParams = new LayoutParams((int) (getWidth() / PICK_COLOR_SCALE_RATE), (int) (getWidth() / PICK_COLOR_SCALE_RATE),
                Gravity.TOP | Gravity.LEFT);
        mColorPickView.setLayoutParams(layoutParams);
        mColorPickView.setPickBitmapColor(strokeColor, null, 1);
        addView(mColorPickView);
        mColorPickView.setVisibility(GONE);
    }

    /**
     * 显示取色view
     * @param eventX 屏幕实际触摸点x
     * @param eventY 屏幕实际触摸点y
     * @param pickBitmapWidth 取色bitmap width
     * @param pickBitmapHeight 取色bitmap height
     * @param pickBitmap 取色bitmap
     */
    private void showColorPickView(float eventX, float eventY, int pickBitmapWidth, int pickBitmapHeight, Bitmap pickBitmap) {
        // ColorPickView跟随手指移动，位置在手指的正上方，若想恰好在手指触摸位置的下方，可以把 *1.5f 改为 /2.0f
        mColorPickView.setTranslationX(eventX - pickBitmapWidth / 2.0f);
        mColorPickView.setTranslationY(eventY - pickBitmapHeight * 1.5f);

        if (mColorPickView != null && mColorPickView.getVisibility() == GONE) {
            mColorPickView.setVisibility(VISIBLE);
        }
        mColorPickView.setPickBitmapColor(strokeColor, pickBitmap, mZoomFactor);
        mDrawPaint.setColor(strokeColor);
        if (mPickColorListener != null) {
            mPickColorListener.onPickColor(strokeColor);
        }
    }

    /**
     * 隐藏取色view
     */
    private void hideColorPickView() {
        if (mColorPickView != null && mColorPickView.getVisibility() == VISIBLE) {
            mColorPickView.setVisibility(GONE);
        }
    }

    /**
     * 获取触摸点颜色及bitmap
     * @param touchX
     * @param touchY
     * @param eventX
     * @param eventY
     */
    private void colorPick(float touchX, float touchY, float eventX, float eventY) {
        if (isLongPress && bgBitmap != null) {
            float x = touchX - bgBitmapTranslateX;
            float y = touchY - bgBitmapTranslateY;
            if (x > 0 && x < bgBitmap.getWidth() && y > 0 && y < bgBitmap.getHeight()) {
                int color = bgBitmap.getPixel((int) x, (int) y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int a = Color.alpha(color);
                Log.d(TAG, "a=" + a + ",r=" + r + ",g=" + g + ",b=" + b);

                strokeColor = Color.argb(a, r, g, b);

                int[] pixels = new int[bgBitmap.getWidth() * bgBitmap.getHeight()];
                int pickBitmapWidth = (int) (getWidth() / PICK_COLOR_SCALE_RATE);
                int pickBitmapHeight = pickBitmapWidth;
                int bX = (int) x - pickBitmapWidth / 2;
                int bY = (int) y - pickBitmapHeight / 2;
                Bitmap pickBitmap = null;
                Log.d("colorPick", "bX:" + bX + "bY:" + bY + ",pickBitmapWidth:" + pickBitmapWidth + ",pickBitmapHeight:" + pickBitmapHeight);
                Log.d("colorPick", "bgBitmap.getWidth()" + bgBitmap.getWidth() + ",bgBitmap.getHeight():" + bgBitmap.getHeight());
                if (bX >= 0 && bY >= 0) {
                    if (bX + pickBitmapWidth <= bgBitmap.getWidth() && bY + pickBitmapHeight <= bgBitmap.getHeight()) {
                        Log.d("colorPick", "pick color sucess");
                        bgBitmap.getPixels(pixels, 0, bgBitmap.getWidth(), bX, bY, pickBitmapWidth, pickBitmapHeight);
                        pickBitmap = Bitmap.createBitmap(pixels, 0, bgBitmap.getWidth(), bgBitmap.getWidth(), bgBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    }
                }
                Log.d("TouchXY", "x:" + x + ", y:" + y);
                showColorPickView(eventX, eventY, pickBitmapWidth, pickBitmapHeight, pickBitmap);
            }
        }
    }

    /**
     * 放大画布
     *
     */
    public void zoomUpCanvas() {
        zoomCanvas(true, 1.5f);
    }

    /**
     * 缩小画布
     *
     */
    public void zoomDownCanvas() {
        zoomCanvas(false, 1.5f);
    }


    /**
     * 快速缩放画布
     *
     */
    public void zoomUpQuickCanvas() {
        zoomCanvas(true, MAX_ZOOM_FACTOR);
    }
    public void zoomDownQuickCanvas() {
        zoomCanvas(false, MAX_ZOOM_FACTOR);
    }
    /**
     * 快速缩放画布
     *
     * @param isZoomUp
     */
    public void zoomQuickCanvas(boolean isZoomUp) {
        zoomCanvas(isZoomUp, MAX_ZOOM_FACTOR);
    }

    /**
     * 缩放画布
     * @param isZoomUp
     * @param scaleFactor
     */
    public void zoomCanvas(boolean isZoomUp, float scaleFactor) {
        float startZoomFactor = mZoomFactor;
        if (isZoomUp) {
            mZoomFactor *= scaleFactor;
        } else {
            mZoomFactor /= scaleFactor;
        }
        mZoomFactor = Math.max(MIN_ZOOM_FACTOR, Math.min(mZoomFactor, MAX_ZOOM_FACTOR));
        mZoomFactor = mZoomFactor > MAX_ZOOM_FACTOR ? MAX_ZOOM_FACTOR : mZoomFactor < 1.0f ? 1.0f : mZoomFactor;

        Log.d(TAG, "mZoomCenterX:" + mZoomCenterX + " , mZoomCenterY:" + mZoomCenterY);

        zoomCanvasAnim(startZoomFactor, mZoomFactor);
    }

    private void zoomCanvasAnim(float startZoomFactor, float endZoomFactor) {
        if (mZoomCanvasAnimator == null) {
            mZoomCanvasAnimator = ValueAnimator.ofFloat(startZoomFactor, endZoomFactor);
            mZoomCanvasAnimator.setDuration(300);
            mZoomCanvasAnimator.setInterpolator(new LinearInterpolator());
            mZoomCanvasAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float value = (Float) animation.getAnimatedValue();
                    mZoomFactor = value;
                    mPreviewRegionView.moveDestView(mClipBoundsRect, PREVIEW_SCALE_RATE);

                    invalidate();

                    if (mZoomFactor > 1.0f) {
                        showPreviewRegionView();
                    } else {
                        hidePreviewRegionView();
                    }

                }
            });
        }
        mZoomCanvasAnimator.setFloatValues(startZoomFactor, mZoomFactor);
        mZoomCanvasAnimator.start();
    }

    /**
     * 移动画布
     * @param mx
     * @param my
     */
    public void moveCanvas(float mx, float my) {
        isDrawPathDatas = false;
        //移动canvas时需要reset path ，因为 invalidate()会绘制最后一笔path
        mDrawPath.reset();

        if (mZoomFactor > 2) {
            mZoomCenterX += MOVE_CANVAS_RATE * mx / mZoomFactor;//移动速率和缩放比率相关
            mZoomCenterY += MOVE_CANVAS_RATE * my / mZoomFactor;
        } else {
            mZoomCenterX += MOVE_CANVAS_RATE * mx;
            mZoomCenterY += MOVE_CANVAS_RATE * my;
        }
        mPreviewRegionView.moveDestView(mClipBoundsRect, PREVIEW_SCALE_RATE);

        invalidate();

        Log.d(TAG, "left : " + mClipBoundsRect.left + " ,top : " + mClipBoundsRect.top + " ,right : " + mClipBoundsRect.right + " ,bottom : " + mClipBoundsRect.bottom);

    }

    private boolean isUndo;
    private boolean isRedo;
    private boolean isDrawPathDatas;

    /**
     * 撤销
     */
    public void undo() {
        if (allDrawPathDatas.size() > 0) {
            undoDrawPathDatas.add(allDrawPathDatas.remove(allDrawPathDatas.size() - 1));
            isRedo = true;
            if (allDrawPathDatas.size() > 0) {
                isUndo = true;
            } else {
                isUndo = false;
            }
            invalidate();
            isDrawPathDatas = true;
        } else {
            isUndo = false;
            isDrawPathDatas = false;
        }
        if (undoRedoListener != null) undoRedoListener.onUndoRedo(isUndo, isRedo);
    }

    /**
     * 恢复
     */
    public void redo() {
        if (undoDrawPathDatas.size() > 0) {
            isUndo = true;
            allDrawPathDatas.add(undoDrawPathDatas.remove(undoDrawPathDatas.size() - 1));
            if (undoDrawPathDatas.size() > 0) {
                isRedo = true;
            } else {
                isRedo = false;
            }
            invalidate();
            isDrawPathDatas = true;
        } else {
            isRedo = false;
            isDrawPathDatas = false;
        }
        if (undoRedoListener != null) undoRedoListener.onUndoRedo(isUndo, isRedo);
    }

    /**
     * 擦除
     */
    public void erase() {
        allDrawPathDatas.clear();
        undoDrawPathDatas.clear();
        // 先判断是否已经回收
        if (mDrawBitmap != null && !mDrawBitmap.isRecycled()) {
            // 回收并且置为null
            mDrawBitmap.recycle();
            mDrawBitmap = null;
        }
        if (bgBitmap != null && !bgBitmap.isRecycled()) {
            // 回收并且置为null
            bgBitmap.recycle();
            bgBitmap = null;
        }
        mDrawPaint = null;
        mDrawPaint = new Paint(mPaint);
        mDrawPath.reset();
        mDrawCanvas = null;
        isDrawPathDatas = false;
        isRedo = false;
        isUndo = false;
        if (undoRedoListener != null) undoRedoListener.onUndoRedo(isUndo, isRedo);
        System.gc();
        invalidate();
    }


    private boolean isDrawSketch;
    /**
     * 绘制素描
     */
    public void drawSketch() {
        if (bgBitmap != null && !isDrawSketch) {
            isPreviewRegion = false;
            isDrawSketch = true;
            bgBitmap = mSketchBitmap;
            sketchSwitchAnim();
        }
    }

    /**
     * 绘制颜色
     */
    public void drawColours() {
        if (bgBitmap != null && isDrawSketch) {
            isPreviewRegion = false;
            bgBitmap = mSourceBitmap;
            isDrawSketch = false;
            sketchSwitchAnim();
        }
    }

    /**
     * 素描与原画切换动画
     */
    private void sketchSwitchAnim() {
        sketchSwitchAnim(500);
    }

    /**
     * 素描与原画切换动画
     *
     * @param duration
     */
    private int alpha = 0;
    private void sketchSwitchAnim(long duration) {
        ValueAnimator animator = ValueAnimator.ofInt(255, 0);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                alpha = (int) animation.getAnimatedValue();
                Log.i("AAA", "alpha:" + alpha);
                invalidate();
            }
        });
        animator.start();
    }

    private Bitmap bgBitmap;
    public void setDrawBgBitmap(Bitmap bitmap) {
        erase();

        float scaleRatio = 1.0f;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float screenRatio = 1.0f;
        float imgRatio = (float) height / (float) width;
        float drawRatio = (float) getHeight() / (float) getWidth();
        if (imgRatio >= screenRatio && drawRatio < imgRatio) {
            //高度大于屏幕，以高为准
            scaleRatio = (float) getHeight() / (float) height;
        } else {
            scaleRatio = (float) getWidth() / (float) width;
        }

        Matrix matrix = new Matrix();
        matrix.postScale(scaleRatio, scaleRatio);
        mSourceBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);

        if (mGpuImage == null) {
            mGpuImage = new GPUImage(getContext());
            mGpuImage.setFilter(new GPUImageSketchFilter());
        }

        mSketchBitmap = mGpuImage.getBitmapWithFilterApplied(mSourceBitmap);

        //默认素描
        bgBitmap = mSketchBitmap;

        isDrawSketch = true;

        mZoomFactor = 1;

        hidePreviewRegionView();

        mClipBoundsRect = new Rect(0, 0, getWidth(), getHeight());
        if (bgBitmap.getWidth() < bgBitmap.getHeight() && drawRatio < imgRatio) {
            bgBitmapTranslateX = mClipBoundsRect.centerX() - (bgBitmap.getWidth() / 2);
            bgBitmapTranslateY = 0;
        } else {
            bgBitmapTranslateX = 0;
            bgBitmapTranslateY = mClipBoundsRect.centerY() - (bgBitmap.getHeight() / 2);
        }

        sketchSwitchAnim(1500);
    }

    private int strokeSize = DEFAULT_STROKE_SIZE;
    private int eraserSize = DEFAULT_ERASER_SIZE;
    public void setSize(int size, int eraserOrStroke) {
        switch (eraserOrStroke) {
            case ERASER:
                eraserSize = size;
                mDrawPaint.setColor(Color.WHITE);
                mDrawPaint.setStrokeWidth(eraserSize);
                break;
            case STROKE:
                strokeSize = size;
                mDrawPaint.setColor(strokeColor);
                mDrawPaint.setStrokeWidth(strokeSize);
                break;
        }
    }

    /**
     * 设置画笔颜色
     * @param color
     */
    public void setStrokeColor(int color) {
        this.strokeColor = color;
        mDrawPaint.setColor(color);
    }

    /**
     * 获取当前画笔颜色
     * @return
     */
    public int getStrokeColor() {
        return strokeColor;
    }

    public List<DrawPathData> getPaths() {
        return allDrawPathDatas;
    }

    public Bitmap getDrawBitmap() {
        return mDrawBitmap;
    }

}
