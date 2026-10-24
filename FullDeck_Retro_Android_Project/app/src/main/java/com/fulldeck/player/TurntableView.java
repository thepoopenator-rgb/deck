package com.fulldeck.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Analog turntable, drawn natively at screen resolution (no WebView).
 * Platter: 33 1/3 RPM only while playing.
 * Tonearm: rigid rotating assembly around a fixed pivot; tracking angle derives
 * from MediaPlayer position/duration so it ends at the final groove.
 */
public final class TurntableView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private boolean playing;
    private float progress;
    private float spin;
    private long lastFrame;
    private long songId = Long.MIN_VALUE;

    public TurntableView(Context context, AttributeSet attributes) { super(context, attributes);  }

    public void setPlayback(boolean isPlaying, int elapsedMs, int durationMs, long trackId) {
        if (trackId != songId) { songId = trackId; spin = 0; lastFrame = 0; }
        if (playing != isPlaying) lastFrame = 0;
        playing = isPlaying;
        progress = durationMs > 0 ? Math.max(0f,Math.min(1f,elapsedMs / (float)durationMs)) : 0f;
        invalidate();
    }

    private int rgb(String color) { return Color.parseColor(color); }
    private void color(int color) { p.reset(); p.setAntiAlias(true); p.setColor(color); }
    private void fill(Canvas c, float x, float y, float radius, int color) { color(color);c.drawCircle(x,y,radius,p); }
    private void line(Canvas c,float x1,float y1,float x2,float y2,float stroke,int color) {
        color(color);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x1,y1,x2,y2,p);
    }
    private void arc(Canvas c,float cx,float cy,float radius,float start,float sweep,float w,int col) {
        color(col);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w);
        rect.set(cx-radius,cy-radius,cx+radius,cy+radius); c.drawArc(rect,start,sweep,false,p);
    }
    private void text(Canvas c,String s,float x,float y,float size,int ink,boolean bold) {
        color(ink);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(bold?android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD):android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));p.setTextSize(size);c.drawText(s,x,y,p);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        if (playing) {
            if (lastFrame > 0) spin=(spin + Math.min(100L,now-lastFrame)*360f/1800f)%360f;
            lastFrame = now;
        } else lastFrame = 0;
        float w=getWidth(), h=getHeight();
        // Viewport 340 x 256 logical units; scales consistently to narrow screens.
        float scale=Math.min(w/340f,h/256f);
        canvas.save(); canvas.translate((w-340f*scale)/2f,(h-256f*scale)/2f); canvas.scale(scale,scale);
        color(rgb("#28211D"));p.setShader(new LinearGradient(0,0,340,250,rgb("#574334"),rgb("#211E1B"),Shader.TileMode.CLAMP));
        rect.set(1,1,339,255);canvas.drawRoundRect(rect,15,15,p);p.setShader(null);
        color(rgb("#9E7B57"));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);canvas.drawRoundRect(rect,15,15,p);
        fill(canvas,47,30,3,rgb("#F1C98D"));text(canvas,"FULL DECK • ANALOG SYSTEM",175,21,9,rgb("#EAC48E"),true);
        // Turntable platter: soft dark rim and deck-side power LED.
        final float cx=143,cy=136,r=102;
        fill(canvas,cx,cy,r+8,rgb("#120F10"));fill(canvas,cx,cy,r+3,rgb("#867359"));
        fill(canvas,cx,cy,r,rgb("#121214"));
        for(int n=0;n<14;n++) arc(canvas,cx,cy,35+n*4.5f,0,360,0.65f,n%3==0?rgb("#444044"):rgb("#2D2A2E"));
        // Spinning part includes asymmetric highlights, label graphics, center pin.
        canvas.save();canvas.rotate(spin,cx,cy);
        for (int n=0;n<4;n++) arc(canvas,cx,cy,57+n*10,18+n*25,31,2.2f,rgb("#71665C"));
        arc(canvas,cx,cy,88,185,38,2,rgb("#6B5D53"));
        fill(canvas,cx,cy,35,rgb("#B56F46"));
        fill(canvas,cx,cy,29,rgb("#DDB681"));
        color(rgb("#8D452E"));rect.set(cx-28,cy-28,cx+28,cy+28);canvas.drawArc(rect,215,115,true,p);
        line(canvas,cx-27,cy-6,cx+27,cy-6,2,rgb("#7A482E"));
        text(canvas,"FULL",cx,cy-12,11,rgb("#31221C"),true);
        text(canvas,"DECK",cx,cy+6,12,rgb("#31221C"),true);
        text(canvas,"33⅓ RPM",cx,cy+21,7,rgb("#4C2B20"),true);
        fill(canvas,cx,cy,4,rgb("#1D1B1D"));fill(canvas,cx-1,cy-1,1.4f,rgb("#FFF3D4"));
        canvas.restore();
        // Tonearm: stylus path mechanically constrained to one rigid arm from pivot.
        // Pivot = (cx + 1.4 r, cy - 0.8 r). Groove start/end both at distance 1.53r.
        float px=cx+1.40f*r,py=cy-0.80f*r;
        float theta=(float)(Math.toDegrees(Math.atan2(1.36,-.70))+
                 (Math.toDegrees(Math.atan2(.95,-1.20))-Math.toDegrees(Math.atan2(1.36,-.70)))*progress);
        canvas.save();canvas.rotate(theta-117.238f,px,py);
        float tipX=cx+0.70f*r,tipY=cy+0.56f*r;
        line(canvas,px,py,tipX+10,tipY-12,8,rgb("#2A201B"));
        line(canvas,px,py,tipX+10,tipY-12,5,rgb("#D8C5A6"));
        line(canvas,px,py,tipX+10,tipY-12,1.5f,rgb("#FFF0CE"));
        line(canvas,tipX+10,tipY-12,tipX,tipY,8,rgb("#382722"));
        line(canvas,tipX+10,tipY-12,tipX,tipY,4,rgb("#BD8B5A"));
        color(rgb("#C68E53"));rect.set(tipX-8,tipY-7,tipX+6,tipY+6);canvas.drawRoundRect(rect,3,3,p);
        line(canvas,tipX,tipY+3,tipX-2,tipY+11,1.5f,rgb("#FAE9C7"));
        canvas.restore();
        fill(canvas,px,py,13,rgb("#291F1A"));fill(canvas,px,py,10,rgb("#A88D68"));fill(canvas,px-2,py-2,5,rgb("#E8D2AC"));
        fill(canvas,px,py,2,rgb("#5A3A27"));
        // Working indicator. The subtle LED only lights during playback.
        fill(canvas,286,216,5,playing?rgb("#BDF3A7"):rgb("#5D5A4B"));
        text(canvas,playing?"PLAYING":"STANDBY",286,234,8,rgb("#E9C99A"),true);
        canvas.restore();
        if(playing)postInvalidateDelayed(16);
    }
}
