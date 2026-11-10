package com.petcare.module.ai.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đọc tệp tài nguyên danh sách của chatbot ({@code src/main/resources/ai/}, 07 §4.6: không là [CFG] vì
 * {@code SystemConfigApi} chỉ có kiểu số, bool, giờ). UTF-8, bỏ BOM và {@code \r}, bỏ dòng trống và dòng {@code #};
 * mọi mục chuẩn hóa NFC. Tệp thiếu hoặc rỗng làm bean khởi động lỗi: thiếu danh sách cấp cứu là lỗi an toàn.
 */
final class ResourceLines {

    private ResourceLines() {
    }

    /** Mỗi dòng một mục. */
    static List<String> read(String path) {
        List<String> lines = lines(path);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Tệp tài nguyên rỗng: " + path);
        }
        return lines;
    }

    /** Tệp chia mục bằng dòng {@code [TÊN]}; mỗi mục phải có ít nhất một dòng. */
    static Map<String, List<String>> readSections(String path, List<String> required) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : lines(path)) {
            if (line.startsWith("[") && line.endsWith("]")) {
                current = sections.computeIfAbsent(line.substring(1, line.length() - 1), name -> new ArrayList<>());
            } else if (current == null) {
                throw new IllegalStateException(path + ": dòng nằm ngoài mục nào: " + line);
            } else {
                current.add(line);
            }
        }
        for (String name : required) {
            if (sections.getOrDefault(name, List.of()).isEmpty()) {
                throw new IllegalStateException(path + ": thiếu hoặc rỗng mục [" + name + "]");
            }
        }
        return sections;
    }

    private static List<String> lines(String path) {
        try (InputStream in = ResourceLines.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Không thấy tệp tài nguyên: " + path);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            return text.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> Normalizer.normalize(line, Normalizer.Form.NFC)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được tệp tài nguyên " + path, e);
        }
    }
}
