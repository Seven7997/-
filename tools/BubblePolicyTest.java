package io.github.wechatbubbles;

public final class BubblePolicyTest {
    private static int checks;
    private static void require(boolean yes, String label) {
        checks++;
        if (!yes) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        require(BubblePolicy.acceptsPackage("com.tencent.mm", "msg"), "WeChat accepted");
        require(!BubblePolicy.acceptsPackage("evil.com.tencent.mm", "msg"), "No substring package match");
        require(!BubblePolicy.acceptsPackage("com.android.systemui", "msg"), "Unrelated package excluded");
        require(BubblePolicy.acceptsPackage(BubblePolicy.MODULE, BubblePolicy.TEST_CHANNEL), "Fixture isolated channel");
        require(!BubblePolicy.acceptsPackage(BubblePolicy.MODULE, "unrelated"), "Own unrelated notifications excluded");
        require(BubblePolicy.clip(null, 40).equals(""), "Null preview handled");
        require(BubblePolicy.clip(" a\0b ", 40).equals("a b"), "Control character trimmed");
        require(BubblePolicy.clip("你好😀世界", 4).equals("你好…"), "No torn surrogate pairs");
        require(BubblePolicy.clip("abc", 0).equals(""), "Zero limit");
        require(BubblePolicy.clip("abcdef", 4).equals("abc…"), "Bounded preview");
        require(BubblePolicy.conversationKey("p", "id", "a", "n").equals("p|s|id"), "Stable shortcut preferred");
        require(BubblePolicy.conversationKey("p", null, "a", "n").equals("p|n|n"), "Notification identity fallback");
        require(!BubblePolicy.conversationKey("p", null, "same name", "n1").equals(
                BubblePolicy.conversationKey("p", null, "same name", "n2")), "Same display names never merge intents");
        require(BubblePolicy.conversationKey("p", "", "", "n").equals("p|n|n"), "Notification fallback");
        require(!BubblePolicy.conversationKey("p", null, "a", "n").equals(
                BubblePolicy.conversationKey("q", null, "a", "n")), "No package identity collision");
        require(BubblePolicy.clamp(-100, 8, 100) == 8, "Clamp leading edge");
        require(BubblePolicy.clamp(1000, 8, 100) == 100, "Clamp trailing edge");
        require(BubblePolicy.clamp(20, 8, 4) == 8, "Small rotated screen safe");
        require(BubblePolicy.forgetOnRemoval(2), "User cancel clears preview");
        require(BubblePolicy.forgetOnRemoval(3), "Clear all clears bubbles");
        require(BubblePolicy.forgetOnRemoval(6), "User stop clears bubbles");
        require(BubblePolicy.forgetOnRemoval(17), "Channel disabled clears bubbles");
        require(BubblePolicy.forgetOnRemoval(21), "Cleared app data clears bubbles");
        require(BubblePolicy.forgetOnRemoval(23), "Lockdown clears bubbles");
        require(!BubblePolicy.forgetOnRemoval(8), "Read notification can keep bubble shortcut in memory");
        require(BubblePolicy.isDuplicate("sig", "sig"), "Repeat delivery deduplicated");
        require(!BubblePolicy.isDuplicate("old", "new"), "New content counted");
        require(!BubblePolicy.isDuplicate(null, "new"), "First content counted");
        String chatA = ChatIdentity.token(BubblePolicy.WECHAT, "test_talker_a");
        String chatB = ChatIdentity.token(BubblePolicy.WECHAT, "test_talker_b");
        require(chatA.matches("[0-9a-f]{64}"), "Only opaque hash leaves the UI observer");
        require(!chatA.equals(chatB), "Different talkers never collapse even with the same display name");
        require(!chatA.equals(ChatIdentity.token(BubblePolicy.MODULE, "test_talker_a")), "Fixture cannot collide with WeChat");
        require(ChatIdentity.token(BubblePolicy.WECHAT, "").isEmpty(), "Unknown chat must never mute a notification");
        java.util.List<String> visible = java.util.List.of(chatA);
        require(BubblePolicy.matchesActiveChat(chatA, visible, 1000, 1500), "Same displayed chat is suppressed");
        require(!BubblePolicy.matchesActiveChat(chatB, visible, 1000, 1500), "Other displayed-name or group chat still bubbles");
        require(!BubblePolicy.matchesActiveChat(chatA, java.util.List.of(), 1000, 1500), "Closing the chat restores its bubble");
        require(!BubblePolicy.matchesActiveChat(chatA, java.util.List.of(chatB), 1000, 1500), "Switching chats unmutes the old one");
        require(BubblePolicy.matchesActiveChat(chatB, java.util.List.of(chatB), 1000, 1500), "Switching chats suppresses the new one");
        require(!BubblePolicy.matchesActiveChat(chatA, visible, 1000, 7001), "Stale UI state cannot lose messages");
        require(!BubblePolicy.matchesActiveChat(chatA, visible, 2000, 1500), "Invalid future UI state is ignored");
        require(!BubblePolicy.matchesActiveChat(null, visible, 1000, 1500), "Unavailable routing stays visible");
        LastOpenedChat memory = new LastOpenedChat();
        memory.opened(chatA, 1000);
        require(!memory.shouldSuppress(chatA, false, 1100), "Pending launch must not mute before a real window exists");
        require(memory.hasConversation(), "Allow short native-window startup without forgetting the click");
        require(memory.shouldSuppress(chatA, true, 1600), "SystemUI-only remembers the clicked chat");
        require(!memory.shouldSuppress(chatB, true, 1700), "SystemUI-only still shows another chat");
        memory.opened(chatB, 2000);
        require(!memory.shouldSuppress(chatA, true, 2100), "Clicking another bubble unmutes the old chat");
        require(memory.shouldSuppress(chatB, true, 2100), "Clicking another bubble replaces the remembered chat");
        require(!memory.shouldSuppress(chatB, false, 2200), "Closed small window cannot suppress a notification");
        require(!memory.hasConversation(), "Window close clears remembered chat");
        require(!memory.shouldSuppress(chatB, true, 2300), "External reopen does not invent a known current chat");
        memory.opened(chatA, 3000);
        require(!memory.observeWindow(false, 6001), "Failed window startup cannot leave a permanent mute");
        memory.opened("", 6500);
        require(!memory.hasConversation(), "Unidentified original notification is not guessed");
        memory.opened(chatA, 7000);
        memory.clear();
        require(!memory.shouldSuppress(chatA, true, 7100), "Lock/disable clear removes remembered suppression");
        System.out.println("PASS: " + checks + " bubble policy checks");
    }
}
