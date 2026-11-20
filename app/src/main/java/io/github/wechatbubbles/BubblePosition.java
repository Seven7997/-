package io.github.wechatbubbles;

/** Normalized placement of the top avatar, independent of the number of conversations. */
final class BubblePosition {
    private BubblePosition() {}

    static float fraction(int y, int top, int bottom, int avatarHeight) {
        long travel = Math.max(0L, (long) bottom - top - avatarHeight);
        if (travel == 0) return 0f;
        return Math.max(0f, Math.min(1f, (float) ((long) y - top) / travel));
    }

    static int restore(float fraction, int top, int bottom, int avatarHeight, int railHeight) {
        if (!Float.isFinite(fraction)) fraction = 0f;
        float bounded = Math.max(0f, Math.min(1f, fraction));
        long travel = Math.max(0L, (long) bottom - top - avatarHeight);
        long desired = top + Math.round(bounded * travel);
        long maximum = Math.max((long) top, (long) bottom - railHeight);
        return (int) Math.max(top, Math.min(maximum, desired));
    }

    static int edgeX(boolean right, int screenWidth, int railWidth, int margin) {
        int maximum = Math.max(0, screenWidth - railWidth);
        return Math.max(0, Math.min(maximum, right ? maximum - margin : margin));
    }
}
