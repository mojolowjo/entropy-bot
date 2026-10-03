package io.github.mojolowjo.entropybot.commands;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a PM in a chat line (B7a, the bridge's onChatReceived). Servers with No Chat Reports send whispers as
 * plain system text with no sender id, so the name comes from the text "X whispers to you: ..."; only the server
 * can write such a line. When the line does carry a verified sender, the name in the text must match it.
 */
public final class ChatParse {
    private ChatParse() {}

    public record Pm(String from, String body, boolean verified) {}

    private static final Pattern INCOMING_TAIL = Pattern.compile("whispers to you: (.*)$");
    private static final Pattern WHISPER = Pattern.compile("^(\\w{3,16}) whispers to you: (.*)$");
    private static final Pattern ARROW = Pattern.compile("^\\[(\\w{3,16}) ?(?:->|→) ?(?:me|you|\\w{3,16})\\] (.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("^From (\\w{3,16}): (.*)$");

    /**
     * @param text          the line as shown
     * @param chatType      the bound chat type's id ("minecraft:msg_command_incoming", "minecraft:chat"), or null
     * @param uuidSender    the sender's name looked up from the message's player id, or null
     * @param signedContent the signed body of a player message, or null
     * @param self          the bot's own name
     * @param publicPrefix  "!" to take "!come" from public chat, or empty
     */
    public static Pm parse(String text, String chatType, String uuidSender, String signedContent, String self, String publicPrefix) {
        String sender = uuidSender, body = null;
        if ("minecraft:msg_command_incoming".equals(chatType) && sender != null) {
            body = signedContent;
            if (body == null) {
                Matcher m = INCOMING_TAIL.matcher(text);
                if (m.find()) body = m.group(1);
            }
        } else {
            Matcher m = null;
            for (Pattern p : new Pattern[]{WHISPER, ARROW, FROM}) {
                Matcher t = p.matcher(text);
                if (t.find()) {
                    m = t;
                    break;
                }
            }
            if (m != null && (sender == null || sender.equals(m.group(1))) && !m.group(1).equals(self)) {
                sender = m.group(1);
                body = m.group(2);
            } else if (sender != null && publicPrefix != null && !publicPrefix.isEmpty() && "minecraft:chat".equals(chatType)) {
                if (signedContent != null && signedContent.startsWith(publicPrefix)) body = signedContent.substring(publicPrefix.length());
            }
        }
        if (body == null || sender == null || sender.equals(self)) return null;
        return new Pm(sender, body, uuidSender != null && uuidSender.equals(sender));
    }
}
