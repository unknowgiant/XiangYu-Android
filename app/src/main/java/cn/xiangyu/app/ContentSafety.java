package cn.xiangyu.app;

/** Filters unsafe public-page titles without packaging the blocked phrases as plain text. */
final class ContentSafety {
    private static final int[] BLOCKED_TOKEN_HASHES = {
        798378, 1059987, 1142670, 1148135, 807771, 1033928, 39160812, 935424633
    };

    static boolean isSafeTitle(String value) {
        if (value == null || value.isEmpty() || value.contains("http://")
                || value.contains("https://") || value.contains("www.")) return false;
        for (int start = 0; start < value.length(); start++) {
            int hash = 0;
            for (int end = start; end < value.length() && end < start + 4; end++) {
                hash = 31 * hash + value.charAt(end);
                int length = end - start + 1;
                if (length >= 2 && isBlockedHash(hash)) return false;
            }
        }
        return true;
    }

    private static boolean isBlockedHash(int value) {
        for (int blocked : BLOCKED_TOKEN_HASHES) if (blocked == value) return true;
        return false;
    }

    private ContentSafety() { }
}
