package com.verityai.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SmallTalkTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "hello", "Hello", "HELLO!", "hi", "hii", "hey", "heyyy", "hellooo", "hello there",
        "Hi VerityAI", "hey verity ai!", "good morning", "namaste", "hi 👋", "  hello   ",
        "hello hello", "hi hello", "hey hi"
    })
    void greetingsIntroduceVerityAiAndAskHowToHelp(String message) {
        Optional<String> reply = SmallTalk.replyFor(message);
        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("VerityAI").contains("How can I help you today?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"how are you", "How are you?", "hi, how are you", "what's up", "whats up"})
    void howAreYouGetsAFriendlyReply(String message) {
        assertThat(SmallTalk.replyFor(message)).isPresent()
            .get().asString().contains("VerityAI");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "who are you", "Who are you?", "what is your name", "introduce yourself", "are you a bot"
    })
    void identityQuestionsExplainWhoItIs(String message) {
        assertThat(SmallTalk.replyFor(message)).isPresent()
            .get().asString().contains("VerityAI").contains("document research assistant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"help", "what can you do", "how can you help me", "how does this work"})
    void helpQuestionsListWaysToStart(String message) {
        assertThat(SmallTalk.replyFor(message)).isPresent()
            .get().asString().contains("things you can ask me");
    }

    @ParameterizedTest
    @ValueSource(strings = {"thanks", "Thank you!", "thx", "thanks a lot"})
    void thanksIsAcknowledged(String message) {
        assertThat(SmallTalk.replyFor(message)).isPresent()
            .get().asString().contains("welcome");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bye", "goodbye", "see you later"})
    void goodbyeIsAcknowledged(String message) {
        assertThat(SmallTalk.replyFor(message)).isPresent()
            .get().asString().contains("Goodbye");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "hello, what is the revenue in 2023?",
        "hi can you summarise page 2",
        "what is this document about",
        "summarize the report",
        "thanks, but what about section 4",
        "who are the authors of this paper",
        "help me understand clause 7",
        "what can you tell me about the budget",
        "how are you calculating the totals"
    })
    void realQuestionsAreNeverTreatedAsSmallTalk(String message) {
        assertThat(SmallTalk.replyFor(message)).isEmpty();
    }

    @Test
    void emptyOrNullIsNotSmallTalk() {
        assertThat(SmallTalk.replyFor(null)).isEmpty();
        assertThat(SmallTalk.replyFor("")).isEmpty();
        assertThat(SmallTalk.replyFor("   ")).isEmpty();
        assertThat(SmallTalk.replyFor("!!!")).isEmpty();
    }
}
