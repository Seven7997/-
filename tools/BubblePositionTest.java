package io.github.wechatbubbles;

public final class BubblePositionTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int top = 100, bottom = 1100, avatar = 66;
        float saved = BubblePosition.fraction(580, top, bottom, avatar);
        check(BubblePosition.restore(saved, top, bottom, avatar, avatar) == 580,
                "Restart restores the same top-avatar position");
        check(BubblePosition.restore(saved, top, bottom, avatar, 246) == 580,
                "Adding conversations does not move an in-bounds rail");
        float low = BubblePosition.fraction(1000, top, bottom, avatar);
        check(BubblePosition.restore(low, top, bottom, avatar, 246) == 854,
                "Extra conversations cannot go below usable screen");
        check(BubblePosition.restore(low, top, bottom, avatar, avatar) == 1000,
                "Removing extra conversations recovers the saved anchor");
        int rotated = BubblePosition.restore(saved, 50, 650, avatar, avatar);
        check(rotated >= 50 && rotated <= 584,
                "Rotation maps a normalized anchor into the new usable area");
        check(BubblePosition.fraction(0, top, bottom, avatar) == 0f, "Above screen is bounded");
        check(BubblePosition.fraction(1200, top, bottom, avatar) == 1f, "Below screen is bounded");
        check(BubblePosition.restore(Float.NaN, top, bottom, avatar, avatar) == top,
                "Invalid saved value is safe");
        check(BubblePosition.restore(1f, top, 120, avatar, 246) == top,
                "A tiny usable height cannot create a negative position");
        check(BubblePosition.edgeX(false, 1440, 280, 8) == 8, "Left edge restores");
        check(BubblePosition.edgeX(true, 1440, 280, 8) == 1152, "Right edge restores");
        check(BubblePosition.edgeX(true, 100, 280, 8) == 0, "Tiny width is safe");
        System.out.println("PASS: " + checks + " position checks");
    }
}
