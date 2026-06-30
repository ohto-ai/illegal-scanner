package com.illegalscanner.validator;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.scanner.ItemAccessor;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BookValidator implements ItemValidator {

    private final IllegalScanner plugin;

    public BookValidator(IllegalScanner plugin) { this.plugin = plugin; }

    @Override
    public String getName() { return "BookValidator"; }

    @Override
    public List<Violation> validate(ItemStack itemStack) {
        List<Violation> violations = new ArrayList<>();
        if (!ItemAccessor.hasWrittenBookContent(itemStack)) return violations;

        int maxPages = plugin.getConfigManager().getConfig()
                .getInt("validation.book_max_pages", 100);

        List<String> pages = ItemAccessor.getBookPages(itemStack);
        if (pages.size() > maxPages) {
            violations.add(Violation.illegal("BOOK_PAGES_EXCEEDED",
                    msg("BOOK_PAGES_EXCEEDED",
                            "{found}", String.valueOf(pages.size()),
                            "{max}", String.valueOf(maxPages))));
        }

        for (int i = 0; i < pages.size(); i++) {
            String plain = pages.get(i);
            if (plain.length() > 1000) {
                violations.add(Violation.warn("BOOK_PAGE_TOO_LONG",
                        "Book page " + (i + 1) + " is unusually long: " +
                                plain.length() + " chars"));
            }
        }

        String author = ItemAccessor.getBookAuthor(itemStack);
        if (author != null && author.length() > 16) {
            violations.add(Violation.illegal("BOOK_AUTHOR_TOO_LONG",
                    msg("BOOK_AUTHOR_TOO_LONG",
                            "{length}", String.valueOf(author.length()))));
        }

        String title = ItemAccessor.getBookTitlePlain(itemStack);
        if (title != null && title.length() > 32) {
            violations.add(Violation.warn("BOOK_TITLE_TOO_LONG",
                    "Book title too long: " + title.length() + " chars (max 32)"));
        }

        return violations;
    }

    private String msg(String key, String... r) {
        String t = plugin.getConfigManager().getConfig()
                .getString("violation_messages." + key, key);
        for (int i = 0; i < r.length; i += 2) t = t.replace(r[i], r[i + 1]);
        return t;
    }
}
