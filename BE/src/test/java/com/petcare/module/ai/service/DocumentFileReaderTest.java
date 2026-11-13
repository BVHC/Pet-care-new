package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Đọc file nạp tri thức (BR-AI-29, docs/adr/0134 D1): kiểu file theo phần mở rộng <b>và</b> byte đầu, giới hạn cỡ, zip bomb,
 * PDF không có chữ, tách văn bản dài theo {@code ##} để mỗi phần ≤ 20 000 ký tự (BR-AI-14).
 */
class DocumentFileReaderTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] docx(String heading, String body) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph h = doc.createParagraph();
            h.setStyle("Heading1");
            h.createRun().setText(heading);
            doc.createParagraph().createRun().setText(body);
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void assertRejected(Runnable call, String fragment) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-AI-29").hasMessageContaining(fragment);
    }

    // ---------------------------------------------------------------- đọc từng kiểu

    @Test
    void markdownAndTextKeepTheTextDropTheBomAndNormalizeLineEndings() {
        byte[] bytes = utf8("﻿# Chăm sóc mèo\r\n\r\nCho ăn đủ bữa.\r\n");
        assertThat(DocumentFileReader.read("meo.md", bytes).text()).isEqualTo("# Chăm sóc mèo\n\nCho ăn đủ bữa.");
        assertThat(DocumentFileReader.read("meo.TXT", bytes).kind()).isEqualTo("TXT");
    }

    @Test
    void markdownFrontmatterIsDroppedAndItsTitleBecomesTheTitleHint() {
        byte[] bytes = utf8("---\r\ndocument_id: PET-DOG-003\r\ntitle: Hành vi và huấn luyện chó\r\nstatus: DRAFT\r\n---\r\n\r\n# Hành vi\r\n\r\nNội dung");
        DocumentFileReader.Extracted extracted = DocumentFileReader.read("DOG_BEHAVIOR.md", bytes);
        assertThat(extracted.text()).isEqualTo("# Hành vi\n\nNội dung");
        assertThat(extracted.titleHint()).isEqualTo("Hành vi và huấn luyện chó");
    }

    @Test
    void textWithoutFrontmatterHasNoTitleHintAndALoneDashLineIsKept() {
        assertThat(DocumentFileReader.read("a.md", utf8("# Tiêu đề\n\nA")).titleHint()).isNull();
        String withRule = "Mở đầu\n---\nphần sau";   // đường kẻ ngang giữa văn bản không phải frontmatter
        assertThat(DocumentFileReader.read("b.md", utf8(withRule)).text()).isEqualTo(withRule);
        assertThat(DocumentFileReader.read("c.txt", utf8("---\ntitle: Không phải md\n---\nx")).titleHint())
                .as(".txt không có frontmatter").isNull();
    }

    @Test
    void docxHeadingStylesBecomeMarkdownHeadings() throws Exception {
        String text = DocumentFileReader.read("goc.docx", docx("Vệ sinh tai", "Lau tai nhẹ nhàng.")).text();
        assertThat(text).contains("## Vệ sinh tai").contains("Lau tai nhẹ nhàng.");
        assertThat(text.indexOf("## Vệ sinh tai")).isLessThan(text.indexOf("Lau tai"));
    }

    @Test
    void pdfTextIsExtracted() throws Exception {
        assertThat(DocumentFileReader.read("v.pdf", pdf("Vaccine schedule basics")).text())
                .contains("Vaccine schedule basics");
    }

    // ---------------------------------------------------------------- từ chối

    @Test
    void aPdfWithoutAnyTextLayerIsRejectedBecauseOcrIsOutOfScope() throws Exception {
        assertRejected(() -> DocumentFileReader.read("scan.pdf", pdfBytesUnchecked(null)), "OCR");
    }

    @Test
    void theExtensionMustMatchTheFirstBytes() throws Exception {
        assertRejected(() -> DocumentFileReader.read("gia.pdf", utf8("không phải pdf")), "nội dung");
        assertRejected(() -> DocumentFileReader.read("gia.docx", utf8("không phải zip")), "nội dung");
        assertRejected(() -> DocumentFileReader.read("gia.docx", pdfBytesUnchecked("x")), "nội dung");
    }

    @Test
    void unsupportedExtensionsAreRejected() {
        assertRejected(() -> DocumentFileReader.read("chay.exe", utf8("x")), "định dạng");
        assertRejected(() -> DocumentFileReader.read("khongduoi", utf8("x")), "định dạng");
    }

    @Test
    void emptyAndOversizedFilesAreRejected() {
        assertRejected(() -> DocumentFileReader.read("rong.md", new byte[0]), "rỗng");
        assertRejected(() -> DocumentFileReader.read("lon.txt", new byte[DocumentFileReader.MAX_FILE_BYTES + 1]), "tối đa");
    }

    @Test
    void aDocxWhoseXmlInflatesFarBeyondTheLimitIsRejectedInsteadOfExhaustingMemory() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] chunk = new byte[1 << 20];
            java.util.Arrays.fill(chunk, (byte) ' ');
            for (int i = 0; i < 40; i++) {
                zip.write(chunk);   // 40 MB nén còn vài chục KB
            }
            zip.closeEntry();
        }
        byte[] bomb = out.toByteArray();
        assertThat(bomb.length).isLessThan(DocumentFileReader.MAX_FILE_BYTES);
        assertRejected(() -> DocumentFileReader.read("bom.docx", bomb), "đọc được");
    }

    // ---------------------------------------------------------------- tách

    @Test
    void shortTextStaysOnePart() {
        assertThat(DocumentFileReader.split("## A\nngắn")).containsExactly("## A\nngắn");
    }

    @Test
    void longTextSplitsAtSectionsAndEveryPartFitsTheLimit() {
        String section = "## Mục\n" + "x".repeat(9_000);
        String text = String.join("\n", section, section, section, section, section);   // ≈ 45 000
        List<String> parts = DocumentFileReader.split(text);
        assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(AiDocument.CONTENT_MAX_LENGTH));
        assertThat(parts).allSatisfy(p -> assertThat(p).startsWith("## Mục"));
        assertThat(String.join("\n", parts).replace("\n", "")).isEqualTo(text.replace("\n", ""));
    }

    @Test
    void aSingleHugeSectionIsHardCutAtLineBoundaries() {
        String line = "y".repeat(990);
        String text = "## Dài\n" + String.join("\n", java.util.Collections.nCopies(60, line));   // ≈ 59 000
        List<String> parts = DocumentFileReader.split(text);
        assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
        assertThat(parts).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(AiDocument.CONTENT_MAX_LENGTH));
        assertThat(String.join("\n", parts).replace("\n", "")).isEqualTo(text.replace("\n", ""));
    }

    private static byte[] pdfBytesUnchecked(String text) {
        try {
            return pdf(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
