package io.github.wechatbubbles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Fixed-size avatar; clips every notification icon instead of retaining full-screen bitmaps. */
final class BubbleView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final Drawable avatar;
    private final int unread;
    private final boolean demo;
    private final float density;

    BubbleView(Context context, Drawable avatar, int unread, boolean demo) {
        super(context);
        this.avatar = avatar;
        this.unread = unread;
        this.demo = demo;
        density = context.getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            float radius = 25f * density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(2.8f * density, 0f, 1.4f * density, 0x45000000);
            canvas.drawCircle(cx, cy, radius + 1.7f * density, paint);
            paint.clearShadowLayer();
            paint.setColor(Color.rgb(13, 159, 104));
            canvas.drawCircle(cx, cy, radius, paint);
            int save = canvas.save();
            clip.reset();
            clip.addCircle(cx, cy, radius - .6f * density, Path.Direction.CW);
            canvas.clipPath(clip);
            if (avatar != null && !demo) {
                avatar.setBounds(Math.round(cx - radius), Math.round(cy - radius),
                        Math.round(cx + radius), Math.round(cy + radius));
                avatar.draw(canvas);
            } else {
                paint.setColor(Color.WHITE);
                bounds.set(cx - radius * .63f, cy - radius * .45f,
                        cx + radius * .63f, cy + radius * .42f);
                canvas.drawRoundRect(bounds, 8 * density, 8 * density, paint);
                Path tail = new Path();
                tail.moveTo(cx - radius * .3f, cy + radius * .3f);
                tail.lineTo(cx - radius * .4f, cy + radius * .62f);
                tail.lineTo(cx + radius * .05f, cy + radius * .35f);
                tail.close();
                canvas.drawPath(tail, paint);
                paint.setColor(Color.rgb(13, 159, 104));
                canvas.drawCircle(cx - radius * .24f, cy - radius * .04f, 2 * density, paint);
                canvas.drawCircle(cx + radius * .24f, cy - radius * .04f, 2 * density, paint);
            }
            canvas.restoreToCount(save);
            // App indicator keeps a sender avatar recognizable as a WeChat notification.
            float appX = cx + radius * .72f, appY = cy + radius * .72f;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(appX, appY, 9f * density, paint);
            paint.setColor(Color.rgb(13, 159, 104));
            canvas.drawCircle(appX, appY, 7.6f * density, paint);
            paint.setColor(Color.WHITE);
            bounds.set(appX - 4.6f * density, appY - 3.5f * density,
                    appX + 4.6f * density, appY + 2.7f * density);
            canvas.drawRoundRect(bounds, 2.5f * density, 2.5f * density, paint);
            if (unread > 0) {
                String number = unread > 9 ? "9+" : Integer.toString(unread);
                float badgeX = cx + radius * .73f, badgeY = cy - radius * .72f;
                paint.setColor(Color.WHITE);
                canvas.drawCircle(badgeX, badgeY, 9.8f * density, paint);
                paint.setColor(Color.rgb(237, 75, 75));
                canvas.drawCircle(badgeX, badgeY, 8.4f * density, paint);
                paint.setColor(Color.WHITE);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                paint.setTextSize(10f * density);
                canvas.drawText(number, badgeX, badgeY - (paint.ascent() + paint.descent()) / 2f, paint);
            }
        } catch (Throwable error) {
            // An app-supplied malformed drawable must never take down the status bar.
            SystemUiHook.logClass("avatar draw", error);
        }
    }

    @Override public boolean performClick() { return super.performClick(); }
}
