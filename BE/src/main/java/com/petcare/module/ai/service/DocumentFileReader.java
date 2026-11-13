package com.petcare.module.ai.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Đọc file nạp tri thức thành văn bản Markdown thô (BR-AI-29, docs/adr/0134 D1): {@code .md}, {@code .txt}, {@code .docx},
 * {@code .pdf}. Kiểu file phải khớp cả phần mở rộng lẫn byte đầu; chỉ văn bản được giữ, không lưu file gốc. PDF không có
 * lớp chữ (scan) bị từ chối vì OCR ngoài phạm vi. Mọi lỗi đọc thành 400 {@code BR-AI-29}.
 *
 * <p>shortcut: giới hạn cỡ cố định {@link #MAX_FILE_BYTES} và chưa có hết giờ trích; ADR-0134 D1 đặt trần là [CFG]
 * {@code ai.ingest.max_file_kb} và hết giờ 20 giây, làm khi có V52.
 */
public final class DocumentFileReader {

    static final String RULE = "BR-AI-29";
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_DOCX_ENTRY_BYTES = 10L * 1024 * 1024;   // chặn zip bomb: XML giải nén tối đa 10 MB
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACE = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern SECTION = Pattern.compile("(?m)(?=^## )");
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\n(.*?)\\n---\\n", Pattern.DOTALL);
    private static final Pattern FRONTMATTER_TITLE = Pattern.compile("(?m)^title:[ \\t]*(.+?)[ \\t]*$");

    static {
        ZipSecureFile.setMaxEntrySize(MAX_DOCX_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_DOCX_ENTRY_BYTES);
    }

    /** {@code titleHint}: {@code title:} của frontmatter YAML ở file {@code .md}, nếu có; ngược lại {@code null}. */
    public record Extracted(String text, String kind, String titleHint) {
    }

    private DocumentFileReader() {
    }

    public static Extracted read(String fileName, byte[] bytes) {
        String ext = extensionOf(fileName);
        if (bytes == null || bytes.length == 0) {
            throw reject("File rỗng");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw reject("File vượt quá tối đa " + MAX_FILE_BYTES / 1024 + " kB");
        }
        String raw;
        switch (ext) {
            case "md", "txt" -> raw = new String(bytes, StandardCharsets.UTF_8);
            case "docx" -> raw = readDocx(fileName, bytes);
            case "pdf" -> raw = readPdf(fileName, bytes);
            default -> throw reject("File có định dạng không được hỗ trợ (chỉ .md, .txt, .docx, .pdf)");
        }
        String text = normalize(raw);
        String titleHint = null;
        if (ext.equals("md")) {   // file docs_RAG và nhiều file Markdown mở đầu bằng frontmatter YAML: không phải nội dung trả lời
            Matcher front = FRONTMATTER.matcher(text);
            if (front.find()) {
                Matcher title = FRONTMATTER_TITLE.matcher(front.group(1));
                titleHint = title.find() ? title.group(1).replaceAll("^[\"']|[\"']$", "") : null;
                text = text.substring(front.end()).strip();
            }
        }
        if (text.isEmpty()) {
            throw reject("File không có chữ để nạp");
        }
        return new Extracted(text, ext.toUpperCase(Locale.ROOT), titleHint);
    }

    /** Tách theo mục {@code ##} thành các phần ≤ 20 000 ký tự (BR-AI-14); mục đơn quá dài bị cắt ở ranh giới dòng. */
    public static List<String> split(String text) {
        int max = AiDocument.CONTENT_MAX_LENGTH;
        if (text.length() <= max) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String section : SECTION.split(text)) {
            if (section.length() > max) {
                flush(parts, current);
                parts.addAll(hardCut(section, max));
                continue;
            }
            if (current.length() + section.length() > max) {
                flush(parts, current);
            }
            current.append(section);
        }
        flush(parts, current);
        return parts;
    }

    // ---------------------------------------------------------------- từng kiểu

    private static String readDocx(String fileName, byte[] bytes) {
        if (!startsWith(bytes, 'P', 'K', 3, 4)) {
            throw mismatch(".docx");
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(out, paragraph);
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText().replace('\n', ' ').trim());
                        }
                        out.append(String.join(" | ", cells)).append('\n');
                    }
                    out.append('\n');
                }
            }
            return out.toString();
        } catch (BusinessRuleViolationException e) {
            throw e;
        } catch (Exception e) {
            throw reject("Không đọc được file " + fileName + " (hỏng hoặc không phải Word .docx hợp lệ)");
        }
    }

    private static void appendParagraph(StringBuilder out, XWPFParagraph paragraph) {
        String text = paragraph.getText().trim();
        if (text.isEmpty()) {
            out.append('\n');
            return;
        }
        String style = paragraph.getStyle();
        if (style != null && (style.startsWith("Heading") || style.equals("Title"))) {
            out.append("\n## ").append(text).append("\n\n");
        } else if (paragraph.getNumID() != null) {
            out.append("- ").append(text).append('\n');
        } else {
            out.append(text).append("\n\n");
        }
    }

    private static String readPdf(String fileName, byte[] bytes) {
        if (!startsWith(bytes, '%', 'P', 'D', 'F')) {
            throw mismatch(".pdf");
        }
        try (PDDocument doc = PDDocument.load(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            if (text == null || text.isBlank()) {
                throw reject("PDF không có lớp chữ (file scan); OCR ngoài phạm vi");
            }
            return text;
        } catch (BusinessRuleViolationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw reject("Không đọc được file " + fileName + " (hỏng, có mật khẩu hoặc không phải PDF hợp lệ)");
        }
    }

    // ---------------------------------------------------------------- tiện ích

    private static String normalize(String raw) {
        String text = raw.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        text = CONTROL.matcher(text).replaceAll("");
        text = TRAILING_SPACE.matcher(text).replaceAll("");
        text = BLANK_RUN.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    private static void flush(List<String> parts, StringBuilder current) {
        String part = current.toString().strip();
        if (!part.isEmpty()) {
            parts.add(part);
        }
        current.setLength(0);
    }

    private static List<String> hardCut(String section, int max) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String line : section.split("(?<=\n)")) {
            String rest = line;
            while (rest.length() > max) {   // một dòng dài hơn trần: cắt cứng
                flush(pieces, piece);
                pieces.add(rest.substring(0, max));
                rest = rest.substring(max);
            }
            if (piece.length() + rest.length() > max) {
                flush(pieces, piece);
            }
            piece.append(rest);
        }
        flush(pieces, piece);
        return pieces;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, int a, int b, int c, int d) {
        return bytes.length >= 4 && bytes[0] == a && bytes[1] == b && bytes[2] == c && bytes[3] == d;
    }

    private static BusinessRuleViolationException mismatch(String ext) {
        return reject("File có nội dung không khớp với phần mở rộng " + ext);
    }

    private static BusinessRuleViolationException reject(String message) {
        return new BusinessRuleViolationException(RULE, message);
    }
}
