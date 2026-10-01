package com.digitalminds.universe;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * UNIVERSE :: VideoFrame
 * Sizes itself to the video's shape inside the space it is given ("Fit": the whole picture, nothing cut).
 * "Fill" is a scale applied to this frame from outside, so the picture can cover the entire window,
 * camera cutout included. Same idea COMET uses, without loading Media3's interface library.
 */
public class VideoFrame extends FrameLayout {

    private float ratio;   // width / height of the video, 0 until known

    public VideoFrame(Context context) {
        super(context);
    }

    public VideoFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(float r) {
        if (Math.abs(r - ratio) > 0.0005f) {
            ratio = r;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int availW = MeasureSpec.getSize(widthSpec);
        int availH = MeasureSpec.getSize(heightSpec);
        int w = availW;
        int h = availH;
        if (ratio > 0f && availW > 0 && availH > 0) {
            float frameRatio = (float) availW / availH;
            if (ratio > frameRatio) h = Math.round(availW / ratio);
            else w = Math.round(availH * ratio);
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
