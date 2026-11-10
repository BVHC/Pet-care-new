package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.petcare.module.ai.service.DocumentChunker.Chunk;

/** Chia đoạn theo BR-AI-15: tiêu đề Markdown, 300–1 200 ký tự, chồng lấn 100 ký tự khi phải cắt tiếp. */
class DocumentChunkerTest {

    private static final String TITLE = "Chăm sóc chó con";

    private static String words(int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; text.length() < length; i++) {
            text.append("từ").append(i).append(' ');
        }
        return text.substring(0, length).trim();
    }

    @Test
    void splitsAtMarkdownHeadingsAndKeepsTheHeadingOutOfTheText() {
        String content = "## Cho ăn\n" + words(400) + "\n\n## Tắm rửa\n" + words(450);

        List<Chunk> chunks = DocumentChunker.chunk(TITLE, content);

        assertThat(chunks).extracting(Chunk::heading).containsExactly("Cho ăn", "Tắm rửa");
        assertThat(chunks).extracting(Chunk::text).allSatisfy(text -> assertThat(text).doesNotContain("##"));
    }

    @Test
    void textBeforeTheFirstHeadingHasNoHeading() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, words(350) + "\n\n## Mục\n" + words(350));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isNull();
    }

    @Test
    void ordinalsStartAtOneAndAreConsecutive() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## A\n" + words(400) + "\n## B\n" + words(400) + "\n## C\n"
                + words(400));

        assertThat(chunks).extracting(Chunk::ordinal).containsExactly(1, 2, 3);
    }

    @Test
    void shortSectionIsMergedIntoTheNextOneWhenTheResultFits() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## Ngắn\nChỉ một câu ngắn.\n## Dài\n" + words(500));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Chỉ một câu ngắn.").contains("Dài");
    }

    @Test
    void shortLastSectionIsMergedIntoThePreviousOne() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## Dài\n" + words(500) + "\n## Cuối\nVài chữ.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).endsWith("Vài chữ.");
    }

    @Test
    void longSectionIsCutIntoPiecesOfAtMost1200CharactersWithOverlap() {
        String section = IntStream.range(0, 6).mapToObj(i -> words(700)).reduce((a, b) -> a + "\n\n" + b).orElseThrow();

        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## Dài\n" + section);

        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(chunks).extracting(c -> c.text().length()).allMatch(length -> length <= 1200);
        for (int i = 1; i < chunks.size(); i++) {
            String previousTail = chunks.get(i - 1).text();
            previousTail = previousTail.substring(Math.max(0, previousTail.length() - 100));
            assertThat(previousTail).as("đoạn %d chồng lấn đoạn trước", i + 1)
                    .contains(chunks.get(i).text().substring(0, 15));
        }
    }

    @Test
    void textWithoutAnyBreakIsHardCutSoNoPieceExceeds1200Characters() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "x".repeat(5000));

        assertThat(chunks.size()).isGreaterThan(3);
        assertThat(chunks).extracting(c -> c.text().length()).allMatch(length -> length <= 1200);
    }

    @Test
    void textToEmbedIsTitleThenHeadingThenText() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## Cho ăn\n" + words(400));

        assertThat(chunks.get(0).embedText()).isEqualTo(TITLE + "\nCho ăn\n" + chunks.get(0).text());
    }

    @Test
    void textToEmbedOmitsTheHeadingWhenThereIsNone() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, words(400));

        assertThat(chunks.get(0).embedText()).isEqualTo(TITLE + "\n" + chunks.get(0).text());
    }

    @Test
    void headingLongerThanTheColumnIsTruncatedTo200() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## " + "h".repeat(300) + "\n" + words(400));

        assertThat(chunks.get(0).heading()).hasSize(200);
    }

    @Test
    void windowsLineEndingsAreHandled() {
        List<Chunk> chunks = DocumentChunker.chunk(TITLE, "## A\r\n" + words(400) + "\r\n## B\r\n" + words(400));

        assertThat(chunks).extracting(Chunk::heading).containsExactly("A", "B");
    }

    @Test
    void blankContentGivesNoChunks() {
        assertThat(DocumentChunker.chunk(TITLE, "  \n\n ")).isEmpty();
        assertThat(DocumentChunker.chunk(TITLE, "## Chỉ có tiêu đề")).isEmpty();
    }
}
