package com.verityai.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises messages that are only a greeting, thanks, goodbye or "who are
 * you", and answers them directly.
 *
 * <p>Without this, "hello" would go through document retrieval, find nothing
 * relevant, and come back as "Insufficient evidence to answer." Handling it
 * here is friendlier, free (no model call) and deterministic. A message that
 * mixes a greeting with a real question ("hi, summarise page 2") is never
 * matched, so it still goes through the evidence-grounded path.</p>
 */
final class SmallTalk {

    /** Stored on the assistant message so these replies are easy to tell apart. */
    static final String MODEL_NAME = "verityai-smalltalk";

    private static final int MAX_WORDS = 6;

    /** Words that only address the assistant and carry no meaning of their own. */
    private static final Set<String> ADDRESS_WORDS =
        Set.of("verityai", "verity", "ai", "there", "buddy", "friend");

    /** A leading entry here is dropped by {@link #normalise}, so "hello hello" and "hi, how are you" match too. */
    private static final Set<String> GREETINGS = Set.of(
        "hello", "hi", "hii", "hey", "hiya", "heya", "yo", "hola", "namaste", "greetings",
        "sup", "good morning", "good afternoon", "good evening");

    private static final Set<String> HOW_ARE_YOU = Set.of(
        "how are you", "how are you doing", "how r u", "how do you do", "hows it going",
        "how is it going", "whats up", "what is up", "wassup");

    private static final Set<String> IDENTITY = Set.of(
        "who are you", "what are you", "what is your name", "whats your name", "your name",
        "introduce yourself", "tell me about yourself", "who is this", "who made you",
        "are you a bot", "are you real");

    private static final Set<String> HELP = Set.of(
        "help", "help me", "what can you do", "what do you do", "how can you help",
        "how can you help me", "how does this work", "how do i use this", "how to use",
        "what can i ask", "what can i ask you", "what should i ask", "can you help me",
        "can you help", "need help", "i need help");

    private static final Set<String> THANKS = Set.of(
        "thanks", "thank you", "thankyou", "thx", "ty", "thanks a lot", "thank you so much",
        "thanks so much", "many thanks", "much appreciated", "appreciate it", "great thanks",
        "ok thanks", "okay thanks", "cool thanks", "thank u");

    private static final Set<String> GOODBYE = Set.of(
        "bye", "goodbye", "bye bye", "see you", "see you later", "see ya", "cya", "good night",
        "take care", "talk later", "ttyl");

    private static final Set<String> ACKNOWLEDGEMENTS = Set.of(
        "ok", "okay", "k", "cool", "nice", "great", "awesome", "got it", "alright",
        "sounds good", "perfect", "fine", "sure");

    private static final String INTRO =
        "I'm **VerityAI**, your document research assistant. I read the PDFs you upload and "
            + "explain what's inside in plain words, with a source for every point so you can "
            + "check it yourself.";

    private static final String WAYS_TO_START = """


        Here are a few things you can ask me:
        - **A question** about your document, like "What is this report about?"
        - **A summary** of the key points, or just one section
        - **A comparison** of two documents
        - **A simple explanation** of a term or number you don't understand

        No documents yet? Upload a PDF and ask away.""";

    private SmallTalk() {}

    /** The reply for a pure small-talk message, or empty if it should be answered from documents. */
    static Optional<String> replyFor(String message) {
        String text = normalise(message);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (GREETINGS.contains(text)) {
            return Optional.of("Hello! 👋 " + INTRO + "\n\n**How can I help you today?**" + WAYS_TO_START);
        }
        if (HOW_ARE_YOU.contains(text)) {
            return Optional.of("Hi! I'm doing well, thanks for asking. 😊 " + INTRO
                + "\n\n**What would you like to explore today?**" + WAYS_TO_START);
        }
        if (IDENTITY.contains(text)) {
            return Optional.of(INTRO + " I only use what's in your documents, so if something "
                + "isn't there, I'll tell you instead of guessing.\n\n**How can I help you?**"
                + WAYS_TO_START);
        }
        if (HELP.contains(text)) {
            return Optional.of("Happy to help! " + INTRO + WAYS_TO_START
                + "\n\nWhat would you like to start with?");
        }
        if (THANKS.contains(text)) {
            return Optional.of("You're very welcome! 😊 Ask me anything else about your documents "
                + "whenever you're ready.");
        }
        if (GOODBYE.contains(text)) {
            return Optional.of("Goodbye! 👋 Your documents and chats will be right here when you "
                + "come back.");
        }
        if (ACKNOWLEDGEMENTS.contains(text)) {
            return Optional.of("Great! Ask me anything about your documents whenever you're ready.");
        }
        return Optional.empty();
    }

    /**
     * Lower-cases, strips punctuation and emoji, squeezes stretched letters
     * ("heyyy" becomes "hey") and drops words that only address the assistant.
     * A greeting in front is dropped too, so "hello hello" is the same message
     * as "hello". Returns an empty string for anything too long to be small talk.
     */
    static String normalise(String message) {
        if (message == null) {
            return "";
        }
        String text = message.toLowerCase(Locale.ROOT)
            .replaceAll("['\u2019]", "")
            .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
            .replaceAll("(.)\\1{2,}", "$1")
            .trim()
            .replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String word : text.split(" ")) {
            if (ADDRESS_WORDS.contains(word)) {
                continue;
            }
            if (kept.size() == MAX_WORDS) {
                return "";
            }
            kept.add(word);
        }
        if (kept.size() > 1 && GREETINGS.contains(kept.get(0))) {
            kept.remove(0);
        }
        return String.join(" ", kept);
    }
}
