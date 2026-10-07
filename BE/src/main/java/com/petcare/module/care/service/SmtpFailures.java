package com.petcare.module.care.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;

/**
 * Phân loại lỗi gửi SMTP của ST20 (docs/adr/0012 mục 5), dùng chung cho gửi từng thư và gửi theo lô (docs/adr/0017):
 * máy chủ từ chối người nhận → lỗi vĩnh viễn; không kết nối / hết thời gian / sai xác thực → lỗi cấp máy chủ, dừng lượt.
 */
final class SmtpFailures {

    private SmtpFailures() {
    }

    /** Máy chủ từ chối địa chỉ người nhận: thử lại không khỏi. */
    static boolean isRejectedRecipient(Throwable error) {
        return causeChain(error).stream().anyMatch(cause -> cause instanceof SendFailedException failed
                && failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0);
    }

    /** Lỗi của cả máy chủ chứ không của riêng thư: các thư sau cũng sẽ lỗi, nên dừng lượt chạy. */
    static boolean isServerLevel(Throwable error) {
        return causeChain(error).stream().anyMatch(cause -> cause instanceof MailAuthenticationException
                || cause instanceof AuthenticationFailedException || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException || cause instanceof UnknownHostException);
    }

    /** Lỗi kèm cả các lỗi con: cause chain và lỗi theo từng thư của {@link MailSendException}. */
    static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        List<Throwable> pending = new ArrayList<>(List.of(error));
        while (!pending.isEmpty() && chain.size() < 32) {
            Throwable current = pending.remove(0);
            if (current == null || chain.contains(current)) {
                continue;
            }
            chain.add(current);
            pending.add(current.getCause());
            if (current instanceof MailSendException send) {
                pending.addAll(List.of(send.getMessageExceptions()));
            }
        }
        return chain;
    }

    static String describe(Throwable error) {
        List<Throwable> chain = causeChain(error);
        Throwable root = chain.get(chain.size() - 1);
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return root == error ? text : text + " | " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
